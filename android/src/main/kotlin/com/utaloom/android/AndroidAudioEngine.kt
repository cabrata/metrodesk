package com.utaloom.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player as Media3Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.utaloom.android.UtaloomApplication
import com.utaloom.data.Song
import okhttp3.OkHttpClient
import java.util.concurrent.CopyOnWriteArrayList

internal fun Song.toMediaItem(): MediaItem = MediaItem.Builder().setMediaId(id).setMediaMetadata(
    MediaMetadata.Builder().setTitle(title).setArtist(artistText).setAlbumTitle(album)
        .setArtworkUri(thumbnail?.let(android.net.Uri::parse)).setDurationMs(duration?.takeIf { it > 0 }?.toLong()?.times(1000))
        .setIsPlayable(true).setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC).build(),
).build()

fun createAudioEngine(): AudioEngine {
    check(Looper.myLooper() == Looper.getMainLooper()) { "Initialize Android playback on the main thread" }
    return AndroidAudioEngine().also { AndroidAudioEngine.current = it }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AndroidAudioEngine : AudioEngine {
    val exo = ExoPlayer.Builder(UtaloomApplication.instance).setLooper(Looper.getMainLooper()).build().apply {
        setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        setHandleAudioBecomingNoisy(true)
        setWakeMode(C.WAKE_MODE_LOCAL)
    }
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<AudioEngine.Listener>()
    private val http = OkHttpClient()
    @Volatile private var released = false
    @Volatile override var time = 0L
        private set
    @Volatile override var isSeekable = false
        private set

    init {
        exo.addListener(object : Media3Player.Listener {
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason in listOf(Media3Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, Media3Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY)) {
                    // Headset disconnect/focus loss is a real pause, not a request to auto-resume the next load.
                    Player.pause(fromRemote = Player.remoteControlled)
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Media3Player.STATE_BUFFERING -> listeners.forEach { it.buffering(0f) }
                    Media3Player.STATE_READY -> {
                        isSeekable = exo.isCurrentMediaItemSeekable
                        val duration = exo.duration
                        if (duration > 0) listeners.forEach { it.lengthChanged(duration) }
                        listeners.forEach { it.buffering(100f) }
                        if (!exo.playWhenReady) listeners.forEach { it.paused() }
                    }
                    Media3Player.STATE_ENDED -> listeners.forEach { it.finished() }
                    Media3Player.STATE_IDLE -> listeners.forEach { it.stopped() }
                }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) listeners.forEach { it.playing() }
                else if (exo.playbackState == Media3Player.STATE_READY) listeners.forEach { it.paused() }
            }
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("Utaloom", "Playback failed", error)
                listeners.forEach { it.error() }
            }
        })
        handler.post(object : Runnable {
            override fun run() {
                if (released) return
                time = exo.currentPosition.coerceAtLeast(0)
                isSeekable = exo.isCurrentMediaItemSeekable
                if (exo.playbackState == Media3Player.STATE_READY) listeners.forEach { it.timeChanged(time) }
                handler.postDelayed(this, 250)
            }
        })
    }

    override fun addListener(listener: AudioEngine.Listener) { listeners.add(listener) }
    override fun removeListener(listener: AudioEngine.Listener) { listeners.remove(listener) }
    override fun submit(task: () -> Unit) {
        // Keep a single owner thread even when callers are room-sync or extraction coroutines.
        handler.post { if (!released) task() }
    }
    override fun play() { exo.play() }
    override fun setPause(paused: Boolean) { exo.playWhenReady = !paused }
    override fun seek(positionMs: Long) { time = positionMs; exo.seekTo(positionMs) }
    override fun stop() { exo.stop(); exo.clearMediaItems(); time = 0; isSeekable = false }
    override fun setVolume(volume: Int) { exo.volume = (volume / 100f).coerceIn(0f, 1f) }

    override fun load(url: String, headers: Map<String, String>, startPaused: Boolean): Boolean = runCatching {
        val song = Player.state.value.current
        val item = (song?.toMediaItem()?.buildUpon() ?: MediaItem.Builder().setMediaId(url)).setUri(url).build()
        val data = DefaultDataSource.Factory(UtaloomApplication.instance, OkHttpDataSource.Factory(http).setDefaultRequestProperties(headers))
        time = 0; isSeekable = false
        exo.setMediaSource(ProgressiveMediaSource.Factory(data).createMediaSource(item))
        exo.playWhenReady = !startPaused
        exo.prepare()
        true
    }.getOrElse {
        android.util.Log.e("Utaloom", "Cannot prepare audio", it)
        false
    }

    @Synchronized override fun release() {
        if (released) return
        released = true
        if (current === this) current = null
        handler.post {
            handler.removeCallbacksAndMessages(null)
            exo.release()
            listeners.clear()
        }
    }

    companion object { @Volatile var current: AndroidAudioEngine? = null }
}
