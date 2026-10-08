package com.utaloom.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.utaloom.data.Library
import com.utaloom.data.SavedRef
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.*
import com.utaloom.innertube.pages.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalTime

@Composable
private fun <T> load(key: Any?, loader: suspend () -> Result<T>): State<Result<T>?> = produceState<Result<T>?>(null, key) {
    value = withContext(Dispatchers.IO) { loader() }
}

@Composable
fun HomeScreen() {
    val settings by Stores.settings.state.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val result by load(settings.cookie to refresh) { YouTube.home() }
    val lib by Stores.library.state.collectAsState()
    val page = result?.getOrNull()
    when {
        result == null -> Loading()
        page == null -> ErrorBox(result?.exceptionOrNull()?.message ?: "Couldn't load home") { refresh++ }
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val hour = LocalTime.now().hour
                        Text(when { hour < 12 -> "Good morning"; hour < 18 -> "Good afternoon"; else -> "Good evening" }, style = MaterialTheme.typography.headlineLarge)
                        Text(settings.accountName?.let { "Welcome back, $it" } ?: "Discover your next favorite", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ refresh++ }) { Icon(Icons.Default.Refresh, "Refresh") }
                }
            }
            if (!page.chips.isNullOrEmpty()) item {
                LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(page.chips!!) { chip ->
                        AssistChip(onClick = { chip.endpoint?.let { Nav.go(Screen.Browse(chip.title, it)) } }, label = { Text(chip.title) })
                    }
                }
            }
            if (lib.history.isNotEmpty()) carousel("Listen again", lib.history.take(16).map { it.toSongItem() }, { Nav.go(Screen.SongList("History", "history")) })
            page.sections.forEach { s ->
                carousel(s.title, s.items.filterExplicit(settings.hideExplicit), s.endpoint?.let { ep -> { Nav.go(Screen.Browse(s.title, ep)) } })
            }
        }
    }
}

@Composable
fun ExploreScreen() {
    val result by load(Unit) { YouTube.explore() }
    val charts by load("charts") { YouTube.getChartsPage() }
    val page = result?.getOrNull()
    if (result == null) return Loading()
    if (page == null) return ErrorBox(result?.exceptionOrNull()?.message ?: "Explore unavailable")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Explore", style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(24.dp)) }
        carousel("New releases", page.newReleaseAlbums)
        if (page.moodAndGenres.isNotEmpty()) {
            item { SectionTitle("Moods & genres") }
            item {
                FlowRow(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    page.moodAndGenres.forEach { m ->
                        Surface(onClick = { Nav.go(Screen.Browse(m.title, m.endpoint)) }, shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.width(200.dp).height(52.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(6.dp).fillMaxHeight().background(Color(m.stripeColor)))
                                Text(m.title, Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
        charts?.getOrNull()?.sections?.forEach { s -> carousel(s.title, s.items) }
    }
}

private val SearchFilters = linkedMapOf<String, YouTube.SearchFilter?>(
    "All" to null,
    "Songs" to YouTube.SearchFilter.FILTER_SONG,
    "Videos" to YouTube.SearchFilter.FILTER_VIDEO,
    "Albums" to YouTube.SearchFilter.FILTER_ALBUM,
    "Artists" to YouTube.SearchFilter.FILTER_ARTIST,
    "Playlists" to YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST,
    "Podcasts" to YouTube.SearchFilter.FILTER_PODCAST,
)

@Composable
fun SearchScreen(query: String) {
    var filter by remember(query) { mutableStateOf("All") }
    val settings by Stores.settings.state.collectAsState()
    var all by remember(query, filter) { mutableStateOf<SearchSummaryPage?>(null) }
    var list by remember(query, filter) { mutableStateOf<List<YTItem>>(emptyList()) }
    var continuation by remember(query, filter) { mutableStateOf<String?>(null) }
    var loading by remember(query, filter) { mutableStateOf(true) }
    var error by remember(query, filter) { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    LaunchedEffect(query, filter) {
        loading = true
        Library.addSearch(query)
        if (filter == "All") {
            val r = withContext(Dispatchers.IO) { YouTube.searchSummary(query, !settings.pauseSearchHistory) }
            all = r.getOrNull(); error = r.exceptionOrNull()?.message
        } else {
            val r = withContext(Dispatchers.IO) { YouTube.search(query, SearchFilters[filter]!!, !settings.pauseSearchHistory) }
            list = r.getOrNull()?.items.orEmpty(); continuation = r.getOrNull()?.continuation; error = r.exceptionOrNull()?.message
        }
        loading = false
    }
    Column(Modifier.fillMaxSize()) {
        Text("Results for \"$query\"", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchFilters.keys.toList()) { f -> FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f) }) }
        }
        when {
            loading -> Loading()
            error != null -> ErrorBox(error!!)
            filter == "All" -> {
                val summaries = all?.summaries.orEmpty()
                if (summaries.isEmpty()) EmptyBox("No results", Icons.Default.Search)
                else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                    summaries.forEach { summary ->
                        if (summary.items.all { it is SongItem }) {
                            item { SectionTitle(summary.title) }
                            items(summary.items.filterExplicit(settings.hideExplicit)) { item ->
                                val s = (item as SongItem).toSong()
                                SongRow(s, { Player.playRadio(s) })
                            }
                        } else carousel(summary.title, summary.items.filterExplicit(settings.hideExplicit))
                    }
                }
            }
            else -> {
                val visible = list.filterExplicit(settings.hideExplicit)
                if (visible.isEmpty()) EmptyBox("No results")
                else LazyColumn(Modifier.weight(1f), state, contentPadding = PaddingValues(vertical = 12.dp)) {
                    items(visible) { ItemRow(it) }
                    if (continuation != null) item {
                        Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                            if (more) CircularProgressIndicator(Modifier.size(24.dp))
                            else TextButton(onClick = {
                                more = true
                                scope.launch {
                                    val r = withContext(Dispatchers.IO) { YouTube.searchContinuation(continuation!!, !settings.pauseSearchHistory) }
                                    r.onSuccess { p -> list = list + p.items; continuation = p.continuation }.onFailure { error = it.message }
                                    more = false
                                }
                            }) { Text("Load more") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ItemRow(item: YTItem, songs: List<Song>? = null, title: String? = null) {
    if (item is SongItem) SongRow(item.toSong(), { openItem(item, songs, title) })
    else if (item is EpisodeItem) SongRow(item.asSongItem().toSong(), { openItem(item) })
    else {
        var menu by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(10.dp)).clickable { openItem(item) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Thumb(item.thumbnail, 56.dp, if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                Text(item.subtitle(), maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                ItemMenu(item, menu) { menu = false }
            }
        }
    }
}

@Composable
fun AlbumScreen(id: String) {
    val result by load(id) { YouTube.album(id) }
    val page = result?.getOrNull()
    val player by Player.state.collectAsState()
    val lib by Stores.library.state.collectAsState()
    if (result == null) return Loading()
    if (page == null) return ErrorBox(result?.exceptionOrNull()?.message ?: "Album unavailable")
    val a = page.album
    val songs = page.songs.map { it.toSong() }
    val saved = lib.savedAlbums.any { it.id == a.browseId }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            CollectionHeader(a.title, a.subtitle(), a.thumbnail, songs, saved = saved,
                onSave = { Library.toggleSaved("album", SavedRef(a.browseId, a.title, a.artists?.joinToString(", ") { it.name }, a.thumbnail, a.playlistId)) })
        }
        itemsIndexed(songs) { i, song -> SongRow(song, { Player.playQueue(songs, i, a.title) }, index = i + 1, showThumb = false, playing = player.current?.id == song.id) }
        carousel("Other versions", page.otherVersions)
    }
}

@Composable
fun CollectionHeader(
    title: String,
    subtitle: String,
    thumbnail: String?,
    songs: List<Song>,
    description: String? = null,
    saved: Boolean = false,
    onSave: (() -> Unit)? = null,
    extra: (@Composable () -> Unit)? = null,
) {
    @Composable
    fun Details(modifier: Modifier) {
        Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, maxLines = 3)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${songs.size} songs • ${formatTime(songs.sumOf { (it.duration ?: 0) * 1000L })}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!description.isNullOrBlank()) Text(description, maxLines = 3, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Player.playQueue(songs, title = title) }, enabled = songs.isNotEmpty()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Play")
                }
                FilledTonalIconButton({ Player.playQueue(songs.shuffled(), title = title) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Shuffle, "Shuffle") }
                if (onSave != null) IconButton(onSave) { Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Save to library", tint = if (saved) MaterialTheme.colorScheme.primary else LocalContentColor.current) }
                IconButton({ Downloads.download(songs) }, enabled = songs.isNotEmpty()) { Icon(Icons.Default.Download, "Download all") }
                extra?.invoke()
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp)) {
        if (maxWidth < 552.dp) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Thumb(thumbnail, 196.dp, RoundedCornerShape(16.dp))
            Details(Modifier.fillMaxWidth())
        } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(thumbnail, 196.dp, RoundedCornerShape(16.dp))
            Details(Modifier.weight(1f))
        }
    }
}

@Composable
fun PlaylistScreen(id: String) {
    var page by remember(id) { mutableStateOf<PlaylistPage?>(null) }
    var songs by remember(id) { mutableStateOf<List<Song>>(emptyList()) }
    var cont by remember(id) { mutableStateOf<String?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    var more by remember(id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val lib by Stores.library.state.collectAsState()
    val player by Player.state.collectAsState()
    LaunchedEffect(id) {
        val r = withContext(Dispatchers.IO) { YouTube.playlist(id) }
        page = r.getOrNull()
        songs = page?.songs?.map { it.toSong() }.orEmpty()
        cont = page?.songsContinuation ?: page?.continuation
        error = r.exceptionOrNull()?.message
    }
    if (error != null) return ErrorBox(error!!)
    val p = page?.playlist ?: return Loading()
    val saved = lib.savedPlaylists.any { it.id == p.id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            CollectionHeader(p.title, p.subtitle(), p.thumbnail, songs, p.description, saved,
                onSave = { Library.toggleSaved("playlist", SavedRef(p.id, p.title, p.author?.name, p.thumbnail)) },
                extra = { p.radioEndpoint?.let { ep -> IconButton({ Player.startRadio(ep, p.title) }) { Icon(Icons.Default.Radio, "Radio") } } })
        }
        itemsIndexed(songs) { i, s -> SongRow(s, { Player.playQueue(songs, i, p.title) }, index = i + 1, playing = player.current?.id == s.id) }
        if (cont != null) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                if (more) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton({
                    more = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { YouTube.playlistContinuation(cont!!) }
                        r.onSuccess { next -> songs += next.songs.map { it.toSong() }; cont = next.continuation }.onFailure { error = it.message }
                        more = false
                    }
                }) { Text("Load more") }
            }
        }
    }
}

@Composable
fun ArtistScreen(id: String) {
    val result by load(id) { YouTube.artist(id) }
    val a = result?.getOrNull()
    val lib by Stores.library.state.collectAsState()
    if (result == null) return Loading()
    if (a == null) return ErrorBox(result?.exceptionOrNull()?.message ?: "Artist unavailable")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item {
            @Composable
            fun ArtistDetails(modifier: Modifier) {
                Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(a.artist.title, style = MaterialTheme.typography.headlineLarge)
                    listOfNotNull(a.subscriberCountText, a.monthlyListenerCount).joinToString(" • ").takeIf { it.isNotBlank() }?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        a.artist.playEndpoint?.let { ep -> Button({ Player.startRadio(ep, a.artist.title) }) { Icon(Icons.Default.PlayArrow, null); Text("Play") } }
                        a.artist.shuffleEndpoint?.let { ep -> FilledTonalIconButton({ Player.startRadio(ep, a.artist.title) }) { Icon(Icons.Default.Shuffle, "Shuffle") } }
                        a.artist.radioEndpoint?.let { ep -> IconButton({ Player.startRadio(ep, a.artist.title) }) { Icon(Icons.Default.Radio, "Radio") } }
                        val saved = lib.savedArtists.any { it.id == a.artist.id }
                        IconButton({ Library.toggleSaved("artist", SavedRef(a.artist.id, a.artist.title, null, a.artist.thumbnail)) }) { Icon(if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Save artist") }
                    }
                }
            }
            BoxWithConstraints(Modifier.fillMaxWidth().padding(24.dp)) {
                if (maxWidth < 552.dp) Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Thumb(a.artist.thumbnail, 200.dp, CircleShape)
                    ArtistDetails(Modifier.fillMaxWidth())
                } else Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Thumb(a.artist.thumbnail, 200.dp, CircleShape)
                    ArtistDetails(Modifier.weight(1f))
                }
            }
        }
        a.sections.forEach { s -> carousel(s.title, s.items, s.moreEndpoint?.let { ep -> { Nav.go(Screen.ArtistItems(s.title, ep)) } }) }
        if (!a.description.isNullOrBlank()) item {
            Column(Modifier.padding(24.dp)) {
                Text("About", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(a.description!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun BrowseScreen(title: String, endpoint: BrowseEndpoint) {
    val result by load(endpoint) { YouTube.browse(endpoint.browseId, endpoint.params) }
    val page = result?.getOrNull()
    if (result == null) return Loading()
    if (page == null) return ErrorBox(result?.exceptionOrNull()?.message ?: "Unavailable")
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text(page.title ?: title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(24.dp)) }
        page.items.forEachIndexed { i, section ->
            if (section.items.all { it is SongItem }) {
                section.title?.let { title -> item { SectionTitle(title) } }
                val songs = section.items.filterIsInstance<SongItem>().map { it.toSong() }
                items(songs) { s -> SongRow(s, { Player.playQueue(songs, songs.indexOf(s), title) }) }
            } else carousel(section.title ?: "Section ${i + 1}", section.items)
        }
    }
}

@Composable
fun ArtistItemsScreen(title: String, endpoint: BrowseEndpoint) {
    var list by remember(endpoint) { mutableStateOf<List<YTItem>>(emptyList()) }
    var cont by remember(endpoint) { mutableStateOf<String?>(null) }
    var loaded by remember(endpoint) { mutableStateOf(false) }
    var error by remember(endpoint) { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(endpoint) {
        withContext(Dispatchers.IO) { YouTube.artistItems(endpoint) }
            .onSuccess { list = it.items; cont = it.continuation }
            .onFailure { error = it.message }
        loaded = true
    }
    if (!loaded) return Loading()
    if (error != null) return ErrorBox(error!!)
    val songs = list.filterIsInstance<SongItem>().map { it.toSong() }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Text(title, style = MaterialTheme.typography.headlineLarge, modifier = Modifier.padding(24.dp)) }
        items(list) { ItemRow(it, songs, title) }
        if (cont != null) item {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                if (more) CircularProgressIndicator(Modifier.size(24.dp))
                else TextButton({
                    more = true
                    scope.launch {
                        withContext(Dispatchers.IO) { YouTube.artistItemsContinuation(cont!!) }
                            .onSuccess { list += it.items; cont = it.continuation }
                        more = false
                    }
                }) { Text("Load more") }
            }
        }
    }
}
