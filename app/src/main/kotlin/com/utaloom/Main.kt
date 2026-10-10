package com.utaloom

import androidx.compose.runtime.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.utaloom.data.PlaylistImports
import com.utaloom.data.Stores
import com.utaloom.platform.Mpris
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import com.utaloom.together.ListenTogether
import com.utaloom.ui.Nav
import com.utaloom.ui.UtaloomApp
import com.utaloom.ui.applySession

fun main() {
    applySession()
    val vlcError = NativeLibs.discoverVlc()
    if (vlcError == null) Player.init() else System.err.println(vlcError)
    Runtime.getRuntime().addShutdownHook(Thread { PlaylistImports.cancel(); Mpris.stop(); Player.release(); Stores.flushAll() })
    application {
        var visible by remember { mutableStateOf(true) }
        val settings by Stores.settings.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val player by Player.state.collectAsState()
        val icon = painterResource("icon.png")
        val trayState = rememberTrayState()
        val importing by PlaylistImports.progress.collectAsState()
        // Background imports report through the system tray, since the user may have hidden the window.
        LaunchedEffect(Unit) {
            PlaylistImports.events.collect { e ->
                if (isTraySupported && (e.background || !visible)) trayState.sendNotification(
                    Notification(if (e is PlaylistImports.Done) "Playlist imported" else "Playlist import failed", PlaylistImports.summary(e),
                        if (e is PlaylistImports.Done) Notification.Type.Info else Notification.Type.Error))
            }
        }
        fun quit() { PlaylistImports.cancel(); ListenTogether.leaveRoom(); Mpris.stop(); Player.release(); Stores.flushAll(); exitApplication() }
        DisposableEffect(Unit) {
            Mpris.start(
                onRaise = { javax.swing.SwingUtilities.invokeLater { visible = true } },
                onQuit = { javax.swing.SwingUtilities.invokeLater { quit() } },
            )
            onDispose { Mpris.stop() }
        }

        if (isTraySupported) Tray(icon, state = trayState, tooltip = importing?.let { p -> if (p.total == 0) "Utaloom: importing playlist…" else "Utaloom: importing ${p.done} / ${p.total}" }
            ?: player.current?.let { "${it.title} • ${it.artistText}" } ?: "Utaloom", onAction = { visible = true }, menu = {
            Item(if (player.isPlaying) "Pause" else "Play", onClick = Player::playPause)
            Item("Next", onClick = { Player.next() })
            Item("Previous", onClick = Player::previous)
            if (importing != null) { Separator(); Item("Cancel playlist import", onClick = PlaylistImports::cancel) }
            Separator()
            Item("Show Utaloom", onClick = { visible = true })
            Item("Quit", onClick = ::quit)
        })

        Window(
            onCloseRequest = { if (isTraySupported && settings.minimizeToTray && (player.current != null || importing != null)) visible = false else quit() },
            visible = visible,
            state = windowState,
            title = player.current?.let { "${it.title} • ${it.artistText} - Utaloom" } ?: "Utaloom",
            icon = icon,
            onPreviewKeyEvent = ::shortcut,
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            UtaloomApp(vlcError)
        }
    }
}

/** Global media shortcuts. Ignored while typing in a text field (those consume the events first). */
private fun shortcut(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    when {
        e.key == Key.MediaPlayPause -> Player.playPause()
        e.key == Key.MediaNext -> Player.next()
        e.key == Key.MediaPrevious -> Player.previous()
        ctrl && e.key == Key.Spacebar -> Player.playPause()
        ctrl && e.key == Key.DirectionRight -> Player.next()
        ctrl && e.key == Key.DirectionLeft -> Player.previous()
        ctrl && e.key == Key.DirectionUp -> Player.setVolume(Player.state.value.volume + 5)
        ctrl && e.key == Key.DirectionDown -> Player.setVolume(Player.state.value.volume - 5)
        ctrl && e.key == Key.L -> Player.state.value.current?.let(com.utaloom.data.Library::toggleLike)
        e.isAltPressed && e.key == Key.DirectionLeft -> Nav.back()
        else -> return false
    }
    return true
}
