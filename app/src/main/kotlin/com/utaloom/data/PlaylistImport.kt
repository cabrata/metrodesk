package com.utaloom.data

import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** Result of importing a remote playlist. [missing] = tracks with no YouTube Music match, [note] = non-fatal warning. */
data class RemoteImport(val playlist: LocalPlaylist, val missing: List<String>, val note: String? = null)

/** App-wide link import that survives closing the dialog, so it can keep running in the background. One at a time. */
object PlaylistImports {
    data class Progress(val done: Int = 0, val total: Int = 0, val background: Boolean = false)
    sealed interface Event { val background: Boolean }
    data class Done(val playlistId: String, val name: String, val songs: Int, val missing: Int, val note: String?, override val background: Boolean) : Event
    data class Failed(val message: String, override val background: Boolean) : Event

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress = _progress.asStateFlow()
    val events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start(url: String): Unit = synchronized(this) {
        if (job?.isActive == true) return
        _progress.value = Progress()
        // LAZY so `job` is assigned before the body (and its finally) can run.
        val self = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            fun mine() = job === me
            try {
                val r = importRemotePlaylist(url) { d, t -> synchronized(this@PlaylistImports) { if (mine()) _progress.update { it?.copy(done = d, total = t) } } }
                // Cancelled while matching: don't create the playlist.
                val id = synchronized(this@PlaylistImports) { if (mine()) Library.createPlaylist(r.playlist.name, r.playlist.songs) else null } ?: return@launch
                events.emit(Done(id, r.playlist.name, r.playlist.songs.size, r.missing.size, r.note, isBackground()))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (mine()) events.emit(Failed((e as? IllegalArgumentException)?.message ?: "Import failed. Check the link and your connection.", isBackground()))
            } finally { synchronized(this@PlaylistImports) { if (mine()) { _progress.value = null; job = null } } }
        }
        job = self
        self.start()
    }

    private fun isBackground() = _progress.value?.background == true
    fun toBackground() = _progress.update { it?.copy(background = true) }
    // A blocking HTTP call may still be finishing; detach it so the UI clears now and a new import can start.
    fun cancel(): Unit = synchronized(this) { job?.cancel(); job = null; _progress.value = null }

    fun summary(e: Event) = when (e) {
        is Done -> "Imported \"${e.name}\": ${e.songs} songs" + (if (e.missing > 0) ", ${e.missing} not found on YouTube Music" else "") + (e.note?.let { ". $it" } ?: "")
        is Failed -> e.message
    }
}

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
private class SpEmbed(val name: String, val tracks: List<SpTrack>, val token: String?)

// ponytail: scrapes the public embed page (no API key). Embed lists at most 100 tracks; spotifyAllTracks pages the rest.
private fun spotifyEmbed(type: String, id: String): SpEmbed {
    val res = get("https://open.spotify.com/embed/$type/$id")
    require(res.statusCode() == 200) { "Spotify $type not found (HTTP ${res.statusCode()})" }
    val raw = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.+?)</script>", RegexOption.DOT_MATCHES_ALL).find(res.body())?.groupValues?.get(1)
        ?: error("Could not read Spotify page. Is the playlist public?")
    val state = Json.parseToJsonElement(raw).jsonObject["props"]?.jsonObject?.get("pageProps")?.jsonObject?.get("state")?.jsonObject
    val entity = state?.get("data")?.jsonObject?.get("entity")?.jsonObject ?: error("Spotify $type not found or private")
    val token = state["settings"]?.jsonObject?.get("session")?.jsonObject?.get("accessToken")?.jsonPrimitive?.contentOrNull
    val tracks = entity["trackList"]?.jsonArray.orEmpty().mapNotNull { el ->
        val t = el.jsonObject
        val title = t["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        SpTrack(title, t["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty(), ((t["duration"]?.jsonPrimitive?.longOrNull ?: 0) / 1000).toInt())
    }
    return SpEmbed(entity["name"]?.jsonPrimitive?.contentOrNull ?: "Spotify $type", tracks, token)
}

// ponytail: unofficial web-player GraphQL with the embed's anonymous token. The official Web API (OAuth) only returns
// items of playlists the user owns since Feb 2026 and caps dev apps at 5 users, so it can't serve public playlists.
// The persisted-query hash rotates; it is re-read from the web player bundle when Spotify answers 412.
private var playlistHash = "8964e8eafb21aa992a7d951d256d83285c04be2105d209262901de70cb97584a"

private fun refreshPlaylistHash() {
    val home = get("https://open.spotify.com/").body()
    for (js in Regex("https://open\\.spotifycdn\\.com/cdn/build/web-player/[^\"]+\\.js").findAll(home).map { it.value }.toSet()) {
        Regex("\"fetchPlaylist\",\"query\",\"([a-f0-9]{64})\"").find(get(js).body())?.let { playlistHash = it.groupValues[1]; return }
    }
    error("Spotify playlist query not found")
}

private fun spotifyPage(id: String, token: String, offset: Int, retry: Boolean = true): Pair<Int, List<SpTrack>> {
    val body = """{"operationName":"fetchPlaylist","variables":{"uri":"spotify:playlist:$id","offset":$offset,"limit":100,"enableWatchFeedEntrypoint":false},"extensions":{"persistedQuery":{"version":1,"sha256Hash":"$playlistHash"}}}"""
    val res = http.send(HttpRequest.newBuilder(URI("https://api-partner.spotify.com/pathfinder/v2/query")).header("User-Agent", UA)
        .header("Authorization", "Bearer $token").header("Content-Type", "application/json").timeout(Duration.ofSeconds(20))
        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString())
    if (res.statusCode() == 412 && retry) { refreshPlaylistHash(); return spotifyPage(id, token, offset, false) }
    check(res.statusCode() == 200) { "Spotify HTTP ${res.statusCode()}" }
    val content = Json.parseToJsonElement(res.body()).jsonObject["data"]?.jsonObject?.get("playlistV2")?.jsonObject?.get("content")?.jsonObject
        ?: error("Spotify playlist unavailable")
    val tracks = content["items"]?.jsonArray.orEmpty().mapNotNull { el ->
        val d = el.jsonObject["itemV2"]?.jsonObject?.get("data")?.jsonObject ?: return@mapNotNull null
        val title = d["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val artists = d["artists"]?.jsonObject?.get("items")?.jsonArray.orEmpty()
            .mapNotNull { it.jsonObject["profile"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull }
        val ms = d["trackDuration"]?.jsonObject?.get("totalMilliseconds")?.jsonPrimitive?.longOrNull ?: 0
        SpTrack(title, artists.joinToString(", "), (ms / 1000).toInt())
    }
    return (content["totalCount"]?.jsonPrimitive?.intOrNull ?: 0) to tracks
}

/** All tracks of a public playlist, or null if the GraphQL route fails (caller keeps the embed's first 100). */
private fun spotifyAllTracks(id: String, token: String): List<SpTrack>? = runCatching {
    val out = mutableListOf<SpTrack>()
    var total: Int
    do {
        val (count, page) = spotifyPage(id, token, out.size)
        total = minOf(count, 20_000)
        out += page
    } while (page.isNotEmpty() && out.size < total)
    out
}.getOrNull()

/** Among the top results: prefer a matching artist, then the closest duration. Rejects hits more than 30s off. */
internal fun bestMatch(hits: List<SongItem>, durationSec: Int, artist: String = ""): SongItem? {
    val wanted = artist.lowercase().split(",").map { it.trim() }.filter { it.isNotEmpty() }
    fun diff(h: SongItem) = if (durationSec <= 0 || h.duration == null) 0 else abs(h.duration!! - durationSec)
    fun artistMiss(h: SongItem) = if (wanted.isEmpty() || h.artists.any { a -> a.name.lowercase().trim() in wanted }) 0 else 1
    return hits.take(5).filter { diff(it) <= 30 }.minWithOrNull(compareBy({ artistMiss(it) }, { diff(it) }))
}

private suspend fun importSpotify(type: String, id: String, onProgress: (Int, Int) -> Unit): RemoteImport = coroutineScope {
    val embed = spotifyEmbed(type, id)
    val full = if (type == "playlist" && embed.tracks.size >= 100 && embed.token != null) spotifyAllTracks(id, embed.token) else null
    val tracks = full ?: embed.tracks
    val note = if (type == "playlist" && embed.tracks.size >= 100 && full == null) "Spotify only returned the first 100 tracks." else null
    val name = embed.name
    val gate = Semaphore(8)
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
    RemoteImport(LocalPlaylist("", name.trim().ifBlank { "Spotify $type" }.take(200), songs), missing, note)
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
