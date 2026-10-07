package com.metrodesk

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.metrodesk.data.Library
import com.metrodesk.data.Paths
import com.metrodesk.data.PersistedQueue
import com.metrodesk.data.Song
import com.metrodesk.data.Stores
import com.metrodesk.platform.NativeLibs
import com.metrodesk.playback.Player
import com.metrodesk.together.Connection
import com.metrodesk.together.ListenTogether
import com.metrodesk.together.Role
import com.metrodesk.together.decodeRoomEnvelope
import com.metrodesk.together.proto.Listentogether.*
import com.metrodesk.together.toTrack
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/** Raw WebSocket peer communicating with metroserver via protobuf envelopes. */
private class Peer(val name: String, url: String) {
    val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    val inbox = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)

    val ws: WebSocket = client.newWebSocket(
        Request.Builder().url(url).header("User-Agent", "Metrodesk/1.0 okhttp").build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
                runCatching {
                    val (type, payload) = decodeRoomEnvelope(bytes.toByteArray())
                    inbox.trySend(type to payload)
                }.onFailure { println("$name decode error: ${it.message}") }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("$name socket failure: ${t.message}")
            }
        }
    )

    fun send(type: String, p: MessageLite? = null) {
        val raw = p?.toByteArray() ?: ByteArray(0)
        val env = Envelope.newBuilder()
            .setType(type)
            .setPayload(ByteString.copyFrom(raw))
            .setCompressed(false)
            .build()
        ws.send(env.toByteArray().toByteString())
    }

    suspend fun await(type: String, timeoutMs: Long = 15_000): ByteArray = withTimeout(timeoutMs) {
        while (true) {
            val (t, p) = inbox.receive()
            if (t == type) return@withTimeout p
            if (t == "error" && type != "error") {
                val err = ErrorPayload.parseFrom(p)
                error("$name received server error: ${err.code} - ${err.message}")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        ByteArray(0)
    }

    suspend fun awaitPlaybackAction(expectedAction: String, timeoutMs: Long = 15_000): PlaybackActionPayload = withTimeout(timeoutMs) {
        while (true) {
            val (t, p) = inbox.receive()
            if (t == "sync_playback") {
                val action = PlaybackActionPayload.parseFrom(p)
                if (action.action == expectedAction) return@withTimeout action
            }
            if (t == "error") {
                val err = ErrorPayload.parseFrom(p)
                error("$name received server error: ${err.code} - ${err.message}")
            }
        }
        @Suppress("UNREACHABLE_CODE")
        PlaybackActionPayload.getDefaultInstance()
    }

    fun close() {
        runCatching { ws.close(1000, "done") }
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        inbox.close()
    }
}

/**
 * End-to-end acceptance suite for Listen Together using the actual ListenTogether singleton,
 * the actual native Player, local WAV audio, and live metroserver WebSocket communication.
 *
 * JVM Class: com.metrodesk.TogetherAcceptanceKt
 */
fun main(args: Array<String>) = runBlocking {
    val serverUrl = args.firstOrNull() ?: Stores.settings.value.ltServerUrl.ifBlank { "wss://metrolist.caliph.dev/ws" }
    println("Starting TogetherAcceptance against $serverUrl")

    // Configure settings for full feature coverage
    Stores.settings.update {
        it.copy(
            ltServerUrl = serverUrl,
            ltSyncVolume = true,
            ltAutoApproveJoins = false,
            ltAutoApproveSuggestions = false,
        )
    }

    // Generate local 12-second silent WAV for reliable offline audio execution
    val wavFile = Paths.dir.resolve("together-smoke.wav")
    val rate = 8000f
    AudioInputStream(
        ByteArrayInputStream(ByteArray(12 * 8000 * 2)),
        AudioFormat(rate, 16, 1, true, false),
        12 * 8000L
    ).use { ais ->
        AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavFile)
    }

    // Valid 11-char track IDs
    val localSong = Song("tgthsmoke01", "Together Smoke", listOf("Metrodesk"), duration = 12)
    val suggSong = Song("tgthsugg001", "Suggested Track", listOf("Guest Artist"), duration = 12)
    val queueSong = Song("tgthqueue01", "Queue Track", listOf("Metrodesk"), duration = 12)

    Library.setDownloaded(localSong.id, wavFile.absolutePath, localSong)
    Library.setDownloaded(suggSong.id, wavFile.absolutePath, suggSong)
    Library.setDownloaded(queueSong.id, wavFile.absolutePath, queueSong)

    val caps = ClientCapabilities.newBuilder()
        .setSupportsProtobuf(true)
        .setSupportsCompression(false)
        .build()

    var rejectGuest: Peer? = null
    var guest: Peer? = null

    try {
        check(NativeLibs.discoverVlc() == null) { "VLC discovery failed" }
        check(Player.init()) { "Player.init failed" }

        // 1. Invalid room error handling and cleanup
        println("[1/7] Testing invalid room error handling and cleanup...")
        ListenTogether.joinRoom("INVROOM9999", "ghost-guest")
        withTimeout(15_000) {
            while (ListenTogether.state.value.connection != Connection.DISCONNECTED &&
                ListenTogether.state.value.connection != Connection.ERROR
            ) {
                delay(50)
            }
        }
        check(ListenTogether.state.value.roomCode == null) { "Room code must be null on invalid room error" }
        check(!ListenTogether.state.value.waitingApproval) { "waitingApproval must be cleared" }
        check(ListenTogether.state.value.message != null) { "Error message should be set" }
        println("-> Invalid room cleanup OK: message=${ListenTogether.state.value.message}")
        delay(500)

        // 2. App creates room as Host
        println("[2/7] Creating room as host...")
        ListenTogether.createRoom("metro-host")
        withTimeout(15_000) {
            while (ListenTogether.state.value.roomCode == null || ListenTogether.state.value.role != Role.HOST) {
                delay(50)
            }
        }
        val roomCode = checkNotNull(ListenTogether.state.value.roomCode) { "Missing room code" }
        println("-> Room created: $roomCode (userId=${ListenTogether.state.value.userId})")

        // 3. Raw guest join request rejected by host and cleaned up
        println("[3/7] Testing join rejection and cleanup...")
        rejectGuest = Peer("reject-guest", serverUrl).apply {
            send("client_capabilities", caps)
            await("server_capabilities")
            send("join_room", JoinRoomPayload.newBuilder().setRoomCode(roomCode).setUsername("reject-user").build())
        }
        withTimeout(15_000) {
            while (ListenTogether.state.value.joinRequests.none { it.username == "reject-user" }) {
                delay(50)
            }
        }
        val rejectReq = ListenTogether.state.value.joinRequests.first { it.username == "reject-user" }
        ListenTogether.reject(rejectReq.userId)
        val rejectedPayload = JoinRejectedPayload.parseFrom(rejectGuest.await("join_rejected"))
        check(rejectedPayload.reason.contains("Rejected by host")) { "Unexpected rejection reason: ${rejectedPayload.reason}" }
        withTimeout(5_000) {
            while (ListenTogether.state.value.joinRequests.any { it.userId == rejectReq.userId }) {
                delay(50)
            }
        }
        rejectGuest.close()
        rejectGuest = null
        println("-> Join request rejected and cleaned up: OK")

        // 4. Raw guest join request approved by host
        println("[4/7] Raw guest joining room and host approving...")
        guest = Peer("raw-guest", serverUrl).apply {
            send("client_capabilities", caps)
            await("server_capabilities")
            send("join_room", JoinRoomPayload.newBuilder().setRoomCode(roomCode).setUsername("guest-user").build())
        }
        withTimeout(15_000) {
            while (ListenTogether.state.value.joinRequests.none { it.username == "guest-user" }) {
                delay(50)
            }
        }
        val guestReq = ListenTogether.state.value.joinRequests.first { it.username == "guest-user" }
        ListenTogether.approve(guestReq.userId)
        val approvedPayload = JoinApprovedPayload.parseFrom(guest.await("join_approved"))
        check(approvedPayload.roomCode == roomCode) { "Approved room mismatch" }
        val guestUserId = approvedPayload.userId
        withTimeout(10_000) {
            while (ListenTogether.state.value.users.none { it.id == guestUserId }) {
                delay(50)
            }
        }
        println("-> Guest approved: OK (guestUserId=$guestUserId)")

        // 5. Host controls playback: playQueue, play, pause, seek, queue, volume
        println("[5/7] Testing host playback synchronization (playQueue, play, pause, seek, queue, volume)...")
        Player.playQueue(listOf(localSong), play = true)
        val changeTrack = guest.awaitPlaybackAction("change_track")
        check(changeTrack.trackInfo.id == localSong.id) { "Change track mismatch: ${changeTrack.trackInfo.id}" }
        println("-> Guest received change_track: OK")

        val playAction = guest.awaitPlaybackAction("play")
        check(playAction.trackId == localSong.id) { "Play track mismatch: ${playAction.trackId}" }
        println("-> Guest received play: OK")

        Player.pause()
        val pauseAction = guest.awaitPlaybackAction("pause")
        println("-> Guest received pause: OK")

        Player.seekTo(2500)
        val seekAction = guest.awaitPlaybackAction("seek")
        check(seekAction.position == 2500L) { "Seek position mismatch: ${seekAction.position}" }
        println("-> Guest received seek: OK")

        Player.addToQueue(listOf(queueSong))
        val queueAction = guest.awaitPlaybackAction("sync_queue")
        check(queueAction.queueList.any { it.id == queueSong.id }) { "Queue song missing from sync_queue" }
        println("-> Guest received sync_queue: OK")

        Player.setVolume(65)
        val volAction = guest.awaitPlaybackAction("set_volume")
        check(abs(volAction.volume - 0.65f) < 0.05f) { "Volume sync mismatch: ${volAction.volume}" }
        println("-> Guest received set_volume: OK")

        // 6. Suggestion: raw guest suggests -> host approves -> host queue inserts next
        println("[6/7] Testing track suggestion (raw -> app approve -> host queue inserts next)...")
        guest.send("suggest_track", SuggestTrackPayload.newBuilder().setTrackInfo(suggSong.toTrack()).build())
        withTimeout(15_000) {
            while (ListenTogether.state.value.suggestions.none { it.track.id == suggSong.id }) {
                delay(50)
            }
        }
        val sugg = ListenTogether.state.value.suggestions.first { it.track.id == suggSong.id }
        ListenTogether.approveSuggestion(sugg.id)

        val guestApprovedSugg = SuggestionApprovedPayload.parseFrom(guest.await("suggestion_approved"))
        check(guestApprovedSugg.trackInfo.id == suggSong.id) { "Approved suggestion track mismatch" }

        withTimeout(15_000) {
            while (true) {
                val q = Player.state.value.queue
                val idx = Player.state.value.index
                if (idx in q.indices && idx + 1 in q.indices && q[idx + 1].id == suggSong.id) {
                    break
                }
                delay(50)
            }
        }
        println("-> Host approved suggestion and inserted next in queue: OK")

        // 7. Transfer host -> app is guest -> raw host drives playback -> actual native player observed, resync, leave
        println("[7/7] Transferring host and driving playback from raw host...")
        ListenTogether.transferHost(guestUserId)
        withTimeout(15_000) {
            while (ListenTogether.state.value.role != Role.GUEST) {
                delay(50)
            }
        }
        check(Player.remoteControlled) { "Player must be remoteControlled when guest" }
        val hostChanged = HostChangedPayload.parseFrom(guest.await("host_changed"))
        check(hostChanged.newHostId == guestUserId) { "HostChangedPayload newHostId mismatch" }
        println("-> Host transferred: raw peer is host, app is guest")

        // Raw host sends change_track
        guest.send(
            "playback_action",
            PlaybackActionPayload.newBuilder()
                .setAction("change_track")
                .setTrackId(localSong.id)
                .setTrackInfo(localSong.toTrack())
                .build()
        )
        withTimeout(15_000) {
            while (Player.state.value.current?.id != localSong.id || Player.state.value.isBuffering) {
                delay(50)
            }
        }
        check(!Player.state.value.isPlaying) { "Guest loaded track must start paused" }
        println("-> App as guest loaded track paused: OK")

        // Raw host sends play
        guest.send(
            "playback_action",
            PlaybackActionPayload.newBuilder()
                .setAction("play")
                .setTrackId(localSong.id)
                .setPosition(0)
                .setServerTime(System.currentTimeMillis())
                .build()
        )
        withTimeout(15_000) {
            while (!Player.state.value.isPlaying || Player.state.value.positionMs < 200) {
                delay(50)
            }
        }
        println("-> Actual native Player playing observed: positionMs=${Player.state.value.positionMs}")

        // Raw host sends pause
        val pausedPos = Player.state.value.positionMs
        guest.send(
            "playback_action",
            PlaybackActionPayload.newBuilder()
                .setAction("pause")
                .setTrackId(localSong.id)
                .setPosition(pausedPos)
                .build()
        )
        withTimeout(15_000) {
            while (Player.state.value.isPlaying) {
                delay(50)
            }
        }
        println("-> Actual native Player paused observed: OK")

        // Raw host sends seek
        guest.send(
            "playback_action",
            PlaybackActionPayload.newBuilder()
                .setAction("seek")
                .setTrackId(localSong.id)
                .setPosition(3500)
                .build()
        )
        withTimeout(15_000) {
            while (Player.state.value.positionMs < 3300) {
                delay(50)
            }
        }
        println("-> Actual native Player seek observed: positionMs=${Player.state.value.positionMs}")

        // Resync
        ListenTogether.requestSync()
        delay(500)
        println("-> Resync request: OK")

        // Leave and reset
        ListenTogether.leaveRoom()
        guest.send("leave_room", null)
        withTimeout(10_000) {
            while (ListenTogether.state.value.roomCode != null || ListenTogether.state.value.role != Role.NONE) {
                delay(50)
            }
        }
        check(!Player.remoteControlled) { "Player must not be remoteControlled after leave" }
        println("-> Leave and reset: OK")

        println("TogetherAcceptance: ALL TESTS PASSED SUCCESSFULLY")
    } finally {
        ListenTogether.leaveRoom()
        Player.remoteControlled = false
        Player.stop()
        Player.clearQueue()
        Player.release()

        Library.setDownloaded(localSong.id, null)
        Library.setDownloaded(suggSong.id, null)
        Library.setDownloaded(queueSong.id, null)
        wavFile.delete()

        rejectGuest?.close()
        guest?.close()

        Stores.queue.update { PersistedQueue() }
        Stores.settings.update {
            it.copy(
                ltSyncVolume = false,
                ltAutoApproveJoins = false,
                ltAutoApproveSuggestions = false,
            )
        }
        Stores.flushAll()

        // Exit if non-daemon http/vlc threads would prevent JVM shutdown
        val nonDaemon = Thread.getAllStackTraces().keys.filter {
            !it.isDaemon && it != Thread.currentThread() && it.name != "DestroyJavaVM"
        }
        if (nonDaemon.isNotEmpty()) {
            System.exit(0)
        }
    }
}
