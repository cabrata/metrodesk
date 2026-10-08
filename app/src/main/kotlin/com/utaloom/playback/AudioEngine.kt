package com.utaloom.playback

/** Platform audio backend. Positions and lengths are in milliseconds. */
interface AudioEngine {
    interface Listener {
        fun playing() {}
        fun paused() {}
        fun stopped() {}
        /** 0..100, with 100 indicating that playback is ready. */
        fun buffering(percent: Float) {}
        fun timeChanged(timeMs: Long) {}
        fun lengthChanged(lengthMs: Long) {}
        fun finished() {}
        fun error() {}
    }

    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)
    /** Queues work outside native event callbacks on the backend's playback thread. */
    fun submit(task: () -> Unit)
    fun play()
    fun setPause(paused: Boolean)
    fun seek(positionMs: Long)
    fun stop()
    /** 100 is unity gain. Player may request up to 150 when supported by the backend. */
    fun setVolume(volume: Int)
    /** Returns false if the backend rejects the media. Headers are unmodified HTTP headers. */
    fun load(url: String, headers: Map<String, String>, startPaused: Boolean): Boolean
    val time: Long
    val isSeekable: Boolean
    fun release()
}
