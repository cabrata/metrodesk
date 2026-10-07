package com.metrodesk.playback

import com.metrodesk.data.Library
import com.metrodesk.data.PersistedQueue
import com.metrodesk.data.Song
import com.metrodesk.data.Stores
import com.metrodesk.data.toSong
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertubex.extraction.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val queue: List<Song> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val volume: Int = 80,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val queueTitle: String? = null,
    val error: String? = null,
    val sleepTimerEndsAt: Long? = null,
) {
    val current: Song? get() = queue.getOrNull(index)
    val hasNext get() = index < queue.lastIndex || repeat == RepeatMode.ALL
}

/** Listener used by Listen Together to observe local user actions (only fired for user-initiated changes). */
interface PlayerHooks {
    fun onTrackChanged(song: Song, queue: List<Song>, index: Int) {}
    /** Fired whenever audio actually starts/resumes playing. */
    fun onPlaying(positionMs: Long) {}
    fun onPause(positionMs: Long) {}
    fun onSeek(positionMs: Long) {}
    fun onQueueChanged(queue: List<Song>, index: Int) {}
    fun onBufferReady(songId: String) {}
    fun onVolume(volume: Int) {}
}

object Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    @Volatile var hooks: PlayerHooks? = null
    /** When true, playback controls are driven remotely (Listen Together guest). */
    @Volatile var remoteControlled: Boolean = false

    private var factory: MediaPlayerFactory? = null
    @Volatile private var mp: MediaPlayer? = null
    private var loadJob: Job? = null
    private var retryJob: Job? = null
    private var radioJob: Job? = null
    private var persistJob: Job? = null
    private val generation = AtomicLong()
    @Volatile private var loadedGeneration = -1L
    private var events: MediaPlayerEventAdapter? = null
    private var sleepJob: Job? = null
    private var originalOrder: List<Song>? = null
    @Volatile private var gain = 1.0
    @Volatile private var pendingSeek: Long? = null
    @Volatile private var playWhenReady = true
    private var retried = false
    @Volatile private var loadedId: String? = null
    private var radioEndpoint: WatchEndpoint? = null
    private var radioContinuation: String? = null

    val available: Boolean get() = mp != null

    @Synchronized fun init(): Boolean {
        if (mp != null) return true
        return runCatching {
            val options = listOf("--no-video", "--network-caching=1500") + if (System.getenv("METRODESK_DEBUG") == null) listOf("--quiet") else listOf("--verbose=1")
            factory = MediaPlayerFactory(*options.toTypedArray())
            mp = factory!!.mediaPlayers().newMediaPlayer()
            val s = Stores.settings.value
            _state.update { it.copy(volume = s.volume) }
            restoreQueue()
            persistJob = scope.launch { positionPersistLoop() }
            true
        }.getOrElse {
            System.err.println("libVLC unavailable: ${it.message}")
            _state.update { s -> s.copy(error = "VLC not found. Install VLC media player to enable playback.") }
            false
        }
    }

    fun release() {
        val old: MediaPlayer?
        val oldFactory: MediaPlayerFactory?
        synchronized(this) {
            persist()
            cancelLoading()
            sleepJob?.cancel(); persistJob?.cancel()
            old = mp; oldFactory = factory
            mp = null; factory = null; events = null
        }
        // release may wait for VLC event dispatch, so never hold Player's monitor here.
        old?.release(); oldFactory?.release()
    }

    private fun cancelLoading() {
        generation.incrementAndGet()
        loadJob?.cancel(); retryJob?.cancel(); radioJob?.cancel()
        loadedGeneration = -1
        loadedId = null
        loadingId = null
        pendingSeek = null
    }

    // region queue

    @Synchronized fun playQueue(songs: List<Song>, startIndex: Int = 0, title: String? = null, radio: WatchEndpoint? = null, fromRemote: Boolean = false, play: Boolean = true, startAt: Long = 0) {
        if (songs.isEmpty() || remoteControlled && !fromRemote) return
        originalOrder = null
        radioEndpoint = radio
        radioContinuation = null
        _state.update { it.copy(queue = songs, index = startIndex.coerceIn(songs.indices), queueTitle = title, shuffle = false) }
        load(play = play, notify = !fromRemote, startAt = startAt)
    }

    /** Play a single song and fill the queue with a YouTube Music radio for it. */
    fun playRadio(song: Song) {
        if (remoteControlled) return
        playQueue(listOf(song), 0, title = "Radio: ${song.title}", radio = WatchEndpoint(videoId = song.id))
        radioJob = scope.launch { extendRadio() }
    }

    fun startRadio(endpoint: WatchEndpoint, title: String?) {
        if (remoteControlled) return
        radioJob?.cancel()
        val gen = generation.get()
        radioJob = scope.launch {
            YouTube.next(endpoint).onSuccess { r ->
                currentCoroutineContext().ensureActive()
                val songs = r.items.map { it.toSong() }
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (remoteControlled || gen != generation.get()) return@withContext
                    playQueue(songs, r.currentIndex ?: 0, title ?: r.title, radio = r.endpoint)
                    radioContinuation = r.continuation
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                if (gen == generation.get()) _state.update { it.copy(error = e.message) }
            }
        }
    }

    private suspend fun extendRadio() {
        if (remoteControlled) return
        val ep = radioEndpoint ?: return
        val gen = generation.get()
        YouTube.next(ep, radioContinuation).onSuccess { r ->
            currentCoroutineContext().ensureActive()
            if (remoteControlled || gen != generation.get()) return@onSuccess
            radioContinuation = r.continuation
            val have = _state.value.queue.map { it.id }.toSet()
            val add = r.items.map { it.toSong() }.filter { it.id !in have }
            if (add.isNotEmpty()) {
                _state.update { it.copy(queue = it.queue + add) }
                notifyQueue()
            }
            if (r.continuation == null) radioEndpoint = null
        }
    }

    fun playNext(songs: List<Song>) = mutateQueue { q, i ->
        val clean = songs.filter { s -> q.none { it.id == s.id } || q.getOrNull(i)?.id == s.id }.filter { it.id != q.getOrNull(i)?.id }
        val rest = q.toMutableList().apply { removeAll { s -> clean.any { it.id == s.id } } }
        val ci = rest.indexOfFirst { it.id == q.getOrNull(i)?.id }.coerceAtLeast(0)
        rest.addAll(if (rest.isEmpty()) 0 else ci + 1, clean)
        rest to ci
    }

    fun addToQueue(songs: List<Song>) = mutateQueue { q, i ->
        (q + songs.filter { s -> q.none { it.id == s.id } }) to i
    }

    @Synchronized fun removeFromQueue(pos: Int) {
        if (remoteControlled) return
        val s = _state.value
        if (pos !in s.queue.indices) return
        if (pos == s.index) {
            if (s.queue.size == 1) return stop()
            _state.update { it.copy(queue = it.queue.toMutableList().apply { removeAt(pos) }, index = pos.coerceAtMost(it.queue.size - 2)) }
            load(play = playWhenReady)
        } else {
            mutateQueue { q, i -> q.toMutableList().apply { removeAt(pos) } to if (pos < i) i - 1 else i }
        }
    }

    fun moveInQueue(from: Int, to: Int) = mutateQueue { q, i ->
        if (from !in q.indices || i !in q.indices) return@mutateQueue q to i
        val list = q.toMutableList()
        val cur = list[i]
        list.add(to.coerceIn(0, list.lastIndex), list.removeAt(from))
        list to list.indexOf(cur)
    }

    fun clearQueue() = mutateQueue { q, i -> listOfNotNull(q.getOrNull(i)) to 0 }

    /** Replace queue keeping the current song (used for Listen Together guest syncing). */
    @Synchronized fun setQueueFromRemote(songs: List<Song>, currentId: String?) {
        val idx = songs.indexOfFirst { it.id == currentId }
        _state.update { it.copy(queue = songs, index = if (idx >= 0) idx else it.index.coerceAtMost(songs.lastIndex)) }
    }

    @Synchronized private fun mutateQueue(f: (List<Song>, Int) -> Pair<List<Song>, Int>) {
        if (remoteControlled) return
        _state.update { s -> f(s.queue, s.index).let { (q, i) -> s.copy(queue = q, index = i) } }
        notifyQueue()
    }

    private fun notifyQueue() {
        persist()
        if (!remoteControlled) _state.value.let { hooks?.onQueueChanged(it.queue, it.index) }
    }

    @Synchronized fun toggleShuffle() {
        if (remoteControlled) return
        val s = _state.value
        if (s.queue.isEmpty()) return
        val cur = s.current ?: return
        if (!s.shuffle) {
            originalOrder = s.queue
            val rest = s.queue.filterIndexed { i, _ -> i != s.index }.shuffled()
            _state.update { it.copy(queue = listOf(cur) + rest, index = 0, shuffle = true) }
        } else {
            val orig = originalOrder ?: s.queue
            val merged = orig.filter { o -> s.queue.any { it.id == o.id } } + s.queue.filter { q -> orig.none { it.id == q.id } }
            _state.update { it.copy(queue = merged, index = merged.indexOfFirst { m -> m.id == cur.id }.coerceAtLeast(0), shuffle = false) }
            originalOrder = null
        }
        notifyQueue()
    }

    fun cycleRepeat() {
        if (!remoteControlled) _state.update { it.copy(repeat = RepeatMode.entries[(it.repeat.ordinal + 1) % RepeatMode.entries.size]) }
    }

    // endregion

    // region transport

    @Synchronized fun playPause() {
        if (remoteControlled) return
        val s = _state.value
        if (s.current == null) return
        if (s.isPlaying || s.isBuffering && playWhenReady) pause() else play()
    }

    @Synchronized fun play(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        playWhenReady = true
        val cur = _state.value.current ?: return
        if (loadedId != cur.id) {
            // Still resolving this song: it will start playing once ready.
            if (loadJob?.isActive == true && loadingId == cur.id) return
            return load(play = true, notify = false, startAt = _state.value.positionMs)
        }
        val gen = generation.get()
        p.submit { if (gen == generation.get() && p === mp) p.controls().play() }
    }

    @Synchronized fun pause(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        playWhenReady = false
        val gen = generation.get()
        p.submit { if (gen == generation.get() && p === mp) p.controls().setPause(true) }
        _state.update { it.copy(isPlaying = false) }
        if (!fromRemote) hooks?.onPause(_state.value.positionMs)
    }

    @Synchronized fun seekTo(ms: Long, fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        val p = mp ?: return
        val target = ms.coerceAtLeast(0)
        _state.update { it.copy(positionMs = target) }
        if (loadedId != _state.value.current?.id || !p.status().isSeekable) pendingSeek = target
        else {
            val gen = generation.get()
            p.submit { if (gen == generation.get() && p === mp) p.controls().setTime(target) }
        }
        if (!fromRemote) hooks?.onSeek(target)
    }

    @Synchronized fun next(auto: Boolean = false) {
        if (remoteControlled) return
        val s = _state.value
        when {
            auto && s.repeat == RepeatMode.ONE -> { seekTo(0, fromRemote = true); play(fromRemote = true); return }
            s.index < s.queue.lastIndex -> _state.update { it.copy(index = it.index + 1) }
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> _state.update { it.copy(index = 0) }
            else -> {
                if (auto) { pause(fromRemote = true); seekTo(0, fromRemote = true) }
                return
            }
        }
        load(play = true)
        if (_state.value.queue.size - _state.value.index < 4) radioJob = scope.launch { extendRadio() }
    }

    @Synchronized fun previous() {
        if (remoteControlled) return
        val s = _state.value
        if (s.current == null) return
        if (s.positionMs > 3000 || s.index == 0) { seekTo(0); return }
        _state.update { it.copy(index = it.index - 1) }
        load(play = true)
    }

    @Synchronized fun skipTo(index: Int, fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        if (index !in _state.value.queue.indices) return
        _state.update { it.copy(index = index) }
        load(play = !fromRemote || playWhenReady, notify = !fromRemote)
    }

    @Synchronized fun stop(fromRemote: Boolean = false) {
        if (remoteControlled && !fromRemote) return
        cancelLoading()
        sleepJob?.cancel()
        playWhenReady = false
        radioEndpoint = null; radioContinuation = null
        val gen = generation.get()
        mp?.let { p -> p.submit { if (gen == generation.get() && p === mp) p.controls().stop() } }
        loadedId = null
        _state.update { PlayerState(volume = it.volume) }
        persist()
    }

    fun setVolume(v: Int, fromRemote: Boolean = false) {
        val vol = v.coerceIn(0, 100)
        _state.update { it.copy(volume = vol, muted = false) }
        applyVolume()
        Stores.settings.update { it.copy(volume = vol) }
        if (!fromRemote) hooks?.onVolume(vol)
    }

    fun toggleMute() {
        _state.update { it.copy(muted = !it.muted) }
        applyVolume()
    }

    private fun applyVolume() {
        val p = mp ?: return
        val s = _state.value
        val v = if (s.muted) 0 else (s.volume * gain).toInt().coerceIn(0, 150)
        p.submit { p.audio().setVolume(v) }
    }

    fun setSleepTimer(minutes: Int?) {
        if (remoteControlled && minutes != null) return
        require(minutes == null || minutes in 1..1440) { "Sleep timer must be between 1 minute and 24 hours" }
        sleepJob?.cancel()
        if (minutes == null) { _state.update { it.copy(sleepTimerEndsAt = null) }; return }
        val end = System.currentTimeMillis() + minutes * 60_000L
        _state.update { it.copy(sleepTimerEndsAt = end) }
        sleepJob = scope.launch {
            delay(minutes * 60_000L)
            pause()
            _state.update { it.copy(sleepTimerEndsAt = null) }
        }
    }

    fun clearError() = _state.update { it.copy(error = null) }

    // endregion

    // region loading

    @Synchronized private fun load(play: Boolean, notify: Boolean = true, startAt: Long = 0) {
        val song = _state.value.current ?: return
        val p = mp ?: return
        loadJob?.cancel()
        retryJob?.cancel()
        val gen = generation.incrementAndGet()
        playWhenReady = play
        retried = false
        loadedId = null
        loadedGeneration = -1
        loadingId = song.id
        readyFor = null
        pendingSeek = startAt.takeIf { it > 0 }
        _state.update { it.copy(isPlaying = false, isBuffering = true, positionMs = startAt, durationMs = (song.duration ?: 0) * 1000L, error = null) }
        Library.addHistory(song)
        if (notify && !remoteControlled) _state.value.let { hooks?.onTrackChanged(song, it.queue, it.index) }
        persist()
        loadJob = scope.launch { startMedia(p, song, gen) }
    }

    private fun currentLoad(p: MediaPlayer, songId: String, gen: Long): Boolean =
        p === mp && gen == generation.get() && _state.value.current?.id == songId

    private suspend fun startMedia(p: MediaPlayer, song: Song, gen: Long) {
        val local = Stores.library.value.downloaded[song.id]?.let(::File)?.takeIf { it.exists() }
        val (mrl, options) = if (local != null) {
            gain = 1.0
            local.absolutePath to arrayOf()
        } else {
            val quality = runCatching { AudioQuality.valueOf(Stores.settings.value.audioQuality) }.getOrDefault(AudioQuality.AUTO)
            val stream = runCatching { StreamResolver.resolve(song.id, quality) }.getOrElse { e ->
                if (e is CancellationException) throw e
                currentCoroutineContext().ensureActive()
                if (!currentLoad(p, song.id, gen)) return
                _state.update { it.copy(isBuffering = false, error = "Can't play \"${song.title}\": ${e.message}") }
                delay(1500)
                if (currentLoad(p, song.id, gen)) next(auto = true)
                return
            }
            currentCoroutineContext().ensureActive()
            if (!currentLoad(p, song.id, gen)) return
            gain = if (Stores.settings.value.normalizeVolume) stream.loudnessDb?.let { 10.0.pow(-it / 20.0).coerceAtMost(1.0) } ?: 1.0 else 1.0
            lastClient = stream.clientName
            val ua = stream.headers["User-Agent"]
            stream.audioUrl to listOfNotNull(ua?.let { ":http-user-agent=$it" }, ":http-reconnect").toTypedArray()
        }
        currentCoroutineContext().ensureActive()
        if (!currentLoad(p, song.id, gen)) return
        p.submit {
            synchronized(Player) {
                if (!currentLoad(p, song.id, gen)) return@submit
                events?.let { p.events().removeMediaPlayerEventListener(it) }
                val listener = Events(song.id, gen)
                events = listener
                p.events().addMediaPlayerEventListener(listener)
                loadedId = song.id
                loadedGeneration = gen
                // start-paused avoids a burst of audio while a guest waits for the host's play message.
                val accepted = if (playWhenReady) p.media().play(mrl, *options) else p.media().play(mrl, "start-paused", *options)
                if (!accepted && currentLoad(p, song.id, gen)) onError(gen)
                if (currentLoad(p, song.id, gen)) applyVolume()
            }
        }
    }

    private var lastClient: String? = null
    @Volatile private var readyFor: String? = null
    @Volatile private var loadingId: String? = null

    @Synchronized private fun onError(gen: Long) {
        if (gen != generation.get()) return
        val song = _state.value.current ?: return
        lastClient?.let { StreamResolver.markFailed(song.id, it) }
        if (!retried) {
            retried = true
            val resume = _state.value.positionMs
            retryJob = scope.launch {
                StreamResolver.refreshAfterRejection()
                currentCoroutineContext().ensureActive()
                if (gen != generation.get()) return@launch
                pendingSeek = resume
                mp?.let { startMedia(it, song, gen) }
            }
        } else {
            _state.update { it.copy(isBuffering = false, error = "Playback failed for \"${song.title}\"") }
            retryJob = scope.launch { delay(1500); if (gen == generation.get()) next(auto = true) }
        }
    }

    private class Events(private val songId: String, private val gen: Long) : MediaPlayerEventAdapter() {
        private fun active(p: MediaPlayer) = currentLoad(p, songId, gen) && loadedGeneration == gen && loadedId == songId

        /** A paused/prebuffered guest must signal ready even though it has never played. */
        private fun ready(p: MediaPlayer) {
            if (!active(p)) return
            val seek: Long?
            synchronized(Player) {
                if (!active(p) || readyFor == songId) return
                readyFor = songId
                seek = pendingSeek
                pendingSeek = null
            }
            _state.update { if (active(p)) it.copy(isBuffering = false) else it }
            if (seek != null) p.submit { if (active(p)) p.controls().setTime(seek) }
            hooks?.onBufferReady(songId)
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            if (!active(mediaPlayer)) return
            val seek = pendingSeek
            _state.update { it.copy(isPlaying = playWhenReady, isBuffering = false) }
            ready(mediaPlayer)
            applyVolume()
            if (playWhenReady) {
                if (!remoteControlled) hooks?.onPlaying(seek ?: mediaPlayer.status().time())
            } else {
                mediaPlayer.submit { if (active(mediaPlayer) && !playWhenReady) mediaPlayer.controls().setPause(true) }
            }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            if (!active(mediaPlayer)) return
            _state.update { it.copy(isPlaying = false) }
            ready(mediaPlayer)
        }
        override fun stopped(mediaPlayer: MediaPlayer) {
            if (active(mediaPlayer)) _state.update { it.copy(isPlaying = false) }
        }
        override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) {
            if (!active(mediaPlayer)) return
            if (newCache >= 100f) ready(mediaPlayer)
            else _state.update { it.copy(isBuffering = true) }
        }
        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            if (active(mediaPlayer)) _state.update { it.copy(positionMs = newTime) }
        }
        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (active(mediaPlayer) && newLength > 0) _state.update { it.copy(durationMs = newLength) }
        }
        override fun finished(mediaPlayer: MediaPlayer) {
            if (!active(mediaPlayer)) return
            _state.update { it.copy(isPlaying = false) }
            scope.launch { if (active(mediaPlayer)) next(auto = true) }
        }
        override fun error(mediaPlayer: MediaPlayer) { if (active(mediaPlayer)) onError(gen) }
    }

    // endregion

    // region persistence

    private fun persist() {
        val s = _state.value
        Stores.queue.update { PersistedQueue(s.queue.take(500), s.index.coerceAtLeast(0), s.positionMs, s.queueTitle) }
    }

    private suspend fun positionPersistLoop() {
        while (scope.isActive) {
            delay(5000)
            if (_state.value.isPlaying) persist()
        }
    }

    private fun restoreQueue() {
        val q = Stores.queue.value
        if (q.songs.isEmpty()) return
        _state.update { it.copy(queue = q.songs, index = q.index.coerceIn(q.songs.indices), positionMs = q.positionMs, queueTitle = q.title) }
        pendingSeek = q.positionMs
        playWhenReady = false
    }

    // endregion
}
