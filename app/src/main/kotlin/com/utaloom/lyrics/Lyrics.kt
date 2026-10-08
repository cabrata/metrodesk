package com.utaloom.lyrics

import com.utaloom.data.Song
import com.utaloom.shared.Lyrics
import com.utaloom.shared.parseLrc
import com.utaloom.shared.plainLyrics
import com.metrolist.kugou.KuGou
import com.metrolist.music.betterlyrics.BetterLyrics
import com.metrolist.music.lyrics.LyricsPlusProvider
import com.metrolist.music.lyrics.ZemerLyricsProvider
import com.metrolist.paxsenix.Paxsenix
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

object LyricsRepository {
    /** Same order as Metrolist's default. */
    val providers = listOf("BetterLyrics", "LrcLib", "KuGou", "Paxsenix", "LyricsPlus", "Zemer", "YouTubeSubtitle", "YouTube")

    private val cache = ConcurrentHashMap<String, Lyrics?>()

    private val http by lazy {
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
            expectSuccess = false
        }
    }

    @Serializable
    private data class LrcTrack(
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    suspend fun get(song: Song, providers: List<String>): Lyrics? {
        cache[song.id]?.let { return it }
        var result: Lyrics? = null
        val title = clean(song.title)
        val artist = song.artists.joinToString()
        val dur = song.duration ?: -1
        for (p in providers) {
            result = runCatching {
                withTimeoutOrNull(15_000) {
                    when (p) {
                        "LrcLib" -> lrclib(song)
                        "YouTube" -> youtube(song)
                        "BetterLyrics" -> lrc(p, BetterLyrics.getLyrics(title, artist, dur, song.album))
                        "KuGou" -> lrc(p, KuGou.getLyrics(title, artist, dur, song.album))
                        "Paxsenix" -> lrc(p, Paxsenix.getLyrics(title, artist, dur, song.album))
                        "LyricsPlus" -> lrc(p, LyricsPlusProvider.getLyrics(song.id, title, artist, dur, song.album))
                        "Zemer" -> lrc(p, ZemerLyricsProvider.getLyrics(song.id))
                        "YouTubeSubtitle" -> lrc("YouTube subtitles", YouTube.transcript(song.id))
                        else -> null
                    }
                }
            }.getOrNull()
            if (result != null) break
        }
        if (result != null) cache[song.id] = result
        return result
    }

    private fun lrc(provider: String, r: Result<String>): Lyrics? =
        r.getOrNull()?.takeIf { it.isNotBlank() }?.let { parseLrc(provider, it) }?.takeIf { it.lines.isNotEmpty() }

    fun invalidate(id: String) = cache.remove(id)

    private val noise = Regex(
        """\s*[(\[](?=[^)\]]*(official|video|audio|lyric|visualizer|remaster|feat\.|ft\.))[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private fun clean(t: String) = t.replace(noise, "").trim()

    private suspend fun lrclib(song: Song): Lyrics? {
        val title = clean(song.title)
        val artist = song.artists.firstOrNull().orEmpty()
        suspend fun search(vararg params: Pair<String, String>): List<LrcTrack> =
            runCatching {
                http.get("https://lrclib.net/api/search") {
                    header("User-Agent", "Utaloom (https://github.com/cabrata/utaloom)")
                    params.forEach { (k, v) -> parameter(k, v) }
                }.body<List<LrcTrack>>()
            }.getOrDefault(emptyList()).filter { it.syncedLyrics != null || it.plainLyrics != null }

        val tracks = search("track_name" to title, "artist_name" to artist).ifEmpty { search("q" to "$artist $title") }
        val dur = song.duration ?: -1
        val best = tracks
            .sortedWith(compareBy<LrcTrack>({ if (dur > 0) abs((it.duration ?: 0.0) - dur) else 0.0 }, { if (it.syncedLyrics != null) 0 else 1 }))
            .firstOrNull { dur <= 0 || abs((it.duration ?: dur.toDouble()) - dur) <= 5 }
            ?: return null
        return best.syncedLyrics?.let { parseLrc("LrcLib", it) } ?: best.plainLyrics?.let { plainLyrics("LrcLib", it) }
    }

    private suspend fun youtube(song: Song): Lyrics? {
        val next = YouTube.next(WatchEndpoint(videoId = song.id)).getOrNull() ?: return null
        val text = YouTube.lyrics(next.lyricsEndpoint ?: return null).getOrNull() ?: return null
        return plainLyrics("YouTube Music", text)
    }
}
