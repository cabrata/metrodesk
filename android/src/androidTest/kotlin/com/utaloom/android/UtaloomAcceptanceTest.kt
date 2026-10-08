package com.utaloom.android

import android.content.Intent
import android.graphics.Bitmap
import android.app.NotificationManager
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.utaloom.data.Library
import com.utaloom.data.Paths
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerHooks
import junit.framework.TestCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Real APK + Media3 + service check. Run :android:connectedDebugAndroidTest, add -Pandroid.testInstrumentationRunnerArguments.network=true for live YouTube. */
class UtaloomAcceptanceTest : TestCase() {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(timeout: Long = 15_000, condition: () -> Boolean) = runBlocking {
        withTimeout(timeout) { while (!condition()) delay(50) }
    }

    override fun setUp() {
        super.setUp()
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.waitForIdleSync()
        await { Player.available }
    }

    fun testNativeBackgroundPlayerAndMediaControls() {
        val ready = AtomicBoolean()
        val first = Song("androidsmoke1", "Android audio check", listOf("Utaloom"), duration = 40)
        val second = first.copy(id = "androidsmoke2", title = "Next track check")
        val file = Paths.downloads.resolve("android-smoke.wav")
        val samples = ByteArray(40 * 8000 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size).array()
        file.outputStream().use { it.write(header); it.write(samples) }
        Library.setDownloaded(first.id, file.path, first)
        Library.setDownloaded(second.id, file.path, second)
        val hooks = Player.hooks
        var controller: MediaController? = null
        try {
            Player.remoteControlled = true
            Player.hooks = object : PlayerHooks { override fun onBufferReady(songId: String) { ready.set(true) } }
            main { Player.playQueue(listOf(first, second), fromRemote = true, play = false) }
            await { ready.get() && !Player.state.value.isBuffering }
            assertFalse("Remote prebuffer must be silent", Player.state.value.isPlaying)
            main { Player.playQueue(listOf(second)) }
            assertEquals(first.id, Player.state.value.current?.id)
            main { Player.play(fromRemote = true) }
            await { Player.state.value.positionMs > 500 }
            main { Player.seekTo(5000, fromRemote = true) }
            await { Player.state.value.positionMs >= 4900 }
            main { Player.pause(fromRemote = true) }
            await { !Player.state.value.isPlaying }
            Player.remoteControlled = false

            val token = SessionToken(context, android.content.ComponentName(context, PlaybackService::class.java))
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, token).buildAsync() }
            controller = future.get(15, TimeUnit.SECONDS)
            val controls = controller
            main { controls.play() }
            await { Player.state.value.isPlaying }
            await { context.getSystemService(NotificationManager::class.java).activeNotifications.isNotEmpty() }
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            val position = Player.state.value.positionMs
            await { Player.state.value.positionMs > position + 1000 }
            assertTrue("Playback survives app going to background", Player.state.value.isPlaying)
            main { controls.pause() }
            await { !Player.state.value.isPlaying }
            main { controls.seekToNext() }
            await { Player.state.value.current?.id == second.id && Player.state.value.isPlaying }
            main { controls.stop() }
            await { Player.state.value.current == null && !Player.state.value.isPlaying }
            instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                context.filesDir.resolve("utaloom-android.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
        } finally {
            main { controller?.release(); Player.remoteControlled = false; Player.stop() }
            Player.hooks = hooks
            Library.setDownloaded(first.id, null)
            Library.setDownloaded(second.id, null)
            Stores.flushAll()
            file.delete()
        }
    }

    fun testLiveYouTubeSearchAndPlayback() {
        if (InstrumentationRegistry.getArguments().getString("network") != "true") return
        val song = runBlocking {
            withTimeout(60_000) { YouTube.search("Rick Astley Never Gonna Give You Up", YouTube.SearchFilter.FILTER_SONG).getOrThrow() }
                .items.filterIsInstance<SongItem>().first().toSong()
        }
        assertTrue("Real YouTube search returns songs", song.id.isNotBlank())
        try {
            main { Player.playQueue(listOf(song)) }
            await(120_000) {
                Player.state.value.error?.let { error(it) }
                Player.state.value.positionMs > 1000 && Player.state.value.isPlaying
            }
            assertTrue("Live extracted YouTube stream plays on Android", Player.state.value.isPlaying)
        } finally { main { Player.stop() } }
    }
}
