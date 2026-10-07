package com.metrodesk

import com.metrodesk.data.Library
import com.metrodesk.data.Paths
import com.metrodesk.data.Song
import com.metrodesk.data.Stores
import com.metrodesk.platform.NativeLibs
import com.metrodesk.playback.Player
import com.metrodesk.playback.PlayerHooks
import com.metrodesk.playback.Downloads
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import javax.sound.sampled.AudioFileFormat
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import java.util.concurrent.atomic.AtomicBoolean

/** Native acceptance check. Uses a local WAV so no network or YouTube availability masks player bugs. */
fun main(args: Array<String>) = runBlocking {
    val file = Paths.dir.resolve("native-smoke.wav")
    val rate = 8000f
    AudioInputStream(ByteArrayInputStream(ByteArray(12 * 8000 * 2)), AudioFormat(rate, 16, 1, true, false), 12 * 8000L).use {
        AudioSystem.write(it, AudioFileFormat.Type.WAVE, file)
    }
    val song = Song("smoke000001", "Native smoke", listOf("Metrodesk"), duration = 12)
    Library.setDownloaded(song.id, file.absolutePath, song)
    val ready = AtomicBoolean()
    try {
        check(NativeLibs.discoverVlc() == null) { "VLC missing" }
        check(Player.init()) { "Player init failed" }
        Player.remoteControlled = true
        Player.hooks = object : PlayerHooks { override fun onBufferReady(songId: String) { if (songId == song.id) ready.set(true) } }
        println("Loading paused local WAV")
        Player.playQueue(listOf(song), fromRemote = true, play = false)
        try { withTimeout(10_000) { while (!ready.get()) delay(50) } }
        catch (e: Exception) { println("Readiness state: ${Player.state.value}"); throw e }
        println("Paused readiness: OK")
        check(!Player.state.value.isPlaying) { "Paused remote load should stay silent" }
        Player.playQueue(listOf(song.copy(id = "bad00000001")))
        check(Player.state.value.current?.id == song.id) { "Guest must not override the host" }
        Player.play(fromRemote = true)
        try { withTimeout(10_000) { while (Player.state.value.positionMs < 500) delay(50) } }
        catch (e: Exception) { println("Playback state: ${Player.state.value}"); throw e }
        println("Native position advances: OK")
        Player.seekTo(5000, fromRemote = true)
        withTimeout(5000) { while (Player.state.value.positionMs < 4900) delay(50) }
        Player.pause(fromRemote = true)
        withTimeout(5000) { while (Player.state.value.isPlaying) delay(50) }
        println("native paused buffer readiness, playback, seek, pause and guest restrictions: OK")
        if ("network" in args) {
            Player.remoteControlled = false
            Player.stop()
            val online = Song("dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"), duration = 213)
            println("Loading real YouTube stream through the app player")
            Player.playQueue(listOf(online))
            withTimeout(120_000) {
                while (Player.state.value.positionMs < 1000) {
                    Player.state.value.error?.let { error(it) }
                    delay(100)
                }
            }
            println("Live YouTube audio advances in VLC: OK")
            Player.pause()
            println("Downloading real audio for offline playback")
            Downloads.download(listOf(online))
            withTimeout(120_000) {
                while (!Downloads.isDownloaded(online.id)) {
                    Downloads.errors.value[online.id]?.let { error(it) }
                    delay(100)
                }
            }
            Player.stop()
            Player.playQueue(listOf(online))
            withTimeout(10_000) { while (Player.state.value.positionMs < 500) delay(100) }
            check(Stores.library.value.downloadedSongs[online.id]?.title == online.title)
            println("Offline downloaded audio playback and metadata: OK")
            Player.stop()
            Downloads.delete(online.id)
        }
    } finally {
        Player.remoteControlled = false
        Player.stop()
        Player.release()
        Library.setDownloaded(song.id, null)
        Stores.flushAll()
        file.delete()
    }
}
