package com.utaloom.playback

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter

fun createAudioEngine(): AudioEngine {
    val options = listOf("--no-video", "--network-caching=1500") + if (System.getenv("UTALOOM_DEBUG") == null) listOf("--quiet") else listOf("--verbose=1")
    val factory = MediaPlayerFactory(*options.toTypedArray())
    return try {
        DesktopAudioEngine(factory, factory.mediaPlayers().newMediaPlayer())
    } catch (e: Throwable) {
        factory.release()
        throw e
    }
}

private class DesktopAudioEngine(private val factory: MediaPlayerFactory, private val player: MediaPlayer) : AudioEngine {
    private val listeners = mutableMapOf<AudioEngine.Listener, MediaPlayerEventAdapter>()

    @Synchronized override fun addListener(listener: AudioEngine.Listener) {
        if (listener in listeners) return
        val adapter = object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = listener.playing()
            override fun paused(mediaPlayer: MediaPlayer) = listener.paused()
            override fun stopped(mediaPlayer: MediaPlayer) = listener.stopped()
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = listener.buffering(newCache)
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = listener.timeChanged(newTime)
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) = listener.lengthChanged(newLength)
            override fun finished(mediaPlayer: MediaPlayer) = listener.finished()
            override fun error(mediaPlayer: MediaPlayer) = listener.error()
        }
        listeners[listener] = adapter
        player.events().addMediaPlayerEventListener(adapter)
    }

    @Synchronized override fun removeListener(listener: AudioEngine.Listener) {
        listeners.remove(listener)?.let { player.events().removeMediaPlayerEventListener(it) }
    }

    override fun submit(task: () -> Unit) { player.submit { task() } }
    override fun play() { player.controls().play() }
    override fun setPause(paused: Boolean) { player.controls().setPause(paused) }
    override fun seek(positionMs: Long) { player.controls().setTime(positionMs) }
    override fun stop() { player.controls().stop() }
    override fun setVolume(volume: Int) { player.audio().setVolume(volume) }

    override fun load(url: String, headers: Map<String, String>, startPaused: Boolean): Boolean {
        // ponytail: VLC supports User-Agent and Referer options, add a custom HTTP layer if other headers are required.
        val options = headers.mapNotNull { (name, value) ->
            when {
                name.equals("User-Agent", ignoreCase = true) -> ":http-user-agent=$value"
                name.equals("Referer", ignoreCase = true) -> ":http-referrer=$value"
                else -> null
            }
        } + listOfNotNull(
            "start-paused".takeIf { startPaused },
            ":http-reconnect".takeIf { url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) },
        )
        return player.media().play(url, *options.toTypedArray())
    }

    override val time: Long get() = player.status().time()
    override val isSeekable: Boolean get() = player.status().isSeekable

    override fun release() {
        try {
            player.release()
        } finally {
            factory.release()
        }
    }
}
