package com.utaloom

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import coil3.ImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.toBitmap
import com.utaloom.data.Stores
import com.utaloom.platform.Mpris
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import com.utaloom.playback.Downloads
import com.utaloom.together.ListenTogether
import com.utaloom.ui.*
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.Image

fun main() {
    applySession()
    val vlcError = NativeLibs.discoverVlc()
    if (vlcError == null) Player.init() else System.err.println(vlcError)
    Runtime.getRuntime().addShutdownHook(Thread { Mpris.stop(); Player.release(); Stores.flushAll() })
    application {
        var visible by remember { mutableStateOf(true) }
        val settings by Stores.settings.state.collectAsState()
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val player by Player.state.collectAsState()
        val icon = painterResource("icon.png")
        fun quit() { ListenTogether.leaveRoom(); Mpris.stop(); Player.release(); Stores.flushAll(); exitApplication() }
        DisposableEffect(Unit) {
            Mpris.start(
                onRaise = { javax.swing.SwingUtilities.invokeLater { visible = true } },
                onQuit = { javax.swing.SwingUtilities.invokeLater { quit() } },
            )
            onDispose { Mpris.stop() }
        }

        if (isTraySupported) Tray(icon, tooltip = player.current?.let { "${it.title} • ${it.artistText}" } ?: "Utaloom", onAction = { visible = true }, menu = {
            Item(if (player.isPlaying) "Pause" else "Play", onClick = Player::playPause)
            Item("Next", onClick = { Player.next() })
            Item("Previous", onClick = Player::previous)
            Separator()
            Item("Show Utaloom", onClick = { visible = true })
            Item("Quit", onClick = ::quit)
        })

        Window(
            onCloseRequest = { if (isTraySupported && settings.minimizeToTray && player.current != null) visible = false else quit() },
            visible = visible,
            state = windowState,
            title = player.current?.let { "${it.title} • ${it.artistText} - Utaloom" } ?: "Utaloom",
            icon = icon,
            onPreviewKeyEvent = ::shortcut,
        ) {
            window.minimumSize = java.awt.Dimension(900, 600)
            App(vlcError)
        }
    }
}

private fun applySession() {
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

/** Global media shortcuts. Ignored while typing in a text field (those consume the events first). */
private fun shortcut(e: KeyEvent): Boolean {
    if (e.type != KeyEventType.KeyDown) return false
    val ctrl = e.isCtrlPressed || e.isMetaPressed
    when {
        e.key == Key.MediaPlayPause -> Player.playPause()
        e.key == Key.MediaNext -> Player.next()
        e.key == Key.MediaPrevious -> Player.previous()
        ctrl && e.key == Key.Spacebar -> Player.playPause()
        ctrl && e.key == Key.DirectionRight -> Player.next()
        ctrl && e.key == Key.DirectionLeft -> Player.previous()
        ctrl && e.key == Key.DirectionUp -> Player.setVolume(Player.state.value.volume + 5)
        ctrl && e.key == Key.DirectionDown -> Player.setVolume(Player.state.value.volume - 5)
        ctrl && e.key == Key.L -> Player.state.value.current?.let(com.utaloom.data.Library::toggleLike)
        e.isAltPressed && e.key == Key.DirectionLeft -> Nav.back()
        else -> return false
    }
    return true
}

private enum class Panel { NONE, QUEUE, LYRICS }

@Composable
private fun App(vlcError: String?) {
    val settings by Stores.settings.state.collectAsState()
    val player by Player.state.collectAsState()
    val lt by ListenTogether.state.collectAsState()
    val downloadErrors by Downloads.errors.collectAsState()
    val dark = when (settings.darkMode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    var seed by remember { mutableStateOf(DefaultSeed) }
    var fullPlayer by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf(Panel.NONE) }
    val snackbar = remember { SnackbarHostState() }

    setSingletonImageLoaderFactory { ctx -> ImageLoader.Builder(ctx).components { add(KtorNetworkFetcherFactory()) }.build() }

    val ctx = LocalPlatformContext.current
    LaunchedEffect(player.current?.thumbnail, settings.dynamicColor) {
        val url = player.current?.thumbnail
        seed = if (!settings.dynamicColor || url == null) DefaultSeed else withContext(Dispatchers.IO) {
            runCatching {
                val img = coil3.SingletonImageLoader.get(ctx).execute(ImageRequest.Builder(ctx).data(hiRes(url, 120)).build()).image
                img?.toBitmap()?.asComposeImageBitmap()?.let(::extractSeed)
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
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f)) {
                        Sidebar()
                        Column(Modifier.weight(1f).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                            TopBar()
                            Box(Modifier.weight(1f)) { Content() }
                        }
                        AnimatedVisibility(panel != Panel.NONE) {
                            Box(Modifier.width(380.dp).fillMaxHeight().padding(top = 8.dp, end = 8.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                                if (panel == Panel.QUEUE) QueuePanel() else LyricsPanel()
                            }
                        }
                    }
                    if (player.current != null) PlayerBar(
                        onOpenFull = { fullPlayer = true },
                        onOpenQueue = { panel = if (panel == Panel.QUEUE) Panel.NONE else Panel.QUEUE },
                        onOpenLyrics = { panel = if (panel == Panel.LYRICS) Panel.NONE else Panel.LYRICS },
                    )
                }
                AnimatedVisibility(fullPlayer && player.current != null) { FullPlayer(onClose = { fullPlayer = false }) }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp))
            }
        }
    }
}

@Composable
private fun Sidebar() {
    val current = Nav.stack.first()
    val lt by ListenTogether.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    NavigationRail(Modifier.fillMaxHeight().padding(vertical = 8.dp), containerColor = Color.Transparent, header = {
        Image(painterResource("icon.png"), "Utaloom", Modifier.size(44.dp).padding(4.dp))
    }) {
        Spacer(Modifier.height(12.dp))
        RailItem("Home", Icons.Default.Home, current == Screen.Home) { Nav.root(Screen.Home) }
        RailItem("Explore", Icons.Default.Explore, current == Screen.Explore) { Nav.root(Screen.Explore) }
        RailItem("Library", Icons.Default.LibraryMusic, current == Screen.Library) { Nav.root(Screen.Library) }
        RailItem("Together", Icons.Default.Groups, current == Screen.Together, badge = lt.joinRequests.size + lt.suggestions.size) { Nav.root(Screen.Together) }
        Spacer(Modifier.weight(1f))
        settings.accountAvatar?.let { Thumb(it, 32.dp, androidx.compose.foundation.shape.CircleShape, Modifier.padding(bottom = 8.dp)) }
        RailItem("Settings", Icons.Default.Settings, current == Screen.Settings) { Nav.root(Screen.Settings) }
    }
}

@Composable
private fun RailItem(label: String, icon: ImageVector, selected: Boolean, badge: Int = 0, onClick: () -> Unit) {
    NavigationRailItem(selected, onClick, icon = {
        BadgedBox(badge = { if (badge > 0) Badge { Text("$badge") } }) { Icon(icon, label) }
    }, label = { Text(label) })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopBar() {
    var query by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var focused by remember { mutableStateOf(false) }
    val lib by Stores.library.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(query) {
        if (query.isBlank()) { suggestions = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(250)
        suggestions = withContext(Dispatchers.IO) { YouTube.searchSuggestions(query) }.getOrNull()?.queries.orEmpty().take(8)
    }
    fun submit(q: String) {
        if (q.isBlank()) return
        query = q
        focused = false
        Nav.go(Screen.Search(q.trim()))
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton({ Nav.back() }, enabled = Nav.stack.size > 1) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Box(Modifier.widthIn(max = 640.dp).weight(1f)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; focused = true },
                placeholder = { Text("Search songs, albums, artists, podcasts") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, "Clear") } },
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.Enter) { submit(query); true }
                    else if (it.type == KeyEventType.KeyDown && it.key == Key.Escape) { focused = false; true } else false
                }.onFocusChanged { if (it.isFocused) focused = true },
            )
            val shown = if (query.isBlank()) lib.searchHistory.take(8) else suggestions
            DropdownMenu(focused && shown.isNotEmpty(), { focused = false }, properties = PopupProperties(focusable = false), modifier = Modifier.widthIn(min = 400.dp)) {
                shown.forEach { s ->
                    DropdownMenuItem(
                        text = { Text(s) },
                        leadingIcon = { Icon(if (query.isBlank()) Icons.Default.History else Icons.Default.Search, null) },
                        trailingIcon = if (query.isBlank()) ({ IconButton({ com.utaloom.data.Library.removeSearch(s) }) { Icon(Icons.Default.Close, "Remove") } }) else null,
                        onClick = { scope.launch { submit(s) } },
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
