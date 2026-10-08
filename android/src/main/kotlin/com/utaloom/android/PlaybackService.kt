package com.utaloom.android

import android.content.Intent
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player as Media3Player
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.utaloom.data.Stores
import com.utaloom.playback.AndroidAudioEngine
import com.utaloom.playback.Player
import com.utaloom.together.ListenTogether

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        check(Player.init()) { "Cannot initialize Android audio" }
        val engine = checkNotNull(AndroidAudioEngine.current)
        // Route lock-screen/headset actions through the same queue and Listen Together guards as the UI.
        val transport = object : ForwardingPlayer(engine.exo) {
            private val forwarded = mutableMapOf<Media3Player.Listener, Media3Player.Listener>()
            override fun addListener(listener: Media3Player.Listener) {
                if (listener in forwarded) return
                val owner = this
                // Kotlin `by` skips Java default methods, so a Proxy is needed to forward every other event.
                val wrapped = java.lang.reflect.Proxy.newProxyInstance(listener.javaClass.classLoader, arrayOf(Media3Player.Listener::class.java)) { proxy, method, args ->
                    when (method.name) {
                        "onAvailableCommandsChanged" -> listener.onAvailableCommandsChanged(owner.availableCommands)
                        "equals" -> args?.get(0) === proxy
                        "hashCode" -> System.identityHashCode(proxy)
                        "toString" -> listener.toString()
                        else -> method.invoke(listener, *(args ?: emptyArray()))
                    }
                } as Media3Player.Listener
                forwarded[listener] = wrapped
                super.addListener(wrapped)
            }
            override fun removeListener(listener: Media3Player.Listener) {
                forwarded.remove(listener)?.let { super.removeListener(it) }
            }
            override fun play() = Player.play()
            override fun pause() = Player.pause()
            override fun stop() = Player.stop()
            override fun setPlayWhenReady(playWhenReady: Boolean) { if (playWhenReady) Player.play() else Player.pause() }
            override fun seekTo(positionMs: Long) = Player.seekTo(positionMs)
            override fun seekTo(mediaItemIndex: Int, positionMs: Long) = Player.seekTo(positionMs)
            override fun seekToNext() = Player.next()
            override fun seekToNextMediaItem() = Player.next()
            override fun seekToPrevious() = Player.previous()
            override fun seekToPreviousMediaItem() = Player.previous()
            override fun getAvailableCommands(): Media3Player.Commands = Media3Player.Commands.Builder().addAll(
                Media3Player.COMMAND_PLAY_PAUSE, Media3Player.COMMAND_STOP,
                Media3Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, Media3Player.COMMAND_SEEK_TO_NEXT, Media3Player.COMMAND_SEEK_TO_PREVIOUS,
                Media3Player.COMMAND_GET_CURRENT_MEDIA_ITEM, Media3Player.COMMAND_GET_TIMELINE,
                Media3Player.COMMAND_GET_METADATA, Media3Player.COMMAND_GET_AUDIO_ATTRIBUTES,
                Media3Player.COMMAND_GET_VOLUME,
            ).build()
            override fun isCommandAvailable(command: Int): Boolean = availableCommands.contains(command)
        }
        val openApp = android.app.PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, transport).setSessionActivity(openApp).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!Player.state.value.isPlaying && !Player.state.value.isBuffering) stopSelf()
    }

    override fun onDestroy() {
        ListenTogether.leaveRoom()
        session?.release()
        session = null
        Player.release()
        Stores.flushAll()
        super.onDestroy()
    }
}
