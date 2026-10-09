package com.utaloom.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import com.utaloom.data.Stores
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.YouTubeLocale
import com.utaloom.platform.artworkBitmap
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.together.ListenTogether
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Restore the shared YouTube session after the platform has initialized its data directory. */
fun applySession() {
    val s = Stores.settings.value
    YouTube.locale = YouTubeLocale(gl = s.contentCountry, hl = s.contentLanguage)
    YouTube.cookie = s.cookie
    YouTube.visitorData = s.visitorData
    YouTube.dataSyncId = s.dataSyncId
    YouTube.useLoginForBrowse = s.useLoginForBrowse
    if (s.visitorData == null) Thread {
        kotlinx.coroutines.runBlocking { YouTube.visitorData() }.onSuccess { v ->
            YouTube.visitorData = v
            Stores.settings.update { it.copy(visitorData = v) }
        }
    }.start()
}

private enum class Panel { NONE, QUEUE, LYRICS }

@Composable
fun UtaloomApp(vlcError: String? = null) {
    val settings by Stores.settings.state.collectAsState()
    val player by Player.state.collectAsState()
    val lt by ListenTogether.state.collectAsState()
    val downloadErrors by Downloads.errors.collectAsState()
    val dark = when (settings.darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var seed by remember { mutableStateOf(DefaultSeed) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var fullPage by rememberSaveable { mutableStateOf(PlayerPage.NOW_PLAYING) }
    var panel by rememberSaveable { mutableStateOf(Panel.NONE) }
    val snackbar = remember { SnackbarHostState() }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0

    LaunchedEffect(player.current) {
        if (player.current == null) { fullPlayer = false; panel = Panel.NONE }
    }

    setSingletonImageLoaderFactory { ctx -> ImageLoader.Builder(ctx).components { add(KtorNetworkFetcherFactory()) }.build() }

    val ctx = LocalPlatformContext.current
    LaunchedEffect(player.current?.thumbnail, settings.dynamicColor) {
        val url = player.current?.thumbnail
        seed = if (!settings.dynamicColor || url == null) DefaultSeed else withContext(Dispatchers.IO) {
            runCatching {
                val img = coil3.SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(hiRes(url, 120)).build()).image
                img?.let(::artworkBitmap)?.let(::extractSeed)
            }.getOrNull()
        } ?: DefaultSeed
    }
    LaunchedEffect(player.error) { player.error?.let { snackbar.showSnackbar(it); Player.clearError() } }
    LaunchedEffect(lt.message) { lt.message?.let { snackbar.showSnackbar(it); ListenTogether.clearMessage() } }
    LaunchedEffect(downloadErrors) {
        downloadErrors.values.firstOrNull()?.let { Downloads.clearErrors(); snackbar.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        if (vlcError != null) snackbar.showSnackbar(vlcError, duration = SnackbarDuration.Indefinite)
    }
    LaunchedEffect(Unit) {
        val update = withContext(Dispatchers.IO) { runCatching { com.utaloom.platform.Updates.check() }.getOrNull() } ?: return@LaunchedEffect
        val r = snackbar.showSnackbar("Utaloom ${update.tag_name} is available (you have ${com.utaloom.platform.Updates.current})", actionLabel = "Download", withDismissAction = true, duration = SnackbarDuration.Indefinite)
        if (r == SnackbarResult.ActionPerformed) com.utaloom.platform.Updates.open(update.html_url)
    }

    UtaloomTheme(seed, dark) {
        Surface(color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val compact = maxWidth < 840.dp
                val panelWidth = minOf(380.dp, maxWidth * 0.4f)
                LaunchedEffect(compact) {
                    if (compact && panel != Panel.NONE) {
                        fullPage = if (panel == Panel.QUEUE) PlayerPage.QUEUE else PlayerPage.LYRICS
                        panel = Panel.NONE
                        fullPlayer = true
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        if (!compact) Sidebar()
                        val contentModifier = if (compact) Modifier else Modifier.padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(20.dp))
                        Column(Modifier.weight(1f).fillMaxHeight().then(contentModifier).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            TopBar(compact)
                            Box(Modifier.weight(1f)) { Content() }
                        }
                        if (!compact) AnimatedVisibility(panel != Panel.NONE) {
                            Box(Modifier.width(panelWidth).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                                if (panel == Panel.QUEUE) QueuePanel() else LyricsPanel()
                            }
                        }
                    }
                    if (player.current != null && (!compact || !keyboardVisible)) {
                        if (compact) MobilePlayerBar(onOpenFull = {
                            focus.clearFocus(); keyboard?.hide()
                            fullPage = PlayerPage.NOW_PLAYING; fullPlayer = true
                        })
                        else PlayerBar(
                            onOpenFull = { fullPage = PlayerPage.NOW_PLAYING; fullPlayer = true },
                            onOpenQueue = { panel = if (panel == Panel.QUEUE) Panel.NONE else Panel.QUEUE },
                            onOpenLyrics = { panel = if (panel == Panel.LYRICS) Panel.NONE else Panel.LYRICS },
                        )
                    }
                    if (compact && !keyboardVisible) BottomNavigation()
                }
                AnimatedVisibility(fullPlayer && player.current != null) {
                    FullPlayer(onClose = { fullPlayer = false }, compact = compact, initialPage = fullPage)
                }
                val bottom = if (fullPlayer || keyboardVisible) 16.dp else if (compact) {
                    if (player.current != null) 152.dp else 88.dp
                } else if (player.current != null) 96.dp else 16.dp
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottom))
            }
        }
    }
}

@Composable
private fun Sidebar() {
    val current = Nav.stack.first()
    val lt by ListenTogether.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    NavigationRail(Modifier.fillMaxHeight().padding(vertical = 8.dp), containerColor = Color.Transparent, windowInsets = WindowInsets(0, 0, 0, 0), header = {
        Icon(Icons.Default.MusicNote, "Utaloom", Modifier.size(44.dp).padding(4.dp), tint = MaterialTheme.colorScheme.primary)
    }) {
        Spacer(Modifier.height(12.dp))
        RailItem("Home", Icons.Default.Home, current == Screen.Home) { Nav.root(Screen.Home) }
        RailItem("Explore", Icons.Default.Explore, current == Screen.Explore) { Nav.root(Screen.Explore) }
        RailItem("Library", Icons.Default.LibraryMusic, current == Screen.Library) { Nav.root(Screen.Library) }
        RailItem("Together", Icons.Default.Groups, current == Screen.Together, badge = lt.joinRequests.size + lt.suggestions.size) { Nav.root(Screen.Together) }
        Spacer(Modifier.weight(1f))
        settings.accountAvatar?.let { Thumb(it, 32.dp, CircleShape, Modifier.padding(bottom = 8.dp)) }
        RailItem("Settings", Icons.Default.Settings, current == Screen.Settings) { Nav.root(Screen.Settings) }
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    NavigationRailItem(selected, onClick, icon = {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) }
    }, label = { Text(label) })
}

@Composable
private fun BottomNavigation() {
    val current = Nav.stack.first()
    val lt by ListenTogether.state.collectAsState()
    NavigationBar(windowInsets = WindowInsets(0, 0, 0, 0)) {
        listOf(
            Triple(Screen.Home, "Home", Icons.Default.Home),
            Triple(Screen.Explore, "Explore", Icons.Default.Explore),
            Triple(Screen.Library, "Library", Icons.Default.LibraryMusic),
            Triple(Screen.Together, "Together", Icons.Default.Groups),
            Triple(Screen.Settings, "Settings", Icons.Default.Settings),
        ).forEach { (screen, label, icon) ->
            NavigationBarItem(current == screen, onClick = { Nav.root(screen) }, icon = {
                val badge = if (screen == Screen.Together) lt.joinRequests.size + lt.suggestions.size else 0
                BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) }
            }, label = { Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) }, alwaysShowLabel = current == screen)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar(compact: Boolean) {
    var query by rememberSaveable { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }
    val lib by Stores.library.state.collectAsState()
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        suggestions = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query) }.getOrNull()?.queries.orEmpty().take(8)
    }
    fun submit(q: String) {
        val trimmed = q.trim()
        if (trimmed.isEmpty()) return
        query = trimmed
        focused = false
        keyboard?.hide()
        focus.clearFocus()
        Nav.go(Screen.Search(trimmed))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = if (compact) 8.dp else 16.dp, vertical = if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!compact || Nav.stack.size > 1) IconButton({ Nav.back() }, enabled = Nav.stack.size > 1) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        BoxWithConstraints(Modifier.widthIn(max = 640.dp).weight(1f)) {
            val searchWidth = maxWidth
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; focused = true },
                placeholder = { Text(if (compact) "Search music" else "Search songs, albums, artists, podcasts") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(query) }),
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submit(query); true }
                    else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { focused = false; keyboard?.hide(); focus.clearFocus(); true } else false
                }.onFocusChanged { focused = it.isFocused },
            )
            val shown = if (query.isBlank()) lib.searchHistory.take(8) else suggestions
            val menuModifier = if (compact) Modifier.width(searchWidth) else Modifier.widthIn(min = 400.dp)
            DropdownMenu(focused && shown.isNotEmpty(), { focused = false }, properties = PopupProperties(focusable = false), modifier = menuModifier) {
                shown.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s) },
                        leadingIcon = { Icon(if (query.isBlank()) Icons.Default.History else Icons.Default.Search, null) },
                        trailingIcon = if (query.isBlank()) ({ IconButton({ com.utaloom.data.Library.removeSearch(s) }) { Icon(Icons.Default.Close, "Remove") } }) else null,
                        onClick = { submit(s) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Content() {
    when (val s = Nav.current) {
        Screen.Home -> HomeScreen()
        Screen.Explore -> ExploreScreen()
        Screen.Library -> LibraryScreen()
        Screen.Together -> TogetherScreen()
        Screen.Settings -> SettingsScreen()
        is Screen.Search -> SearchScreen(s.query)
        is Screen.Album -> AlbumScreen(s.browseId)
        is Screen.Artist -> ArtistScreen(s.browseId)
        is Screen.Playlist -> PlaylistScreen(s.playlistId)
        is Screen.LocalPlaylist -> LocalPlaylistScreen(s.id)
        is Screen.Browse -> BrowseScreen(s.title, s.endpoint)
        is Screen.ArtistItems -> ArtistItemsScreen(s.title, s.endpoint)
        is Screen.SongList -> SongListScreen(s.title, s.kind)
    }
}
