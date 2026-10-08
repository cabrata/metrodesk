package com.utaloom

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import com.utaloom.together.Connection
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import com.utaloom.together.proto.Listentogether.*
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

/** Real acceptance check exercising the actual ListenTogether singleton + Player against live server. */
private class TestPeer(val name: String, url: String) {
    val http = OkHttpClient()
    val inbox = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
    val ws: WebSocket = http.newWebSocket(Request.Builder().url(url).header("User-Agent", "Utaloom/1.0 okhttp").build(), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val e = Envelope.parseFrom(bytes.toByteArray())
            inbox.trySend(e.type to e.payload.toByteArray())
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            println("$name socket failure: $t")
        }
    })

    fun send(type: String, p: MessageLite?) =
        ws.send(Envelope.newBuilder().setType(type).setPayload(ByteString.copyFrom(p?.toByteArray() ?: ByteArray(0))).build().toByteArray().toByteString())

    suspend fun await(type: String, timeoutMs: Long = 10_000): ByteArray = withTimeout(timeoutMs) {
        while (true) {
            val (t, p) = inbox.receive()
            if (t == type) return@withTimeout p
            if (t == "error") {
                val err = ErrorPayload.parseFrom(p)
                error("$name received server error: ${err.code} ${err.message}")
            }
        }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }

    fun close() {
        ws.close(1000, "close")
        http.dispatcher.executorService.shutdown()
        http.connectionPool.evictAll()
    }
}

fun main() = runBlocking {
    val url = Stores.settings.value.ltServerUrl
    println("Using Listen Together server: $url")
    val song = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"), duration = 213)

    // Ensure player is initialized without playing
    Player.init()
    Player.stop()

    // 1. Desktop app creates a room as Host
    ListenTogether.createRoom("AcceptanceHost")
    withTimeout(15_000) {
        while (ListenTogether.state.value.roomCode == null || ListenTogether.state.value.connection != Connection.CONNECTED) {
            delay(100)
        }
    }
    val roomCode = ListenTogether.state.value.roomCode!!
    println("Room created by desktop app: $roomCode, role=${ListenTogether.state.value.role}")
    check(ListenTogether.state.value.role == Role.HOST) { "Desktop app must be HOST" }
    check(!Player.remoteControlled) { "Host must not be remoteControlled" }

    val guest = TestPeer("GuestPeer", url)
    try {
        guest.send("client_capabilities", ClientCapabilities.newBuilder().setSupportsProtobuf(true).setSupportsCompression(false).build())
        guest.await("server_capabilities")

        // 2. Guest requests to join room
        guest.send("join_room", JoinRoomPayload.newBuilder().setRoomCode(roomCode).setUsername("GuestPeer").build())

        // 3. Desktop app receives JoinRequest and approves it
        withTimeout(10_000) {
            while (ListenTogether.state.value.joinRequests.isEmpty()) {
                delay(100)
            }
        }
        val joinReq = ListenTogether.state.value.joinRequests.first()
        println("Host received join request from: ${joinReq.username} (${joinReq.userId})")
        ListenTogether.approve(joinReq.userId)

        // Guest receives JoinApproved
        val approved = JoinApprovedPayload.parseFrom(guest.await("join_approved"))
        println("Guest successfully joined room: ${approved.roomCode}")
        check(approved.roomCode == roomCode)

        // 4. Host changes track via Player: Player.playQueue -> guest receives sync_playback
        Player.playQueue(listOf(song), title = "Acceptance Test")
        var sync: PlaybackActionPayload
        withTimeout(10_000) {
            do {
                sync = PlaybackActionPayload.parseFrom(guest.await("sync_playback"))
            } while (sync.action != "change_track")
        }
        check(sync.trackInfo.id == song.id) { "Guest received wrong trackId: ${sync.trackInfo.id}" }
        println("Guest successfully synced change_track for ${sync.trackInfo.title}")

        // 5. Guest sends track suggestion -> Host receives and approves
        val suggestedTrack = TrackInfo.newBuilder()
            .setId("9bZkp7q19f0")
            .setTitle("Gangnam Style")
            .setArtist("PSY")
            .setDuration(219_000)
            .build()
        guest.send("suggest_track", SuggestTrackPayload.newBuilder().setTrackInfo(suggestedTrack).build())

        withTimeout(10_000) {
            while (ListenTogether.state.value.suggestions.isEmpty()) {
                delay(100)
            }
        }
        val suggestion = ListenTogether.state.value.suggestions.first()
        println("Host received suggestion: ${suggestion.track.title} from ${suggestion.from}")
        ListenTogether.approveSuggestion(suggestion.id)

        // Guest receives suggestion_approved or queue_add
        val approvedSugg = SuggestionApprovedPayload.parseFrom(guest.await("suggestion_approved"))
        println("Guest received suggestion approval: ${approvedSugg.trackInfo.title}")

        // 6. Transfer host to guest -> Desktop app becomes GUEST, Player.remoteControlled becomes true
        ListenTogether.transferHost(approved.userId)
        withTimeout(10_000) {
            while (ListenTogether.state.value.role != Role.GUEST) {
                delay(100)
            }
        }
        println("Desktop app role transitioned to: ${ListenTogether.state.value.role}, remoteControlled=${Player.remoteControlled}")
        check(ListenTogether.state.value.role == Role.GUEST)
        check(Player.remoteControlled) { "Guest desktop app must have Player.remoteControlled = true" }

        // 7. Desktop app leaves room -> role resets, remoteControlled resets
        ListenTogether.leaveRoom()
        withTimeout(5000) {
            while (ListenTogether.state.value.roomCode != null) {
                delay(100)
            }
        }
        check(!Player.remoteControlled) { "Player.remoteControlled must reset to false after leaving room" }
        check(ListenTogether.state.value.role == Role.NONE) { "Role must be NONE after leaving room" }
        println("Desktop app left room cleanly. Listen Together full integration check: SUCCESS!")
    } finally {
        guest.close()
        ListenTogether.leaveRoom()
        Player.stop()
        Player.release()
        Stores.flushAll()
    }
}
