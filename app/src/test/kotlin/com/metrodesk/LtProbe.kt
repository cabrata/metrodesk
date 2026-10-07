package com.metrodesk

import com.google.protobuf.ByteString
import com.google.protobuf.MessageLite
import com.metrodesk.together.proto.Listentogether.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

/** Dev check against a live metroserver: `bash gradlew :app:ltProbe` (host+guest create/join/approve/sync). */
private class Peer(name: String, url: String) {
    val inbox = Channel<Pair<String, ByteArray>>(Channel.UNLIMITED)
    val ws: WebSocket = OkHttpClient().newWebSocket(Request.Builder().url(url).header("User-Agent", "Metrodesk/1.0 okhttp").build(), object : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, bytes: okio.ByteString) {
            val e = Envelope.parseFrom(bytes.toByteArray())
            inbox.trySend(e.type to e.payload.toByteArray())
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { println("$name failure: $t") }
    })
    fun send(type: String, p: MessageLite?) =
        ws.send(Envelope.newBuilder().setType(type).setPayload(ByteString.copyFrom(p?.toByteArray() ?: ByteArray(0))).build().toByteArray().toByteString())
    suspend fun await(type: String): ByteArray = withTimeout(10_000) {
        while (true) { val (t, p) = inbox.receive(); if (t == type) return@withTimeout p; if (t == "error") error("server error: ${ErrorPayload.parseFrom(p)}") }
        @Suppress("UNREACHABLE_CODE") ByteArray(0)
    }
}

fun main(args: Array<String>) = runBlocking {
    val url = args.firstOrNull() ?: "wss://metrolist.caliph.dev/ws"
    val caps = ClientCapabilities.newBuilder().setSupportsProtobuf(true).setSupportsCompression(false).build()
    val host = Peer("host", url).apply { send("client_capabilities", caps); await("server_capabilities") }
    host.send("create_room", CreateRoomPayload.newBuilder().setUsername("probe-host").build())
    val room = RoomCreatedPayload.parseFrom(host.await("room_created"))
    println("room=${room.roomCode}")

    val guest = Peer("guest", url).apply { send("client_capabilities", caps); await("server_capabilities") }
    guest.send("join_room", JoinRoomPayload.newBuilder().setRoomCode(room.roomCode).setUsername("probe-guest").build())
    val req = JoinRequestPayload.parseFrom(host.await("join_request"))
    host.send("approve_join", ApproveJoinPayload.newBuilder().setUserId(req.userId).build())
    JoinApprovedPayload.parseFrom(guest.await("join_approved"))
    println("guest approved")

    val track = TrackInfo.newBuilder().setId("dQw4w9WgXcQ").setTitle("Probe").setArtist("Metrodesk").setDuration(213_000).build()
    host.send("playback_action", PlaybackActionPayload.newBuilder().setAction("change_track").setTrackId(track.id).setTrackInfo(track).build())
    var sync: PlaybackActionPayload
    do sync = PlaybackActionPayload.parseFrom(guest.await("sync_playback")) while (sync.action != "change_track")
    check(sync.trackInfo.id == track.id) { "wrong track" }
    host.send("playback_action", PlaybackActionPayload.newBuilder().setAction("play").setTrackId(track.id).setPosition(0).build())
    do sync = PlaybackActionPayload.parseFrom(guest.await("sync_playback")) while (sync.action != "play")
    println("guest received change_track + play: OK")
    guest.send("leave_room", null); host.send("leave_room", null)
    host.ws.close(1000, null); guest.ws.close(1000, null)
    System.exit(0)
}
