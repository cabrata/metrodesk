package com.utaloom.android

import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.utaloom.data.Library
import com.utaloom.data.Paths
import com.utaloom.data.Song
import com.utaloom.playback.Player
import com.utaloom.ui.Nav
import com.utaloom.ui.Screen
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Run connectedDebugAndroidTest after setting emulator wm size/density and font_scale. Exercises the real app UI. */
class PortraitUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun nodes(node: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = if (node == null) emptyList() else
        listOf(node) + (0 until node.childCount).flatMap { nodes(node.getChild(it)) }

    private fun find(label: String, timeoutMs: Long = 10_000): AccessibilityNodeInfo {
        val end = android.os.SystemClock.uptimeMillis() + timeoutMs
        while (android.os.SystemClock.uptimeMillis() < end) {
            nodes(automation.rootInActiveWindow).firstOrNull { it.text?.toString() == label || it.contentDescription?.toString() == label }?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("UI element '$label' absent at ${context.resources.displayMetrics.widthPixels}x${context.resources.displayMetrics.heightPixels}")
    }

    private fun visible(label: String): AccessibilityNodeInfo = find(label).also { node ->
        val bounds = Rect().also(node::getBoundsInScreen)
        val display = context.resources.displayMetrics
        assertTrue("'$label' has visible size: $bounds", bounds.width() > 0 && bounds.height() > 0)
        assertTrue("'$label' fits portrait viewport: $bounds", bounds.left >= 0 && bounds.top >= 0 && bounds.right <= display.widthPixels && bounds.bottom <= display.heightPixels)
    }

    private fun tap(label: String) {
        val node = visible(label)
        var clickable: AccessibilityNodeInfo? = node
        while (clickable != null && !clickable.isClickable) clickable = clickable.parent
        if (clickable != null) assertTrue(clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        else {
            val bounds = Rect().also(node::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        }
        Thread.sleep(250)
    }

    @Test fun playerAndAllPortraitScreensRemainReachable() {
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val song = Song("portraitcheck1", "A long song title that must fit in a narrow portrait player", listOf("Utaloom Artist"), duration = 40)
        val second = song.copy(id = "portraitcheck2", title = "Next portrait track")
        val file = Paths.downloads.resolve("portrait-check.wav")
        val bytes = ByteArray(40 * 8000 * 2)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).put("RIFF".toByteArray()).putInt(36 + bytes.size)
            .put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(8000).putInt(16000)
            .putShort(2).putShort(16).put("data".toByteArray()).putInt(bytes.size).array()
        file.outputStream().use { it.write(header); it.write(bytes) }
        Library.setDownloaded(song.id, file.path, song)
        Library.setDownloaded(second.id, file.path, second)
        try {
            main { Nav.root(Screen.Home); Player.playQueue(listOf(song, second), play = false) }
            tap("Open player")
            listOf("Now playing artwork", "Playback position", "Play", "Previous", "Next", "Shuffle", "Repeat: off", "Remove download", "Sleep timer").forEach { visible(it) }
            tap("Play")
            visible("Pause")
            tap("Up next")
            visible("Pause")
            visible("Next portrait track")
            tap("Next")
            val end = android.os.SystemClock.uptimeMillis() + 10_000
            while (Player.state.value.current?.id != second.id && android.os.SystemClock.uptimeMillis() < end) Thread.sleep(100)
            assertEquals(second.id, Player.state.value.current?.id)
            tap("Playing")
            visible("Now playing artwork")
            shell("input keyevent KEYCODE_BACK")
            visible("Home")
            listOf("Explore", "Library", "Together", "Settings").forEach { label ->
                tap(label)
                visible(label)
            }
            tap("Library")
            visible("New playlist")
            tap("New playlist")
            visible("Playlist name")
            tap("Cancel")
            tap("Together")
            visible("Create room")
            tap("Your name")
            Thread.sleep(300)
            assertFalse("Keyboard gives content space instead of retaining bottom navigation", nodes(automation.rootInActiveWindow).any { it.contentDescription?.toString() == "Explore" && it.isVisibleToUser })
            shell("input keyevent KEYCODE_BACK")
        } finally {
            main { Player.stop(); Nav.root(Screen.Home) }
            Library.setDownloaded(song.id, null)
            Library.setDownloaded(second.id, null)
            file.delete()
        }
    }
}
