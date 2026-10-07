package com.metrodesk.platform

import com.metrodesk.playback.Player
import com.metrodesk.playback.PlayerState
import com.metrodesk.playback.RepeatMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.TypeRef
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.DBusProperties
import org.freedesktop.dbus.annotations.DBusProperty
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ
import org.freedesktop.dbus.annotations.DBusProperty.Access.READ_WRITE
import org.freedesktop.dbus.annotations.PropertiesEmitsChangedSignal.EmitChangeSignal.FALSE
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.errors.InvalidMethodArgument
import org.freedesktop.dbus.errors.NotSupported
import org.freedesktop.dbus.errors.PropertyReadOnly
import org.freedesktop.dbus.errors.UnknownInterface
import org.freedesktop.dbus.errors.UnknownProperty
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.awt.EventQueue

@DBusInterfaceName("org.mpris.MediaPlayer2")
@DBusProperties(
    DBusProperty(name = "CanQuit", type = Boolean::class, access = READ),
    DBusProperty(name = "CanRaise", type = Boolean::class, access = READ),
    DBusProperty(name = "HasTrackList", type = Boolean::class, access = READ),
    DBusProperty(name = "Identity", type = String::class, access = READ),
    DBusProperty(name = "DesktopEntry", type = String::class, access = READ),
    DBusProperty(name = "SupportedUriSchemes", type = Array<String>::class, access = READ),
    DBusProperty(name = "SupportedMimeTypes", type = Array<String>::class, access = READ),
)
interface MediaPlayer2 : DBusInterface {
    fun Raise()
    fun Quit()
}

interface MprisMetadata : TypeRef<Map<String, Variant<*>>>

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
@DBusProperties(
    DBusProperty(name = "PlaybackStatus", type = String::class, access = READ),
    DBusProperty(name = "LoopStatus", type = String::class, access = READ_WRITE),
    DBusProperty(name = "Rate", type = Double::class, access = READ_WRITE),
    DBusProperty(name = "Shuffle", type = Boolean::class, access = READ_WRITE),
    DBusProperty(name = "Metadata", type = MprisMetadata::class, access = READ),
    DBusProperty(name = "Volume", type = Double::class, access = READ_WRITE),
    DBusProperty(name = "Position", type = Long::class, access = READ, emitChangeSignal = FALSE),
    DBusProperty(name = "MinimumRate", type = Double::class, access = READ),
    DBusProperty(name = "MaximumRate", type = Double::class, access = READ),
    DBusProperty(name = "CanGoNext", type = Boolean::class, access = READ),
    DBusProperty(name = "CanGoPrevious", type = Boolean::class, access = READ),
    DBusProperty(name = "CanPlay", type = Boolean::class, access = READ),
    DBusProperty(name = "CanPause", type = Boolean::class, access = READ),
    DBusProperty(name = "CanSeek", type = Boolean::class, access = READ),
    DBusProperty(name = "CanControl", type = Boolean::class, access = READ),
)
interface MediaPlayer2Player : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(offset: Long)
    fun SetPosition(trackId: DBusPath, position: Long)
    fun OpenUri(uri: String)
}

/**
 * Linux-only MPRIS2 bridge so media keys / desktop widgets can control playback.
 * Every failure (no session bus, Windows, name taken) is swallowed: MPRIS is optional.
 * ponytail: no Seeked signal (clients poll Position); add a DBusSignal subclass if widgets show stale position after seeks.
 */
object Mpris : MediaPlayer2, MediaPlayer2Player, Properties {
    private const val PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT = "org.mpris.MediaPlayer2"
    private const val PLAYER = "org.mpris.MediaPlayer2.Player"
    private const val BUS_NAME = "org.mpris.MediaPlayer2.metrodesk"

    private var conn: DBusConnection? = null
    private var scope: CoroutineScope? = null
    @Volatile private var onRaise: (() -> Unit)? = null
    @Volatile private var onQuit: (() -> Unit)? = null

    /** Starts the service. Returns false (never throws) when MPRIS is unavailable. */
    @Synchronized
    fun start(onRaise: (() -> Unit)? = null, onQuit: (() -> Unit)? = null): Boolean {
        if (conn != null) return true
        if (!System.getProperty("os.name").lowercase().contains("linux")) return false
        this.onRaise = onRaise
        this.onQuit = onQuit
        return runCatching {
            val c = DBusConnectionBuilder.forSessionBus().withShared(false).build()
            try {
                c.exportObject(PATH, this)
                c.requestBusName(BUS_NAME)
            } catch (t: Throwable) {
                runCatching { c.close() }; throw t
            }
            conn = c
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { s ->
                Player.state.map { playerProps(it) }.distinctUntilChanged()
                    .onEach { props -> runCatching { c.sendMessage(Properties.PropertiesChanged(PATH, PLAYER, props, emptyList())) } }
                    .launchIn(s)
            }
            true
        }.getOrElse {
            System.err.println("MPRIS unavailable: ${it.message}")
            false
        }
    }

    @Synchronized
    fun stop() {
        scope?.cancel(); scope = null
        conn?.let { c -> runCatching { c.releaseBusName(BUS_NAME) }; runCatching { c.close() } }
        conn = null
    }

    override fun getObjectPath() = PATH

    // Serialize user actions on the same Swing UI thread as Compose desktop.
    private fun command(action: () -> Unit) = EventQueue.invokeLater {
        runCatching(action).onFailure { System.err.println("MPRIS command failed: ${it.message}") }
    }
    private fun transport(action: () -> Unit) = command { if (!Player.remoteControlled && Player.state.value.current != null) action() }

    override fun Raise() = command { onRaise?.invoke() }
    override fun Quit() = command { onQuit?.invoke() }
    override fun Next() = transport { Player.next() }
    override fun Previous() = transport { Player.previous() }
    override fun Pause() = transport { Player.pause() }
    override fun PlayPause() = transport { Player.playPause() }
    override fun Stop() = transport { Player.pause(); Player.seekTo(0) }
    override fun Play() = transport { Player.play() }
    override fun Seek(offset: Long) = transport {
        val s = Player.state.value
        val target = (s.positionMs + offset / 1000).coerceAtLeast(0)
        if (s.durationMs > 0 && target > s.durationMs) Player.next() else Player.seekTo(target)
    }
    override fun SetPosition(trackId: DBusPath, position: Long) = transport {
        val s = Player.state.value
        if (trackId.path == trackPath(s) && position >= 0 && (s.durationMs <= 0 || position / 1000 <= s.durationMs)) Player.seekTo(position / 1000)
    }
    override fun OpenUri(uri: String): Unit = throw NotSupported("Use Metrodesk to open songs")

    // endregion

    // region properties

    private fun trackPath(s: PlayerState) =
        s.current?.let { "/com/metrodesk/track/t" + it.id.toByteArray(Charsets.UTF_8).joinToString("") { byte -> "%02x".format(byte) } } ?: "/org/mpris/MediaPlayer2/TrackList/NoTrack"

    private fun rootProps(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(onQuit != null),
        "CanRaise" to Variant(onRaise != null),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("Metrodesk"),
        "DesktopEntry" to Variant("metrodesk"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    /** Everything except Position (which must not be broadcast in PropertiesChanged). */
    private fun playerProps(s: PlayerState): Map<String, Variant<*>> {
        val control = !Player.remoteControlled
        val song = s.current
        val meta = buildMap<String, Variant<*>> {
            put("mpris:trackid", Variant(DBusPath(trackPath(s))))
            if (song != null) {
                put("mpris:length", Variant(s.durationMs.coerceAtLeast(0) * 1000))
                put("xesam:title", Variant(song.title))
                put("xesam:artist", Variant(song.artists, "as"))
                song.album?.let { put("xesam:album", Variant(it)) }
                song.thumbnail?.let { put("mpris:artUrl", Variant(it)) }
                put("xesam:url", Variant("https://music.youtube.com/watch?v=${song.id}"))
            }
        }
        return mapOf(
            "PlaybackStatus" to Variant(if (song == null) "Stopped" else if (s.isPlaying) "Playing" else "Paused"),
            "LoopStatus" to Variant(when (s.repeat) { RepeatMode.OFF -> "None"; RepeatMode.ALL -> "Playlist"; RepeatMode.ONE -> "Track" }),
            "Shuffle" to Variant(s.shuffle),
            "Rate" to Variant(1.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "Volume" to Variant(if (s.muted) 0.0 else s.volume / 100.0),
            "Metadata" to Variant(meta, "a{sv}"),
            "CanGoNext" to Variant(control && song != null && s.hasNext),
            "CanGoPrevious" to Variant(control && song != null),
            "CanPlay" to Variant(control && song != null),
            "CanPause" to Variant(control && song != null),
            "CanSeek" to Variant(control && song != null),
            "CanControl" to Variant(control),
        )
    }

    private fun all(iface: String): Map<String, Variant<*>> = when (iface) {
        ROOT -> rootProps()
        PLAYER -> playerProps(Player.state.value) + ("Position" to Variant(Player.state.value.positionMs * 1000))
        else -> throw UnknownInterface(iface)
    }

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any?> Get(iface: String, prop: String): A = (all(iface)[prop] ?: throw UnknownProperty(prop)) as A

    override fun GetAll(iface: String): Map<String, Variant<*>> = all(iface)

    override fun <A : Any?> Set(iface: String, prop: String, value: A) {
        if (prop !in all(iface)) throw UnknownProperty(prop)
        if (iface != PLAYER || prop !in setOf("Volume", "Shuffle", "LoopStatus", "Rate")) throw PropertyReadOnly(prop)
        val v = (value as? Variant<*>)?.value ?: value
        when (prop) {
            "Volume" -> {
                if (v !is Double || !v.isFinite()) throw InvalidMethodArgument("Volume must be a finite number")
                command { Player.setVolume((v.coerceIn(0.0, 1.0) * 100).toInt()) }
            }
            "Shuffle" -> {
                if (v !is Boolean) throw InvalidMethodArgument("Shuffle must be boolean")
                transport { if (v != Player.state.value.shuffle) Player.toggleShuffle() }
            }
            "LoopStatus" -> {
                val target = when (v) { "Track" -> RepeatMode.ONE; "Playlist" -> RepeatMode.ALL; "None" -> RepeatMode.OFF; else -> throw InvalidMethodArgument("Unsupported LoopStatus") }
                transport { repeat(RepeatMode.entries.size) { if (Player.state.value.repeat != target) Player.cycleRepeat() } }
            }
            "Rate" -> if (v != 1.0) throw InvalidMethodArgument("Only playback rate 1.0 is supported")
        }
    }

    // endregion
}
