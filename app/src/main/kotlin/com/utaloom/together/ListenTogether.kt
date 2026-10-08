package com.utaloom.together

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerHooks
import com.utaloom.together.proto.Listentogether.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.abs

internal const val MAX_ROOM_PAYLOAD_BYTES = 1_048_576

/** Trust boundary: cap the envelope before protobuf decoding and gzip output before allocation can grow. */
internal fun decodeRoomEnvelope(data: ByteArray): Pair<String, ByteArray> {
    require(data.size <= MAX_ROOM_PAYLOAD_BYTES) { "Room envelope exceeds 1 MiB" }
    val env = Envelope.parseFrom(data)
    val raw = env.payload.toByteArray()
    val payload = if (env.compressed) GZIPInputStream(ByteArrayInputStream(raw)).use { it.readNBytes(MAX_ROOM_PAYLOAD_BYTES + 1) } else raw
    require(payload.size <= MAX_ROOM_PAYLOAD_BYTES) { "Decoded room payload exceeds 1 MiB" }
    return env.type to payload
}

enum class Connection { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }
enum class Role { NONE, HOST, GUEST }

data class RoomUser(val id: String, val name: String, val isHost: Boolean, val connected: Boolean = true)
data class JoinRequest(val userId: String, val username: String)
data class Suggestion(val id: String, val from: String, val track: TrackInfo)

data class TogetherState(
    val connection: Connection = Connection.DISCONNECTED,
    val role: Role = Role.NONE,
    val roomCode: String? = null,
    val userId: String? = null,
    val users: List<RoomUser> = emptyList(),
    val joinRequests: List<JoinRequest> = emptyList(),
    val suggestions: List<Suggestion> = emptyList(),
    val waitingApproval: Boolean = false,
    val waitingForBuffer: List<String> = emptyList(),
    val message: String? = null,
    val logs: List<String> = emptyList(),
)

/** Listen Together client compatible with Metrolist's metroserver protocol (protobuf envelopes over WebSocket). */
object ListenTogether : PlayerHooks {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(TogetherState())
    val state: StateFlow<TogetherState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var ws: WebSocket? = null
    private val socketGeneration = AtomicLong()
    private var pingJob: Job? = null
    private var reconnectJob: Job? = null
    @Volatile private var compression = false
    @Volatile private var sessionToken: String? = null
    private var pendingAction: (() -> Unit)? = null
    @Volatile private var userLeft = true
    private var attempts = 0
    private var lastRevision = 0L
    private var pingSeq = 0L
    @Volatile private var clockOffset: Long? = null // serverTime - localTime

    private fun serverNow() = System.currentTimeMillis() + (clockOffset ?: 0)

    // region public API

    fun createRoom(username: String) = withConnection {
        saveUsername(username)
        send("create_room", CreateRoomPayload.newBuilder().setUsername(username).build())
    }

    fun joinRoom(code: String, username: String) = withConnection {
        saveUsername(username)
        _state.update { it.copy(waitingApproval = true) }
        send("join_room", JoinRoomPayload.newBuilder().setRoomCode(code.trim().uppercase()).setUsername(username).build())
    }

    @Synchronized fun leaveRoom() {
        send("leave_room", null)
        resetRoom()
        disconnect()
    }

    fun approve(userId: String) {
        send("approve_join", ApproveJoinPayload.newBuilder().setUserId(userId).build())
        _state.update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.userId == userId }) }
    }

    fun reject(userId: String) {
        send("reject_join", RejectJoinPayload.newBuilder().setUserId(userId).setReason("Rejected by host").build())
        _state.update { s -> s.copy(joinRequests = s.joinRequests.filterNot { it.userId == userId }) }
    }

    fun kick(userId: String) = send("kick_user", KickUserPayload.newBuilder().setUserId(userId).setReason("Kicked by host").build())

    fun transferHost(userId: String) = send("transfer_host", TransferHostPayload.newBuilder().setNewHostId(userId).build())

    fun suggest(song: Song) {
        send("suggest_track", SuggestTrackPayload.newBuilder().setTrackInfo(song.toTrack()).build())
        _state.update { it.copy(message = "Suggestion sent to host") }
    }

    fun approveSuggestion(id: String) {
        send("approve_suggestion", ApproveSuggestionPayload.newBuilder().setSuggestionId(id).build())
        _state.update { s -> s.copy(suggestions = s.suggestions.filterNot { it.id == id }) }
    }

    fun rejectSuggestion(id: String) {
        send("reject_suggestion", RejectSuggestionPayload.newBuilder().setSuggestionId(id).setReason("Rejected by host").build())
        _state.update { s -> s.copy(suggestions = s.suggestions.filterNot { it.id == id }) }
    }

    fun requestSync() = send("request_sync", null)

    fun clearMessage() = _state.update { it.copy(message = null) }

    val isGuest get() = _state.value.role == Role.GUEST
    val inRoom get() = _state.value.roomCode != null

    // endregion

    // region connection

    @Synchronized private fun withConnection(action: () -> Unit) {
        if (_state.value.connection == Connection.CONNECTED) return action()
        pendingAction = action
        if (_state.value.connection in setOf(Connection.CONNECTING, Connection.RECONNECTING)) return
        connect()
    }

    @Synchronized private fun connect() {
        val url = Stores.settings.value.ltServerUrl.trim()
        if (!url.startsWith("ws://") && !url.startsWith("wss://")) {
            _state.update { it.copy(connection = Connection.ERROR, message = "Invalid server URL") }
            return
        }
        userLeft = false
        clockOffset = null
        compression = false
        val gen = socketGeneration.incrementAndGet()
        _state.update { it.copy(connection = if (attempts > 0) Connection.RECONNECTING else Connection.CONNECTING) }
        log("Connecting to $url")
        // Contains "okhttp" so stock metroserver user-agent policies let desktop clients host rooms.
        runCatching {
            val req = Request.Builder().url(url).header("User-Agent", "Utaloom/1.0 okhttp").build()
            ws = http.newWebSocket(req, Listener(gen))
        }.onFailure {
            _state.update { s -> s.copy(connection = Connection.ERROR, waitingApproval = false, message = "Can't connect: ${it.message}") }
        }
    }

    @Synchronized private fun disconnect() {
        userLeft = true
        socketGeneration.incrementAndGet()
        clockOffset = null
        pendingAction = null
        compression = false
        attempts = 0
        reconnectJob?.cancel()
        pingJob?.cancel()
        ws?.close(1000, "bye")
        ws = null
        _state.update { it.copy(connection = Connection.DISCONNECTED) }
    }

    @Synchronized private fun scheduleReconnect() {
        if (userLeft) return
        reconnectJob?.cancel()
        val gen = socketGeneration.get()
        reconnectJob = scope.launch {
            attempts++
            val wait = (1000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000)
            log("Reconnecting in ${wait / 1000}s")
            _state.update { it.copy(connection = Connection.RECONNECTING) }
            delay(wait)
            synchronized(ListenTogether) {
                if (gen != socketGeneration.get() || userLeft) return@launch
                sessionToken?.let { token -> pendingAction = { send("reconnect", ReconnectPayload.newBuilder().setSessionToken(token).build()) } }
                connect()
            }
        }
    }

    private class Listener(private val gen: Long) : WebSocketListener() {
        private fun active(socket: WebSocket) = !userLeft && gen == socketGeneration.get() && (ws == null || ws === socket)

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(ListenTogether) {
                if (!active(webSocket)) { webSocket.close(1000, "Superseded"); return }
                ws = webSocket
                attempts = 0
                compression = false
                _state.update { it.copy(connection = Connection.CONNECTED) }
                log("Connected")
                send(
                    "client_capabilities",
                    ClientCapabilities.newBuilder().setSupportsProtobuf(true).setSupportsCompression(true).setClientVersion("utaloom-1.0").build(),
                )
                pingJob?.cancel()
                pingJob = scope.launch {
                    while (isActive && active(webSocket)) {
                        send("ping", PingPayload.newBuilder().setClientTime(System.currentTimeMillis()).setSequence(++pingSeq).build())
                        delay(if (clockOffset == null) 1000 else 15_000)
                    }
                }
                pendingAction?.also { pendingAction = null }?.invoke()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            if (!active(webSocket)) return
            runCatching {
                require(bytes.size <= MAX_ROOM_PAYLOAD_BYTES) { "Room envelope exceeds 1 MiB" }
                handle(bytes.toByteArray(), gen)
            }.onFailure { log("Bad message: ${it.message}") }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDrop(webSocket, "closed $code $reason")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = onDrop(webSocket, "failure: ${t.message}")

        private fun onDrop(socket: WebSocket, why: String) {
            synchronized(ListenTogether) {
                if (!active(socket)) return
                socketGeneration.incrementAndGet()
                log("Disconnected ($why)")
                pingJob?.cancel()
                ws = null
                clockOffset = null
                _state.update { it.copy(connection = Connection.ERROR, waitingApproval = false, message = "Connection lost: $why") }
                if (inRoom) scheduleReconnect()
            }
        }
    }

    // endregion

    // region codec

    private fun send(type: String, payload: MessageLite?) {
        var bytes = payload?.toByteArray() ?: ByteArray(0)
        if (bytes.size > MAX_ROOM_PAYLOAD_BYTES - 128) { log("Message too large: $type"); return }
        var compressed = false
        if (compression && bytes.size > 100) {
            val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
            if (gz.size < bytes.size) { bytes = gz; compressed = true }
        }
        val env = Envelope.newBuilder().setType(type).setPayload(ByteString.copyFrom(bytes)).setCompressed(compressed).build()
        if (type != "ping") log("→ $type")
        ws?.send(env.toByteArray().toByteString())
    }

    @Synchronized private fun handle(data: ByteArray, gen: Long) {
        val (type, p) = decodeRoomEnvelope(data)
        if (gen != socketGeneration.get() || userLeft) return
        if (type != "pong") log("← $type")
        when (type) {
            "server_capabilities" -> compression = ServerCapabilities.parseFrom(p).supportsCompression
            "pong" -> PongPayload.parseFrom(p).let { pong ->
                val now = System.currentTimeMillis()
                val offset = ((pong.serverReceiveTime - pong.clientTime) + (pong.serverSendTime - now)) / 2
                clockOffset = clockOffset?.let { (it * 3 + offset) / 4 } ?: offset
            }
            "room_created" -> RoomCreatedPayload.parseFrom(p).let { r ->
                sessionToken = r.sessionToken
                becomeHost(r.roomCode, r.userId)
                _state.update { it.copy(users = listOf(RoomUser(r.userId, Stores.settings.value.ltUsername, true)), message = "Room created: ${r.roomCode}") }
                val snapshot = Player.state.value
                snapshot.current?.let { cur ->
                    onTrackChanged(cur, snapshot.queue, snapshot.index)
                    if (snapshot.isPlaying) onPlaying(snapshot.positionMs)
                }
            }
            "join_request" -> JoinRequestPayload.parseFrom(p).let { r ->
                if (Stores.settings.value.ltAutoApproveJoins) approve(r.userId)
                else _state.update { s -> s.copy(joinRequests = s.joinRequests + JoinRequest(r.userId, r.username)) }
            }
            "join_approved" -> JoinApprovedPayload.parseFrom(p).let { r ->
                sessionToken = r.sessionToken
                becomeGuest(r.roomCode, r.userId)
                applyRoomState(r.state)
            }
            "join_rejected" -> {
                _state.update { it.copy(waitingApproval = false, message = "Join rejected: ${JoinRejectedPayload.parseFrom(p).reason}") }
                disconnect()
            }
            "reconnected" -> ReconnectedPayload.parseFrom(p).let { r ->
                if (r.isHost) becomeHost(r.roomCode, r.userId) else becomeGuest(r.roomCode, r.userId)
                applyRoomState(r.state, playbackToo = !r.isHost)
            }
            "user_joined" -> UserJoinedPayload.parseFrom(p).let { u -> upsertUser(RoomUser(u.userId, u.username, false)) }
            "user_left" -> UserLeftPayload.parseFrom(p).let { u -> _state.update { s -> s.copy(users = s.users.filterNot { it.id == u.userId }) } }
            "user_reconnected" -> UserReconnectedPayload.parseFrom(p).let { u -> setConnected(u.userId, true) }
            "user_disconnected" -> UserDisconnectedPayload.parseFrom(p).let { u -> setConnected(u.userId, false) }
            "host_changed" -> HostChangedPayload.parseFrom(p).let { h ->
                _state.update { s -> s.copy(users = s.users.map { it.copy(isHost = it.id == h.newHostId) }, message = "${h.newHostName} is now the host") }
                val me = _state.value.userId
                if (h.newHostId == me) becomeHost(_state.value.roomCode!!, me) else becomeGuest(_state.value.roomCode!!, me!!)
            }
            "kicked" -> {
                val reason = KickedPayload.parseFrom(p).reason
                resetRoom()
                disconnect()
                _state.update { it.copy(message = "You were removed from the room: $reason") }
            }
            "sync_playback" -> onSyncPlayback(PlaybackActionPayload.parseFrom(p))
            "sync_state" -> SyncStatePayload.parseFrom(p).let { s ->
                if (s.revision >= lastRevision) {
                    lastRevision = s.revision
                    applyPlayback(s.currentTrack.takeIf { s.hasCurrentTrack() }, s.queueList, s.isPlaying, s.position, s.lastUpdate, s.volume)
                }
            }
            "buffer_wait" -> _state.update { it.copy(waitingForBuffer = BufferWaitPayload.parseFrom(p).waitingForList) }
            "buffer_complete" -> _state.update { it.copy(waitingForBuffer = emptyList()) }
            "suggestion_received" -> SuggestionReceivedPayload.parseFrom(p).let { r ->
                if (Stores.settings.value.ltAutoApproveSuggestions) approveSuggestion(r.suggestionId)
                else _state.update { s -> s.copy(suggestions = s.suggestions + Suggestion(r.suggestionId, r.fromUsername, r.trackInfo)) }
            }
            "suggestion_approved" -> _state.update { it.copy(message = "Host approved: ${SuggestionApprovedPayload.parseFrom(p).trackInfo.title}") }
            "suggestion_rejected" -> _state.update { it.copy(message = "Suggestion rejected") }
            "error" -> ErrorPayload.parseFrom(p).let { e ->
                log("Error ${e.code}: ${e.message}")
                if (e.code in setOf("session_not_found", "session_expired", "room_not_found")) {
                    resetRoom(); disconnect()
                }
                if (e.code != "stale_track") _state.update { it.copy(message = e.message, waitingApproval = false) }
            }
        }
    }

    // endregion

    // region room state

    private fun becomeHost(code: String, userId: String) {
        _state.update { it.copy(role = Role.HOST, roomCode = code, userId = userId, waitingApproval = false) }
        Player.remoteControlled = false
        Player.hooks = this
    }

    private fun becomeGuest(code: String, userId: String) {
        Player.setSleepTimer(null)
        _state.update { it.copy(role = Role.GUEST, roomCode = code, userId = userId, waitingApproval = false, joinRequests = emptyList(), suggestions = emptyList()) }
        Player.remoteControlled = true
        Player.hooks = this
    }

    private fun resetRoom() {
        Player.remoteControlled = false
        Player.hooks = null
        sessionToken = null
        lastRevision = 0
        clockOffset = null
        _state.update { TogetherState(connection = it.connection, logs = it.logs) }
    }

    private fun upsertUser(u: RoomUser) = _state.update { s -> s.copy(users = s.users.filterNot { it.id == u.id } + u) }
    private fun setConnected(id: String, c: Boolean) = _state.update { s -> s.copy(users = s.users.map { if (it.id == id) it.copy(connected = c) else it }) }

    private fun applyRoomState(rs: RoomState, playbackToo: Boolean = true) {
        lastRevision = rs.revision
        _state.update { it.copy(users = rs.usersList.map { u -> RoomUser(u.userId, u.username, u.isHost, u.isConnected) }) }
        if (playbackToo) applyPlayback(rs.currentTrack.takeIf { rs.hasCurrentTrack() }, rs.queueList, rs.isPlaying, rs.position, rs.lastUpdate, rs.volume)
    }

    private fun applyPlayback(track: TrackInfo?, queue: List<TrackInfo>, playing: Boolean, position: Long, at: Long, volume: Float) {
        if (_state.value.role != Role.GUEST || track == null) return
        val songs = listOf(track.toSong()) + queue.map { it.toSong() }
        val pos = if (playing) position + (serverNow() - at).coerceIn(0, 10_000) else position
        if (Player.state.value.current?.id != track.id) {
            Player.playQueue(songs, 0, fromRemote = true, play = playing, startAt = pos)
        } else {
            Player.setQueueFromRemote(songs, track.id)
            Player.seekTo(pos, fromRemote = true)
            if (playing) Player.play(fromRemote = true) else Player.pause(fromRemote = true)
        }
        if (Stores.settings.value.ltSyncVolume && volume.isFinite() && volume >= 0) Player.setVolume((volume * 100).toInt(), fromRemote = true)
    }

    private fun onSyncPlayback(a: PlaybackActionPayload) {
        if (a.revision != 0L && a.revision < lastRevision) return
        if (a.revision != 0L) lastRevision = a.revision
        val role = _state.value.role
        if (role == Role.HOST) {
            // Suggestions approved by us are inserted server-side; mirror them locally.
            if (a.action == "queue_add" && a.hasTrackInfo() && Player.state.value.queue.none { it.id == a.trackInfo.id }) {
                suppress = true
                try {
                    if (a.insertNext) Player.playNext(listOf(a.trackInfo.toSong())) else Player.addToQueue(listOf(a.trackInfo.toSong()))
                } finally { suppress = false }
            }
            return
        }
        if (role != Role.GUEST) return
        val cur = Player.state.value.current
        val elapsed = (serverNow() - a.serverTime).coerceIn(0, 10_000).takeIf { a.serverTime > 0 } ?: 0
        when (a.action) {
            "change_track" -> if (a.hasTrackInfo()) {
                Player.playQueue(listOf(a.trackInfo.toSong()) + a.queueList.map { it.toSong() }, 0, title = a.queueTitle.ifBlank { null }, fromRemote = true, play = false)
            }
            "play" -> {
                if (cur?.id != a.trackId && a.trackId.isNotEmpty()) return requestSync()
                val target = a.position + elapsed
                if (abs(Player.state.value.positionMs - target) > 800) Player.seekTo(target, fromRemote = true)
                Player.play(fromRemote = true)
            }
            "pause" -> {
                Player.pause(fromRemote = true)
                Player.seekTo(a.position, fromRemote = true)
            }
            "seek" -> Player.seekTo(a.position + if (Player.state.value.isPlaying) elapsed else 0, fromRemote = true)
            "queue_add", "queue_remove", "queue_clear", "sync_queue" -> {
                val c = cur ?: return
                Player.setQueueFromRemote(listOf(c) + a.queueList.map { it.toSong() }, c.id)
            }
            "set_volume" -> if (Stores.settings.value.ltSyncVolume) Player.setVolume((a.volume * 100).toInt(), fromRemote = true)
        }
    }

    // endregion

    // region host hooks (PlayerHooks)

    @Volatile private var suppress = false
    private val isHost get() = _state.value.role == Role.HOST && !suppress

    private fun action(name: String, build: PlaybackActionPayload.Builder.() -> Unit = {}) =
        send("playback_action", PlaybackActionPayload.newBuilder().setAction(name).apply(build).build())

    override fun onTrackChanged(song: Song, queue: List<Song>, index: Int) {
        if (!isHost) return
        action("change_track") {
            trackInfo = song.toTrack()
            trackId = song.id
            addAllQueue(queue.drop(index + 1).take(200).map { it.toTrack() })
            Player.state.value.queueTitle?.let { queueTitle = it }
        }
    }

    override fun onPlaying(positionMs: Long) {
        if (!isHost) return
        val id = Player.state.value.current?.id ?: return
        action("play") { trackId = id; position = positionMs; capturedAtServerTime = serverNow() }
    }

    override fun onPause(positionMs: Long) {
        if (!isHost) return
        val id = Player.state.value.current?.id ?: return
        action("pause") { trackId = id; position = positionMs }
    }

    override fun onSeek(positionMs: Long) {
        if (!isHost) return
        val id = Player.state.value.current?.id ?: return
        action("seek") { trackId = id; position = positionMs; capturedAtServerTime = serverNow() }
    }

    override fun onQueueChanged(queue: List<Song>, index: Int) {
        if (!isHost) return
        action("sync_queue") { addAllQueue(queue.drop(index + 1).take(200).map { it.toTrack() }) }
    }

    override fun onVolume(volume: Int) {
        if (!isHost || !Stores.settings.value.ltSyncVolume) return
        action("set_volume") { this.volume = volume / 100f }
    }

    override fun onBufferReady(songId: String) {
        if (_state.value.role == Role.GUEST) send("buffer_ready", BufferReadyPayload.newBuilder().setTrackId(songId).build())
    }

    // endregion

    private fun saveUsername(name: String) = Stores.settings.update { it.copy(ltUsername = name) }

    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")
    private fun log(msg: String) = _state.update { it.copy(logs = (it.logs + "${LocalTime.now().format(timeFmt)}  $msg").takeLast(300)) }
}

fun Song.toTrack(): TrackInfo = TrackInfo.newBuilder()
    .setId(id)
    .setTitle(title.take(200))
    .setArtist(artistText.take(200))
    .setAlbum(album.orEmpty())
    .setDuration(((duration ?: 180) * 1000L))
    .setThumbnail(thumbnail.orEmpty())
    .build()

fun TrackInfo.toSong() = Song(
    id = id,
    title = title,
    artists = artist.split(", ").filter { it.isNotBlank() }.ifEmpty { listOf(artist) },
    album = album.ifBlank { null },
    duration = (duration / 1000).toInt().takeIf { it > 0 },
    thumbnail = thumbnail.ifBlank { null },
)
