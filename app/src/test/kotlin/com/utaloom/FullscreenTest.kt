package com.utaloom

import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.window.WindowPlacement
import com.utaloom.data.PersistedQueue
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import com.utaloom.ui.Nav
import com.utaloom.ui.Screen
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.Window
import java.awt.event.KeyEvent
import java.awt.event.InputEvent
import java.awt.event.WindowEvent
import javax.accessibility.Accessible
import javax.accessibility.AccessibleContext
import javax.swing.SwingUtilities
import kotlin.concurrent.thread

/** Run under an isolated display and D-Bus session: dbus-run-session -- xvfb-run -a ./gradlew --no-daemon :app:test --tests com.utaloom.FullscreenTest. */
class FullscreenTest {
    @Test
    fun playerControlsAndKeyboardRestoreWindow() {
        assumeTrue("Needs a graphical display and VLC", !GraphicsEnvironment.isHeadless() && NativeLibs.discoverVlc() == null)
        val settings = Stores.settings.value
        val queue = Stores.queue.value
        Stores.settings.update { it.copy(minimizeToTray = false, visitorData = "fullscreen-test", lyricsDisabled = it.lyricsProviderOrder.toSet()) }
        Stores.queue.update { PersistedQueue(listOf(Song("screen00001", "Fullscreen test", listOf("Utaloom"))), title = "Fullscreen test") }
        Nav.root(Screen.Settings)
        val app = thread(name = "fullscreen-test-app", isDaemon = true) { runApp(exitProcessOnExit = false) }
        var window: ComposeWindow? = null
        try {
            await("Desktop window") { onEdt { Window.getWindows().filterIsInstance<ComposeWindow>().firstOrNull { it.isShowing }?.also { window = it } != null } }
            val w = checkNotNull(window)
            val original = onEdt { w.bounds }
            click(w, "Full screen (F11)")
            await("Player fullscreen") { onEdt { w.placement == WindowPlacement.Fullscreen && w.graphicsConfiguration.device.fullScreenWindow == w && find(w, "Exit full screen (Esc)") != null } }
            click(w, "Exit full screen (Esc)")
            await("Button restores floating window") { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original } }
            val robot = Robot()
            fun key(code: Int) {
                onEdt { w.toFront(); w.requestFocus() }
                val point = onEdt { w.locationOnScreen }
                robot.mouseMove(point.x + 100, point.y + 100)
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
                robot.waitForIdle()
                await("Keyboard focus") { onEdt { w.isFocused && w.focusOwner != null } }
                robot.keyPress(code)
                robot.keyRelease(code)
                robot.waitForIdle()
            }
            key(KeyEvent.VK_F11)
            await("F11 enters fullscreen") { onEdt { w.placement == WindowPlacement.Fullscreen } }
            key(KeyEvent.VK_F11)
            await("F11 exits fullscreen") { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original } }
            key(KeyEvent.VK_F11)
            await("Re-enter with F11") { onEdt { w.placement == WindowPlacement.Fullscreen } }
            key(KeyEvent.VK_ESCAPE)
            await("Escape restores floating window") { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original } }
            click(w, "Full screen (F11)")
            await("Re-enter fullscreen") { onEdt { w.placement == WindowPlacement.Fullscreen } }
            click(w, "Close player")
            await("Close player exits fullscreen") { onEdt { w.placement == WindowPlacement.Floating && w.bounds == original } }
            onEdt { w.setSize(900, 600) }
            await("Fullscreen button at minimum window size") {
                onEdt { find(w, "Full screen (F11)")?.accessibleComponent?.size?.let { it.width > 0 && it.height > 0 } == true }
            }
            val small = onEdt { w.bounds }
            click(w, "Full screen (F11)")
            await("Enter before stopping") { onEdt { w.placement == WindowPlacement.Fullscreen } }
            Player.stop()
            await("Stopping restores window") { onEdt { w.placement == WindowPlacement.Floating && w.bounds == small } }
            key(KeyEvent.VK_F11)
            check(onEdt { w.placement == WindowPlacement.Floating }) { "No current song must not enter fullscreen" }
        } catch (e: Exception) {
            println(onEdt { window?.let { "placement=${it.placement}, bounds=${it.bounds}, focused=${it.isFocused}, focusOwner=${it.focusOwner}" } })
            throw e
        } finally {
            window?.let { w -> onEdt { w.dispatchEvent(WindowEvent(w, WindowEvent.WINDOW_CLOSING)) } }
            app.join(5000)
            Stores.settings.update { settings }
            Stores.queue.update { queue }
            Stores.flushAll()
        }
    }

    private fun click(window: ComposeWindow, name: String) {
        await(name) { onEdt { find(window, name)?.accessibleAction?.doAccessibleAction(0) == true } }
    }

    private fun find(node: Accessible, name: String): AccessibleContext? {
        val ctx = node.accessibleContext ?: return null
        if (ctx.accessibleName == name && ctx.accessibleAction != null) return ctx
        return (0 until ctx.accessibleChildrenCount).firstNotNullOfOrNull { i -> ctx.getAccessibleChild(i)?.let { find(it, name) } }
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.nanoTime() + 15_000_000_000L
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(50)
        }
    }

    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        SwingUtilities.invokeAndWait { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }
}
