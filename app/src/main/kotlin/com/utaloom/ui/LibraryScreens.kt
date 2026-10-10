package com.utaloom.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.utaloom.data.Library
import com.utaloom.data.LibraryState
import com.utaloom.data.LocalPlaylist
import com.utaloom.data.SavedRef
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.data.RemoteImport
import com.utaloom.data.importRemotePlaylist
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.SongItem
import com.utaloom.innertube.models.YTItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.Locale
import com.utaloom.platform.readPlaylistText
import com.utaloom.platform.writePlaylistText

private val playlistJson = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
private const val maxPlaylistBytes = 10 * 1024 * 1024

internal fun playlistNameError(name: String): String? = when {
    name.trim().isEmpty() -> "Enter a playlist name"
    name.trim().length > 200 -> "Use a name of 200 characters or fewer"
    name.any { it.isISOControl() } -> "The name cannot contain control characters"
    else -> null
}

internal fun decodePlaylist(text: String): LocalPlaylist {
    require(text.toByteArray(Charsets.UTF_8).size <= maxPlaylistBytes) { "Playlist JSON is too large (10 MiB maximum)" }
    val p = try { playlistJson.decodeFromString(LocalPlaylist.serializer(), text) }
    catch (_: Exception) { throw IllegalArgumentException("Invalid playlist JSON. Export a Utaloom playlist to see the format.") }
    require(playlistNameError(p.name) == null) { playlistNameError(p.name).orEmpty() }
    require(p.songs.size <= 20_000) { "A playlist can contain at most 20,000 songs" }
    p.songs.forEach { song ->
        require(song.id.matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "A song has an invalid ID" }
        require(song.title.isNotBlank() && song.title.length <= 2000 && song.title.none { it.isISOControl() }) { "A song has an invalid title" }
        require(song.artists.size <= 100 && song.artists.all { it.length <= 500 && it.none { c -> c.isISOControl() } }) { "A song has invalid artists" }
        require(song.duration == null || song.duration in 0..604_800) { "A song has an invalid duration" }
        require(listOfNotNull(song.thumbnail).all { it.isEmpty() || (it.length <= 4096 && (it.startsWith("https://") || it.startsWith("http://"))) }) { "A song has an invalid thumbnail URL" }
    }
    require(p.songs.distinctBy { it.id }.size == p.songs.size) { "Duplicate song IDs found. No songs were imported." }
    return p.copy(name = p.name.trim())
}

private suspend fun readPlaylistFile(): LocalPlaylist? = readPlaylistText(maxPlaylistBytes)?.let(::decodePlaylist)

private suspend fun exportPlaylist(p: LocalPlaylist): Boolean =
    writePlaylistText(p.name, playlistJson.encodeToString(LocalPlaylist.serializer(), p))

private fun knownSongs(lib: LibraryState): List<Song> =
    (lib.history + lib.liked + lib.playlists.flatMap { it.songs } + lib.downloadedSongs.values + Stores.queue.value.songs).distinctBy { it.id }

private fun downloadedSongs(lib: LibraryState): List<Song> {
    val known = knownSongs(lib).associateBy { it.id }
    return lib.downloaded.keys.map { lib.downloadedSongs[it] ?: known[it] ?: Song(it, it, emptyList()) }
}

private fun visibleSongs(songs: List<Song>, query: String, sort: String, hideExplicit: Boolean): List<Song> {
    val filtered = songs.filter { (!hideExplicit || !it.explicit) && (query.isBlank() || "${it.title} ${it.artistText} ${it.album.orEmpty()}".contains(query.trim(), true)) }
    return when (sort) {
        "Title" -> filtered.sortedBy { it.title.lowercase(Locale.ROOT) }
        "Artist" -> filtered.sortedWith(compareBy<Song> { it.artistText.lowercase(Locale.ROOT) }.thenBy { it.title.lowercase(Locale.ROOT) })
        "Duration" -> filtered.sortedByDescending { it.duration ?: 0 }
        else -> filtered
    }
}

@Composable
private fun LibrarySelect(value: String, options: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton({ open = true }) { Text(value); Icon(Icons.Default.ArrowDropDown, null) }
        DropdownMenu(open, { open = false }) {
            options.forEach { option -> DropdownMenuItem({ Text(option) }, { onSelect(option); open = false }) }
        }
    }
}

@Composable
private fun LibrarySearch(query: String, onQuery: (String) -> Unit, sort: String, onSort: (String) -> Unit, options: List<String> = listOf("Original order", "Title", "Artist", "Duration")) {
    @Composable
    fun SearchField(modifier: Modifier) {
        OutlinedTextField(query, onQuery, modifier, placeholder = { Text("Search this collection") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = { if (query.isNotEmpty()) IconButton({ onQuery("") }) { Icon(Icons.Default.Close, "Clear search") } })
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        if (maxWidth < 552.dp) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(Modifier.fillMaxWidth())
            LibrarySelect(sort, options, onSort)
        } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SearchField(Modifier.weight(1f))
            LibrarySelect(sort, options, onSort)
        }
    }
}

@Composable
private fun SongsHeader(title: String, songs: List<Song>) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text("${songs.size} songs • ${formatTime(songs.sumOf { (it.duration ?: 0) * 1000L })}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({ Player.playQueue(songs, title = title) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.PlayArrow, null); Text("Play") }
            OutlinedButton({ Player.playQueue(songs, songs.indices.random(), title, shuffle = true) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Shuffle, null); Text("Shuffle") }
            OutlinedButton({ Player.addToQueue(songs) }, enabled = songs.isNotEmpty()) { Text("Add to queue") }
            IconButton({ Downloads.download(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Download, "Download visible songs") }
        }
    }
}

@Composable
private fun PlaylistNameDialog(title: String, initial: String = "", onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    val error = playlistNameError(name)
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), label = { Text("Playlist name") }, singleLine = true,
            isError = name.isNotEmpty() && error != null, supportingText = { Text(error ?: "${name.trim().length}/200") })
    }, confirmButton = { TextButton({ onSave(name.trim()); onDismiss() }, enabled = error == null) { Text("Save") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun LibraryConfirm(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title, maxLines = 3, overflow = TextOverflow.Ellipsis) }, text = { Text(message, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton({ onConfirm(); onDismiss() }) { Text("Confirm", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
fun LibraryScreen() {
    val lib by Stores.library.state.collectAsState()
    var account by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Recent first") }
    var create by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<LocalPlaylist?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var linkImport by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    @Composable
    fun LibraryActions() {
        TextButton({ create = true }) { Text("New playlist") }
        TextButton({
            fileBusy = true
            scope.launch {
                try { imported = readPlaylistFile() }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { message = "Could not import playlist. Check that the file is valid Utaloom JSON." }
                finally { fileBusy = false }
            }
        }, enabled = !fileBusy) { Text(if (fileBusy) "Importing…" else "Import JSON") }
        TextButton({ linkImport = true }) { Text("Import from Spotify / YT Music") }
    }
    @Composable
    fun Header() {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (!account && maxWidth < 600.dp * LocalDensity.current.fontScale) {
                Column {
                    SectionTitle("Your library")
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { LibraryActions() }
                }
            } else SectionTitle("Your library", action = if (account) null else ({ LibraryActions() }))
        }
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(!account, { account = false }, { Text("On this device") })
            FilterChip(account, { account = true }, { Text("YouTube account") })
        }
    }
    if (account) AccountLibrary(Modifier.fillMaxSize(), header = { Header() })
    else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Header() }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ Nav.go(Screen.SongList("Liked songs", "liked")) }) { Text("Liked (${lib.liked.size})") }
                OutlinedButton({ Nav.go(Screen.SongList("Listening history", "history")) }) { Text("History (${lib.history.size})") }
                OutlinedButton({ Nav.go(Screen.SongList("Downloads", "downloads")) }) { Text("Downloads (${lib.downloaded.size})") }
                TextButton({ Nav.go(Screen.SongList("Most played", "top")) }) { Text("Most played") }
            }
        }
        item {
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibrarySelect(filter, listOf("All", "Local playlists", "Saved albums", "Saved artists", "Saved playlists")) { filter = it }
            }
        }
        item { LibrarySearch(query, { query = it }, sort, { sort = it }, listOf("Recent first", "Title")) }
        val match: (String) -> Boolean = { query.isBlank() || it.contains(query.trim(), true) }
        val local = lib.playlists.filter { match(it.name) }.let { if (sort == "Title") it.sortedBy { p -> p.name.lowercase(Locale.ROOT) } else it.reversed() }
        if (filter == "All" || filter == "Local playlists") {
            item { SectionTitle("Local playlists") }
            if (local.isEmpty()) item { Text("No playlists here. Create one or import a JSON file.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(local, key = { "local-${it.id}" }) { p ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { Nav.go(Screen.LocalPlaylist(p.id)) }.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Thumb(p.songs.firstOrNull()?.thumbnail, 56.dp)
                    Column(Modifier.weight(1f)) { Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${p.songs.size} songs", style = MaterialTheme.typography.bodySmall) }
                    Icon(Icons.Default.ChevronRight, null)
                }
            }
        }
        listOf(Triple("Saved albums", "album", lib.savedAlbums), Triple("Saved artists", "artist", lib.savedArtists), Triple("Saved playlists", "playlist", lib.savedPlaylists)).forEach { (title, kind, refs) ->
            if (filter == "All" || filter == title) {
                val shown = refs.filter { match("${it.title} ${it.subtitle.orEmpty()}") }.let { if (sort == "Title") it.sortedBy { r -> r.title.lowercase(Locale.ROOT) } else it }
                item { SectionTitle(title) }
                if (shown.isEmpty()) item { Text("Nothing saved yet.", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(shown, key = { "$kind-${it.id}" }) { SavedLibraryRow(kind, it) }
            }
        }
    }
    if (create) PlaylistNameDialog("New playlist", onDismiss = { create = false }) { Nav.go(Screen.LocalPlaylist(Library.createPlaylist(it))) }
    if (linkImport) LinkImportDialog({ linkImport = false }) { linkImport = false; imported = it.playlist
        val lines = listOfNotNull(it.note, it.missing.takeIf { m -> m.isNotEmpty() }?.let { m -> "${m.size} tracks had no YouTube Music match:\n" + m.take(20).joinToString("\n") + if (m.size > 20) "\n…" else "" })
        if (lines.isNotEmpty()) message = lines.joinToString("\n\n") }
    imported?.let { p -> AlertDialog(onDismissRequest = { imported = null }, title = { Text("Import ${p.name}?") },
        text = { Text("Create a new local playlist with ${p.songs.size} songs. Existing playlists will not be changed.") },
        confirmButton = { TextButton({ Nav.go(Screen.LocalPlaylist(Library.createPlaylist(p.name, p.songs))); imported = null }) { Text("Import") } },
        dismissButton = { TextButton({ imported = null }) { Text("Cancel") } }) }
    message?.let { msg -> AlertDialog(onDismissRequest = { message = null }, text = { Text(msg) }, confirmButton = { TextButton({ message = null }) { Text("OK") } }) }
}

@Composable
private fun SavedLibraryRow(kind: String, ref: SavedRef) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).clickable {
        Nav.go(when (kind) { "album" -> Screen.Album(ref.id); "artist" -> Screen.Artist(ref.id); else -> Screen.Playlist(ref.playlistId ?: ref.id) })
    }.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Thumb(ref.thumbnail, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(ref.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ref.subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        IconButton({ Library.toggleSaved(kind, ref) }) { Icon(Icons.Default.Favorite, "Unsave ${ref.title}", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
private fun AccountLibrary(modifier: Modifier, header: @Composable () -> Unit) {
    val settings by Stores.settings.state.collectAsState()
    var tab by remember { mutableStateOf("Playlists") }
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf("Original order") }
    var entries by remember { mutableStateOf<List<YTItem>>(emptyList()) }
    var continuation by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab, settings.cookie, settings.contentLanguage, settings.contentCountry, refresh) {
        entries = emptyList(); continuation = null; error = null
        if (settings.cookie == null) return@LaunchedEffect
        busy = true
        try {
            val browseId = when (tab) { "Songs" -> "FEmusic_liked_videos"; "Albums" -> "FEmusic_liked_albums"; "Artists" -> "FEmusic_library_corpus_track_artists"; else -> "FEmusic_liked_playlists" }
            val page = withContext(Dispatchers.IO) { YouTube.library(browseId).getOrThrow() }
            entries = page.items.distinctBy { it.id }; continuation = page.continuation
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Could not load your account library. Check your connection or sign in again." }
        finally { busy = false }
    }
    val shown = entries.filter { (!settings.hideExplicit || !it.explicit) && (query.isBlank() || "${it.title} ${it.subtitle()}".contains(query.trim(), true)) }
        .let { if (sort == "Title") it.sortedBy { item -> item.title.lowercase(Locale.ROOT) } else it }
    val songs = shown.filterIsInstance<SongItem>().map { it.toSong() }
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 32.dp)) {
        item { header() }
        if (settings.cookie == null) {
            item { Text("Sign in from Settings to access your YouTube Music library.", Modifier.padding(24.dp)) }
            item { TextButton({ Nav.go(Screen.Settings) }, Modifier.padding(horizontal = 24.dp)) { Text("Open Settings") } }
            return@LazyColumn
        }
        item {
            FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Playlists", "Songs", "Albums", "Artists").forEach { name -> FilterChip(tab == name, { tab = name }, { Text(name) }) }
                IconButton({ refresh++ }, enabled = !busy) { Icon(Icons.Default.Refresh, "Refresh account library") }
            }
        }
        item { LibrarySearch(query, { query = it }, sort, { sort = it }, listOf("Original order", "Title")) }
        items(shown, key = { it.id }) { ItemRow(it, songs, "YouTube $tab") }
        if (shown.isEmpty() && !busy && error == null) item { Text("No items in this collection.", Modifier.padding(24.dp)) }
        if (busy) item { Loading(Modifier.fillMaxWidth().height(80.dp)) }
        error?.let { msg -> item { Column(Modifier.padding(24.dp)) { Text(msg, color = MaterialTheme.colorScheme.error); TextButton({ refresh++ }) { Text("Retry") } } } }
        if (continuation != null && !busy) item {
            TextButton({
                val token = continuation ?: return@TextButton
                val currentTab = tab
                busy = true; error = null
                scope.launch {
                    try {
                        val page = withContext(Dispatchers.IO) { YouTube.libraryContinuation(token).getOrThrow() }
                        if (tab == currentTab) { entries = (entries + page.items).distinctBy { it.id }; continuation = page.continuation?.takeIf { it != token } }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { if (tab == currentTab) error = "Could not load more items. Try again." }
                    finally { if (tab == currentTab) busy = false }
                }
            }, Modifier.padding(horizontal = 24.dp)) { Text("Load more") }
        }
    }
}

@Composable
fun SongListScreen(title: String, kind: String) {
    val lib by Stores.library.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val player by Player.state.collectAsState()
    var query by remember(kind) { mutableStateOf("") }
    var sort by remember(kind) { mutableStateOf("Original order") }
    var clear by remember { mutableStateOf(false) }
    val source = when (kind) {
        "liked" -> lib.liked
        "history" -> lib.history
        "downloads" -> downloadedSongs(lib)
        "top" -> knownSongs(lib).filter { (lib.playCounts[it.id] ?: 0) > 0 }.sortedByDescending { lib.playCounts[it.id] ?: 0 }
        else -> emptyList()
    }
    val shown = visibleSongs(source, query, sort, settings.hideExplicit)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { SongsHeader(title, shown) }
        if (kind == "history") item { TextButton({ clear = true }, Modifier.padding(horizontal = 24.dp), enabled = lib.history.isNotEmpty()) { Text("Clear listening history") } }
        item { LibrarySearch(query, { query = it }, sort, { sort = it }) }
        if (shown.isEmpty()) item { Text(if (query.isBlank()) "No songs here yet." else "No songs match your search.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(shown, key = { _, song -> song.id }) { i, song -> SongRow(song, { Player.playQueue(shown, i, title) }, playing = player.current?.id == song.id,
            trailing = { if (kind == "top") Text("${lib.playCounts[song.id] ?: 0} plays", style = MaterialTheme.typography.bodySmall) }) }
    }
    if (clear) LibraryConfirm("Clear listening history?", "All locally recorded history and play counts will be removed. Liked songs and playlists are kept.", { clear = false }) {
        Stores.library.update { it.copy(history = emptyList(), playCounts = emptyMap()) }
    }
}

@Composable
fun LocalPlaylistScreen(id: String) {
    val lib by Stores.library.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val player by Player.state.collectAsState()
    val playlist = lib.playlists.firstOrNull { it.id == id }
    var query by remember(id) { mutableStateOf("") }
    var sort by remember(id) { mutableStateOf("Original order") }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var remove by remember { mutableStateOf<Song?>(null) }
    var add by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<LocalPlaylist?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var fileBusy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (playlist == null) return EmptyBox("This local playlist no longer exists.")
    val shown = visibleSongs(playlist.songs, query, sort, settings.hideExplicit)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp * LocalDensity.current.fontScale
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { SongsHeader(playlist.name, shown) }
        item {
        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton({ rename = true }) { Text("Rename") }
            TextButton({ add = true }) { Text("Add songs") }
            TextButton({
                fileBusy = true
                scope.launch {
                    try { if (exportPlaylist(playlist)) message = "Playlist exported successfully." }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Export failed. Your original playlist was kept. Check the destination file before retrying." }
                    finally { fileBusy = false }
                }
            }, enabled = !fileBusy) { Text("Export JSON") }
            TextButton({
                fileBusy = true
                scope.launch {
                    try { imported = readPlaylistFile() }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { message = "Import failed. Choose valid Utaloom JSON. No songs were changed." }
                    finally { fileBusy = false }
                }
            }, enabled = !fileBusy) { Text("Import songs") }
            TextButton({ delete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        }
        item { LibrarySearch(query, { query = it }, sort, { sort = it }) }
        if (shown.isEmpty()) item { Text(if (playlist.songs.isEmpty()) "Add songs from your library, search, or import a JSON playlist." else "No songs match the current filters.", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(shown, key = { _, song -> song.id }) { i, song ->
                    val original = playlist.songs.indexOfFirst { it.id == song.id }
                    val reorder = query.isBlank() && sort == "Original order" && (!settings.hideExplicit || playlist.songs.none { it.explicit })
                    SongRow(song, { Player.playQueue(shown, i, playlist.name) }, index = i + 1, playing = player.current?.id == song.id,
                        extraMenu = { close ->
                            if (compact && reorder) {
                                if (original > 0) MenuItem("Move up", Icons.Default.ArrowUpward) { Library.moveInPlaylist(id, original, original - 1); close() }
                                if (original < playlist.songs.lastIndex) MenuItem("Move down", Icons.Default.ArrowDownward) { Library.moveInPlaylist(id, original, original + 1); close() }
                            }
                            MenuItem("Remove from this playlist", Icons.Default.Delete) { remove = song; close() }
                        },
                        trailing = if (compact || !reorder) null else ({
                            IconButton({ Library.moveInPlaylist(id, original, original - 1) }, enabled = original > 0) { Icon(Icons.Default.ArrowUpward, "Move ${song.title} up") }
                            IconButton({ Library.moveInPlaylist(id, original, original + 1) }, enabled = original < playlist.songs.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move ${song.title} down") }
                        }))
                }
        }
    }
    if (rename) PlaylistNameDialog("Rename playlist", playlist.name, { rename = false }) { Library.renamePlaylist(id, it) }
    if (delete) LibraryConfirm("Delete ${playlist.name}?", "The local playlist will be permanently removed. Downloaded files and liked songs are kept. Export first if you need a backup.", { delete = false }) {
        Library.deletePlaylist(id); Nav.back()
    }
    remove?.let { song -> LibraryConfirm("Remove song?", "Remove ${song.title} from ${playlist.name}?", { remove = null }) { Library.removeFromPlaylist(id, song.id) } }
    if (add) AddPlaylistSongsDialog(id) { add = false }
    imported?.let { p -> AlertDialog(onDismissRequest = { imported = null }, title = { Text("Add songs from ${p.name}?") },
        text = { Text("Add ${p.songs.count { s -> playlist.songs.none { it.id == s.id } }} new songs to ${playlist.name}. Existing songs and their order will be kept.") },
        confirmButton = { TextButton({ Library.addToPlaylist(id, p.songs); imported = null }) { Text("Add songs") } },
        dismissButton = { TextButton({ imported = null }) { Text("Cancel") } }) }
    message?.let { msg -> AlertDialog(onDismissRequest = { message = null }, text = { Text(msg) }, confirmButton = { TextButton({ message = null }) { Text("OK") } }) }
}

@Composable
private fun AddPlaylistSongsDialog(id: String, onDismiss: () -> Unit) {
    val lib by Stores.library.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val existing = lib.playlists.firstOrNull { it.id == id }?.songs.orEmpty().map { it.id }.toSet()
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val choices = (knownSongs(lib) + results).distinctBy { it.id }.filter { it.id !in existing && (!settings.hideExplicit || !it.explicit) && (query.isBlank() || "${it.title} ${it.artistText}".contains(query.trim(), true)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add songs") }, text = {
        LazyColumn(Modifier.widthIn(max = 520.dp).fillMaxWidth().heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Find songs") }) }
            item {
            TextButton({
                val q = query.trim()
                busy = true; error = null
                scope.launch {
                    try {
                        Library.addSearch(q)
                        results = withContext(Dispatchers.IO) { YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>().map { it.toSong() } }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { error = "Search failed. Check your connection and try again." }
                    finally { busy = false }
                }
            }, enabled = !busy && query.trim().length in 1..200) { Text(if (busy) "Searching…" else "Search YouTube Music") }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
                if (choices.isEmpty()) item { Text("No matching songs. Search YouTube Music above.", Modifier.padding(12.dp)) }
                items(choices, key = { it.id }) { song ->
                    Row(Modifier.fillMaxWidth().clickable { selected = if (song.id in selected) selected - song.id else selected + song.id }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(song.id in selected, { selected = if (it) selected + song.id else selected - song.id })
                        Column(Modifier.weight(1f)) { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(song.artistText, maxLines = 1, style = MaterialTheme.typography.bodySmall) }
                    }
                }
        }
    }, confirmButton = { TextButton({ Library.addToPlaylist(id, (knownSongs(lib) + results).distinctBy { it.id }.filter { it.id in selected }); onDismiss() }, enabled = selected.isNotEmpty()) { Text("Add ${selected.size} songs") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } })
}

@Composable
private fun LinkImportDialog(onDismiss: () -> Unit, onDone: (RemoteImport) -> Unit) {
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("Import playlist from link") }, text = {
        Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), singleLine = true, enabled = !busy, label = { Text("Spotify or YouTube Music link") })
            Text("Public playlists only. Spotify tracks are matched on YouTube Music, so large playlists take a while.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (busy) Text(progress.ifEmpty { "Loading…" })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        busy = true; error = null; progress = ""
        scope.launch {
            try { onDone(importRemotePlaylist(url) { d, t -> progress = "Matching $d / $t tracks…" }) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = (e as? IllegalArgumentException)?.message ?: "Import failed. Check the link and your connection." }
            finally { busy = false }
        }
    }, enabled = !busy && url.isNotBlank()) { Text(if (busy) "Importing…" else "Import") } },
        dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("Cancel") } })
}

internal fun libraryScreensSelfCheck() {
    com.utaloom.data.playlistImportSelfCheck()
    val song = Song("abc-123", "Title", listOf("Artist"))
    val original = LocalPlaylist("example", "Playlist", listOf(song))
    check(decodePlaylist(playlistJson.encodeToString(LocalPlaylist.serializer(), original)) == original)
    check(playlistNameError("\n") != null && playlistNameError("Playlist") == null)
    check(runCatching { decodePlaylist("{}") }.isFailure)
    check(runCatching { decodePlaylist(playlistJson.encodeToString(LocalPlaylist.serializer(), original.copy(songs = listOf(song, song)))) }.isFailure)
    check(runCatching { decodePlaylist(playlistJson.encodeToString(LocalPlaylist.serializer(), original.copy(songs = listOf(song.copy(id = "../evil"))))) }.isFailure)
    check(visibleSongs(listOf(song, song.copy(id = "explicit", explicit = true)), "artist", "Title", true) == listOf(song))
}
