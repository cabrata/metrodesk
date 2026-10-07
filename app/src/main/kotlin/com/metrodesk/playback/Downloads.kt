package com.metrodesk.playback

import com.metrodesk.data.Library
import com.metrodesk.data.Paths
import com.metrodesk.data.Song
import com.metrodesk.data.Stores
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit

object Downloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.MINUTES).build()
    private val limit = Semaphore(2)
    private val _progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val progress = _progress.asStateFlow()
    private val _errors = MutableStateFlow<Map<String, String>>(emptyMap())
    val errors = _errors.asStateFlow()

    fun isDownloaded(id: String) = Stores.library.value.downloaded[id]?.let { File(it).exists() } == true
    fun clearErrors() { _errors.value = emptyMap() }

    fun download(songs: List<Song>) = songs.distinctBy { it.id }.forEach { song ->
        if (isDownloaded(song.id) || !song.id.matches(Regex("[A-Za-z0-9_-]{11}"))) return@forEach
        synchronized(this) {
            if (song.id in _progress.value) return@forEach
            _progress.update { it + (song.id to 0f) }
        }
        scope.launch {
            try {
                limit.withPermit { fetch(song) }
                _errors.update { it - song.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _errors.update { it + (song.id to "Couldn't download ${song.title}. Check your connection and retry.") }
            } finally {
                _progress.update { it - song.id }
            }
        }
    }

    fun delete(id: String) {
        val f = Stores.library.value.downloaded[id]?.let(::File)
        if (f == null || !f.exists() || f.delete()) Library.setDownloaded(id, null)
        else _errors.update { it + (id to "Couldn't remove the downloaded file.") }
    }

    private suspend fun fetch(song: Song) {
        val stream = StreamResolver.resolve(song.id, com.metrolist.innertubex.extraction.AudioQuality.HIGH)
        currentCoroutineContext().ensureActive()
        val ext = if (stream.mimeType?.contains("mp4") == true) "m4a" else "webm"
        val target = Paths.downloads.resolve("${song.id}.$ext")
        val tmp = File(target.path + ".part")
        val total = stream.contentLengthBytes?.takeIf { it > 0 }
        val chunk = 1L shl 20
        var pos = 0L
        try {
            tmp.outputStream().use { out ->
                while (total == null || pos < total) {
                    currentCoroutineContext().ensureActive()
                    val end = if (total != null) minOf(pos + chunk, total) - 1 else pos + chunk - 1
                    val req = Request.Builder().url(stream.audioUrl).apply {
                        stream.headers.forEach { (k, v) -> header(k, v) }
                        header("Range", "bytes=$pos-$end")
                    }.build()
                    val result = http.newCall(req).execute().use { r ->
                        if (r.code == 416 && total == null && pos > 0) return@use 0L to false
                        check(r.code == 206 || (r.code == 200 && pos == 0L)) { "Invalid ranged response (${r.code})" }
                        if (r.code == 206) {
                            val range = r.header("Content-Range").orEmpty()
                            check(range.startsWith("bytes $pos-")) { "Mismatched Content-Range" }
                        }
                        val read = r.body.byteStream().use { it.copyTo(out) }
                        if (r.code == 206) check(read in 1..(end - pos + 1)) { "Incomplete response" }
                        read to (r.code == 200)
                    }
                    if (result.first == 0L) break
                    pos += result.first
                    if (total != null) _progress.update { it + (song.id to (pos.toFloat() / total).coerceIn(0f, 1f)) }
                    if (result.second || (total == null && result.first < chunk)) break
                }
            }
            check(pos > 0 && (total == null || pos == total)) { "Incomplete download" }
            try { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            Library.setDownloaded(song.id, target.absolutePath, song)
        } finally {
            tmp.delete()
        }
    }
}
