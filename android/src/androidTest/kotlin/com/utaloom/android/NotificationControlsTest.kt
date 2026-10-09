package com.utaloom.android

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.media3.common.C
import androidx.media3.common.Player as Media3Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.platform.app.InstrumentationRegistry
import com.utaloom.data.Library
import com.utaloom.data.Paths
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.playback.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/** Real service + actual System UI buttons. Root also inspects the saved notification screenshot. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NotificationControlsTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun await(step: String, condition: () -> Boolean) = runBlocking {
        try { withTimeout(20_000) { while (!condition()) delay(50) } }
        catch (e: kotlinx.coroutines.TimeoutCancellationException) { throw AssertionError("Timed out at $step: ${Player.state.value}", e) }
    }
    private fun find(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.contentDescription?.toString()?.contains(label, ignoreCase = true) == true && node.isClickable) return node
        for (index in 0 until node.childCount) find(node.getChild(index), label)?.let { return it }
        return null
    }
    private fun shadeButton(label: String) {
        await("shade $label") { find(instrumentation.uiAutomation.rootInActiveWindow, label)?.isEnabled == true }
        assertTrue("System UI $label click", find(instrumentation.uiAutomation.rootInActiveWindow, label)!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    @Test fun notificationShadeControlsAndSharedQueue() {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        await("audio initialization") { Player.available }
        val songs = (1..3).map { Song("notification-check-$it", "Notification track $it", listOf("Utaloom controls check"), duration = 90) }
        val file = Paths.downloads.resolve("notification-controls-check.wav")
        val samples = ByteArray(90 * 8000 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            .put("RIFF".toByteArray()).putInt(36 + samples.size).put("WAVEfmt ".toByteArray())
            .putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(samples.size).array()
        file.outputStream().use { it.write(header); it.write(samples) }
        songs.forEach { Library.setDownloaded(it.id, file.path, it) }
        var controller: MediaController? = null
        try {
            main { Player.remoteControlled = false; Player.playQueue(songs, startIndex = 1) }
            await("fixture playback") { Player.state.value.isPlaying && Player.state.value.positionMs > 250 }
            lateinit var future: com.google.common.util.concurrent.ListenableFuture<MediaController>
            main { future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync() }
            val controls = future.get(20, TimeUnit.SECONDS).also { controller = it }
            var ready = false
            await("shared queue advertised") { main {
                ready = controls.mediaItemCount == 3 && controls.currentMediaItemIndex == 1 && controls.hasNextMediaItem() && controls.hasPreviousMediaItem()
            }; ready }
            main {
                assertEquals(2, controls.nextMediaItemIndex)
                assertEquals(0, controls.previousMediaItemIndex)
                assertEquals(songs[1].title, controls.mediaMetadata.title.toString())
                assertEquals(songs[1].artistText, controls.mediaMetadata.artist.toString())
                assertTrue(controls.isCommandAvailable(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM))
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            await("native notification") { manager.activeNotifications.any { it.notification.smallIcon?.resId == R.drawable.ic_notification } }
            assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
            shadeButton("Pause")
            await("shade pause") { !Player.state.value.isPlaying }
            shadeButton("Play")
            await("shade play") { Player.state.value.isPlaying }
            shadeButton("Next")
            await("shade next") { Player.state.value.current?.id == songs[2].id && Player.state.value.isPlaying }
            main { controls.seekTo(0) }
            shadeButton("Previous")
            await("shade previous") { Player.state.value.current?.id == songs[1].id && Player.state.value.isPlaying }
            main { controls.seekTo(10_000) }
            await("session seek") { Player.state.value.positionMs >= 9900 }
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                File(context.getExternalFilesDir(null), "notification-controls.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            main { Player.remoteControlled = true }
            await("guest commands disabled") { main {
                ready = !controls.isCommandAvailable(Media3Player.COMMAND_PLAY_PAUSE) && !controls.isCommandAvailable(Media3Player.COMMAND_SEEK_TO_NEXT)
                    && !controls.isCommandAvailable(Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            }; ready }
            main { controls.pause(); controls.seekToNext() }
            assertTrue("Guest transport must not pause", Player.state.value.isPlaying)
            assertEquals(songs[1].id, Player.state.value.current?.id)
            main { Player.remoteControlled = false; Player.skipTo(2) }
            await("queue end") { main { ready = controls.currentMediaItemIndex == 2 && !controls.isCommandAvailable(Media3Player.COMMAND_SEEK_TO_NEXT) }; ready }
            main { assertEquals(C.INDEX_UNSET, controls.nextMediaItemIndex); Player.cycleRepeat() }
            await("repeat all next") { main { ready = controls.isCommandAvailable(Media3Player.COMMAND_SEEK_TO_NEXT) && controls.nextMediaItemIndex == 0 }; ready }
            main { controls.seekToNext() }
            await("repeat wraps shared queue") { Player.state.value.current?.id == songs[0].id }
        } finally {
            main { controller?.release(); Player.remoteControlled = false; Player.stop() }
            songs.forEach { Library.setDownloaded(it.id, null) }
            Stores.flushAll()
            file.delete()
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        }
    }
}
