package com.utaloom.data

import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs

/** Result of importing a remote playlist. [missing] = tracks with no YouTube Music match. */
data class RemoteImport(val playlist: LocalPlaylist, val missing: List<String>)

private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
private val http by lazy { HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build() }

private fun get(url: String): HttpResponse<String> =
    http.send(HttpRequest.newBuilder(URI(url)).header("User-Agent", UA).timeout(Duration.ofSeconds(20)).build(), HttpResponse.BodyHandlers.ofString())

internal fun ytPlaylistId(url: String): String? =
    Regex("[?&]list=([A-Za-z0-9_-]{2,64})").find(url)?.groupValues?.get(1)?.takeIf {
        Regex("^https?://((www|m|music)\\.)?youtube\\.com/").containsMatchIn(url)
    }

internal fun spotifyRef(url: String): Pair<String, String>? =
    Regex("^https?://open\\.spotify\\.com/(?:intl-[a-z-]+/)?(playlist|album)/([A-Za-z0-9]{22})").find(url)?.destructured?.let { (t, id) -> t to id }

/** Accepts YouTube Music / YouTube playlist links and Spotify playlist / album links (incl. spotify.link short links). */
suspend fun importRemotePlaylist(input: String, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): RemoteImport = withContext(Dispatchers.IO) {
    val url = input.trim()
    require(url.length <= 2048) { "Link is too long" }
    ytPlaylistId(url)?.let { return@withContext importYouTube(it) }
    val resolved = if (Regex("^https://spotify\\.link/[A-Za-z0-9]+$").matches(url)) get(url).uri().toString() else url
    spotifyRef(resolved)?.let { (type, id) -> return@withContext importSpotify(type, id, onProgress) }
    throw IllegalArgumentException("Paste a Spotify playlist/album link or a YouTube Music playlist link")
}

private suspend fun importYouTube(id: String): RemoteImport {
    val page = YouTube.playlist(id).getOrThrow()
    val songs = page.songs.toMutableList()
    var cont = page.songsContinuation ?: page.continuation
    while (cont != null && songs.size < 20_000) {
        val next = YouTube.playlistContinuation(cont).getOrThrow()
        if (next.songs.isEmpty()) break
        songs += next.songs; cont = next.continuation
    }
    return RemoteImport(LocalPlaylist("", page.playlist.title.ifBlank { "YouTube playlist" }.take(200), songs.map { it.toSong() }.distinctBy { it.id }), emptyList())
}

private data class SpTrack(val title: String, val artist: String, val durationSec: Int)

// ponytail: scrapes the public embed page (no API key). Embed only lists ~100 tracks; longer playlists need the Spotify Web API + OAuth.
private fun spotifyEmbed(type: String, id: String): Pair<String, List<SpTrack>> {
    val res = get("https://open.spotify.com/embed/$type/$id")
    require(res.statusCode() == 200) { "Spotify $type not found (HTTP ${res.statusCode()})" }
    val raw = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.+?)</script>", RegexOption.DOT_MATCHES_ALL).find(res.body())?.groupValues?.get(1)
        ?: error("Could not read Spotify page. Is the playlist public?")
    val entity = Json.parseToJsonElement(raw).jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
        ?.get("data")?.jsonObject?.get("entity")?.jsonObject ?: error("Spotify $type not found or private")
    val tracks = entity["trackList"]?.jsonArray.orEmpty().mapNotNull { el ->
        val t = el.jsonObject
        val title = t["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        SpTrack(title, t["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), ((t["duration"]?.jsonPrimitive?.longOrNull ?: 0) / 1000).toInt())
    }
    return (entity["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify $type") to tracks
}

/** Among the top results: prefer a matching artist, then the closest duration. Rejects hits more than 30s off. */
internal fun bestMatch(hits: List<SongItem>, durationSec: Int, artist: String = ""): SongItem? {
    val wanted = artist.lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun diff(h: SongItem) = if (durationSec <= 0 || h.duration == null) 0 else abs(h.duration!! - durationSec)
    fun artistMiss(h: SongItem) = if (wanted.isEmpty() || h.artists.any { a -> a.name.lowercase().trim() in wanted }) 0 else 1
    return hits.take(5).filter { diff(it) <= 30 }.minWithOrNull(compareBy({ artistMiss(it) }, { diff(it) }))
}

private suspend fun importSpotify(type: String, id: String, onProgress: (Int, Int) -> Unit): RemoteImport = coroutineScope {
    val (name, tracks) = spotifyEmbed(type, id)
    val gate = Semaphore(4)
    var done = 0
    val matched = tracks.map { t ->
        async {
            gate.withPermit {
                val title = t.title.replace(Regex("\\s+-\\s+.*(remaster|version|edit|mix|live).*$", RegexOption.IGNORE_CASE), "")
                val hits = YouTube.search("$title ${t.artist}", YouTube.SearchFilter.FILTER_SONG).getOrNull()?.items?.filterIsInstance<SongItem>().orEmpty()
                bestMatch(hits, t.durationSec, t.artist).also { synchronized(this@coroutineScope) { onProgress(++done, tracks.size) } }
            }
        }
    }.awaitAll()
    val songs = matched.filterNotNull().map { it.toSong() }.distinctBy { it.id }
    val missing = tracks.zip(matched).filter { it.second == null }.map { "${it.first.title} - ${it.first.artist}" }
    RemoteImport(LocalPlaylist("", name.trim().ifBlank { "Spotify $type" }.take(200), songs), missing)
}

internal fun playlistImportSelfCheck() {
    check(ytPlaylistId("https://music.youtube.com/playlist?list=PLabc_-123") == "PLabc_-123")
    check(ytPlaylistId("https://www.youtube.com/watch?v=x&list=RDabc") == "RDabc")
    check(ytPlaylistId("https://evil.com/?list=PLabc") == null)
    check(spotifyRef("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=x") == ("playlist" to "37i9dQZF1DXcBWIGoYBM5M"))
    check(spotifyRef("https://open.spotify.com/intl-id/album/37i9dQZF1DXcBWIGoYBM5M") == ("album" to "37i9dQZF1DXcBWIGoYBM5M"))
    check(spotifyRef("https://open.spotify.com/track/37i9dQZF1DXcBWIGoYBM5M") == null)
    val a = SongItem(id = "a", title = "x", artists = emptyList(), duration = 300, thumbnail = "")
    val b = SongItem(id = "b", title = "x", artists = emptyList(), duration = 200, thumbnail = "")
    check(bestMatch(listOf(a, b), 199)?.id == "b")
    check(bestMatch(listOf(a), 100) == null)
    val c = b.copy(id = "c", artists = listOf(com.utaloom.innertube.models.Artist("Olivia Rodrigo", null)), duration = 210)
    check(bestMatch(listOf(b, c), 200, "Olivia Rodrigo")?.id == "c")
}
