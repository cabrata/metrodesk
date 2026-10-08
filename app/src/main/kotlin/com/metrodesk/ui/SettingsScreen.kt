package com.metrodesk.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.metrodesk.data.Stores
import com.metrodesk.shared.cookieError
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeLocale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.net.URI
import java.util.Locale

internal fun serverUrlError(value: String): String? {
    if (value.length > 2048 || value.any { it.isWhitespace() || it.isISOControl() }) return "Use a valid WebSocket URL (maximum 2048 characters)"
    val uri = runCatching { URI(value) }.getOrNull() ?: return "Use a valid WebSocket URL"
    return if (uri.scheme !in listOf("wss", "ws") || uri.host.isNullOrBlank() || uri.userInfo != null || uri.fragment != null || uri.port !in -1..65535 || uri.port == 0)
        "Use ws:// or wss:// with a host, without credentials or a fragment" else null
}

internal fun loginCookieError(cookie: String): String? = cookieError(cookie)
    ?: if (cookie.toByteArray(Charsets.UTF_8).size > 16_384) "Cookie is too long (16 KiB maximum)"
    else if (cookie.any { it.isISOControl() }) "Cookie contains a control character"
    else if (cookie.split(';').none { part -> part.trim().let { (it.startsWith("SAPISID=") || it.startsWith("__Secure-3PAPISID=")) && it.substringAfter('=').isNotBlank() } }) "Cookie needs a nonempty SAPISID"
    else null

private suspend fun signInWithCookie(cookie: String) {
    require(loginCookieError(cookie) == null)
    val oldCookie = YouTube.cookie
    val oldVisitor = YouTube.visitorData
    val oldDataSync = YouTube.dataSyncId
    val oldAuthUser = YouTube.authUser
    val oldUseLogin = YouTube.useLoginForBrowse
    var saved = false
    try {
        YouTube.cookie = cookie
        YouTube.dataSyncId = null
        YouTube.authUser = "0"
        YouTube.useLoginForBrowse = true
        val account = withTimeout(30_000) { withContext(Dispatchers.IO) { YouTube.accountInfo().getOrThrow() } }
        currentCoroutineContext().ensureActive()
        check(account.name.isNotBlank())
        Stores.settings.update { it.copy(cookie = cookie, visitorData = YouTube.visitorData, dataSyncId = YouTube.dataSyncId,
            accountName = account.name, accountEmail = account.email, accountAvatar = account.thumbnailUrl) }
        saved = true
    } finally {
        YouTube.useLoginForBrowse = oldUseLogin
        if (!saved) {
            YouTube.cookie = oldCookie
            YouTube.visitorData = oldVisitor
            YouTube.dataSyncId = oldDataSync
            YouTube.authUser = oldAuthUser
        }
    }
}

@Composable
private fun SettingsToggle(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SettingsChoice(title: String, description: String, selected: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box {
            OutlinedButton({ open = true }) { Text(options.firstOrNull { it.first == selected }?.second ?: selected); Icon(Icons.Default.ArrowDropDown, null) }
            DropdownMenu(open, { open = false }) {
                options.forEach { (value, label) -> DropdownMenuItem({ Text(label) }, { onChange(value); open = false }) }
            }
        }
    }
}

@Composable
fun SettingsScreen() {
    val s by Stores.settings.state.collectAsState()
    val lib by Stores.library.state.collectAsState()
    var login by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SectionTitle("Settings") }
        item {
            SectionTitle("YouTube Music account")
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (s.accountAvatar != null) Thumb(s.accountAvatar, 56.dp)
                Column(Modifier.weight(1f)) {
                    Text(s.accountName ?: "Not signed in", style = MaterialTheme.typography.titleMedium)
                    s.accountEmail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text("Cookie login connects your account library. Local likes and playlists stay on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedButton({ login = true }) { Text(if (s.cookie == null) "Sign in" else "Update login") }
                if (s.cookie != null) TextButton({ confirm = "signout" }) { Text("Sign out") }
            }
        }
        item { UpdateRow() }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp)); SectionTitle("Appearance") }
        item { SettingsChoice("Theme", "Follow your system or choose a fixed appearance.", s.darkMode, listOf("system" to "System", "dark" to "Dark", "light" to "Light")) { v -> Stores.settings.update { it.copy(darkMode = v) } } }
        item { SettingsToggle("Dynamic color", "Use the current track artwork for the app accent color.", s.dynamicColor) { v -> Stores.settings.update { it.copy(dynamicColor = v) } } }
        item { SettingsToggle("Minimize to tray", "Keep Metrodesk running when you close its window, if a system tray is available.", s.minimizeToTray) { v -> Stores.settings.update { it.copy(minimizeToTray = v) } } }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                Text("Lyrics text size: ${s.lyricsTextSize} sp", style = MaterialTheme.typography.titleMedium)
                Slider(s.lyricsTextSize.coerceIn(16, 48).toFloat(), { v -> Stores.settings.update { it.copy(lyricsTextSize = v.toInt()) } }, valueRange = 16f..48f, steps = 31)
            }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp)); SectionTitle("Lyrics providers") }
        item { Text("Tried from top to bottom until one has lyrics for the song.", Modifier.padding(horizontal = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val order = s.lyricsProviderOrder
        order.forEachIndexed { i, p ->
            item(key = "lyrics-$p") {
                fun move(to: Int) = Stores.settings.update { it.copy(lyricsOrder = order.toMutableList().apply { add(to, removeAt(i)) }) }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ move(i - 1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move $p up") }
                    IconButton({ move(i + 1) }, enabled = i < order.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move $p down") }
                    Switch(p !in s.lyricsDisabled, { on -> Stores.settings.update { it.copy(lyricsDisabled = if (on) it.lyricsDisabled - p else it.lyricsDisabled + p) } })
                }
            }
        }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp)); SectionTitle("Playback") }
        item { SettingsChoice("Audio quality", "Applies when loading the next track. Downloads use high quality.", s.audioQuality, listOf("AUTO" to "Automatic", "HIGH" to "High", "LOW" to "Low")) { v -> Stores.settings.update { it.copy(audioQuality = v) } } }
        item { SettingsToggle("Volume normalization", "Reduce volume differences between tracks. Applies when loading the next track.", s.normalizeVolume) { v -> Stores.settings.update { it.copy(normalizeVolume = v) } } }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp)); SectionTitle("Content and privacy") }
        item { SettingsToggle("Hide explicit songs", "Filter explicitly marked music from supported browse, search and library views.", s.hideExplicit) { v -> Stores.settings.update { it.copy(hideExplicit = v) } } }
        item { SettingsToggle("Use account for browsing", "Use your signed-in account for supported browse requests.", s.useLoginForBrowse) { v -> YouTube.useLoginForBrowse = v; Stores.settings.update { it.copy(useLoginForBrowse = v) } } }
        item { SettingsToggle("Pause listening history", "Do not record new songs or play counts locally.", s.pauseHistory) { v -> Stores.settings.update { it.copy(pauseHistory = v) } } }
        item { SettingsToggle("Pause search history", "Do not save new searches locally.", s.pauseSearchHistory) { v -> Stores.settings.update { it.copy(pauseSearchHistory = v) } } }
        item {
            Row(Modifier.padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ confirm = "history" }, enabled = lib.history.isNotEmpty() || lib.playCounts.isNotEmpty()) { Text("Clear listening history") }
                TextButton({ confirm = "search" }, enabled = lib.searchHistory.isNotEmpty()) { Text("Clear search history") }
            }
        }
        item { ContentLocaleFields(s.contentLanguage, s.contentCountry) }
        item { HorizontalDivider(Modifier.padding(horizontal = 24.dp)); SectionTitle("Listen Together") }
        item { TogetherServerField(s.ltServerUrl) }
        item { SettingsToggle("Automatically approve joins", "Allow incoming requests into rooms you host without asking.", s.ltAutoApproveJoins) { v -> Stores.settings.update { it.copy(ltAutoApproveJoins = v) } } }
        item { SettingsToggle("Automatically approve suggestions", "Accept guests' song suggestions in rooms you host.", s.ltAutoApproveSuggestions) { v -> Stores.settings.update { it.copy(ltAutoApproveSuggestions = v) } } }
        item { SettingsToggle("Sync room volume", "Share the host's volume with guests and follow room volume updates.", s.ltSyncVolume) { v -> Stores.settings.update { it.copy(ltSyncVolume = v) } } }
    }
    if (login) CookieLoginDialog { login = false }
    confirm?.let { action ->
        val title = when (action) { "signout" -> "Sign out?"; "search" -> "Clear search history?"; else -> "Clear listening history?" }
        val message = when (action) { "signout" -> "Remove your saved cookie and account details from this device. Local playlists, likes and downloads are kept."; "search" -> "Remove all locally saved searches?"; else -> "Remove local listening history and play counts? Liked songs and playlists are kept." }
        AlertDialog(onDismissRequest = { confirm = null }, title = { Text(title) }, text = { Text(message) },
            confirmButton = { TextButton({
                when (action) {
                    "signout" -> {
                        YouTube.cookie = null; YouTube.dataSyncId = null; YouTube.authUser = "0"
                        Stores.settings.update { it.copy(cookie = null, dataSyncId = null, accountName = null, accountEmail = null, accountAvatar = null) }
                    }
                    "search" -> Stores.library.update { it.copy(searchHistory = emptyList()) }
                    else -> Stores.library.update { it.copy(history = emptyList(), playCounts = emptyMap()) }
                }
                confirm = null
            }) { Text("Confirm", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ContentLocaleFields(language: String, country: String) {
    var lang by remember(language) { mutableStateOf(language) }
    var region by remember(country) { mutableStateOf(country) }
    val valid = lang.trim().matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*")) && region.trim().uppercase(Locale.ROOT) in Locale.getISOCountries().toSet()
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Content locale", style = MaterialTheme.typography.titleMedium)
        Text("YouTube content language and country, not the app interface language.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(lang, { lang = it.take(35) }, Modifier.weight(1f), label = { Text("Language (en, id, pt-BR)") }, singleLine = true)
            OutlinedTextField(region, { region = it.take(2) }, Modifier.width(150.dp), label = { Text("Country (US, ID)") }, singleLine = true)
            OutlinedButton({
                val hl = lang.trim()
                val gl = region.trim().uppercase(Locale.ROOT)
                YouTube.locale = YouTubeLocale(gl = gl, hl = hl)
                Stores.settings.update { it.copy(contentLanguage = hl, contentCountry = gl) }
            }, enabled = valid && (lang.trim() != language || region.trim().uppercase(Locale.ROOT) != country)) { Text("Apply") }
        }
        if (!valid) Text("Use a language tag and a two-letter ISO country code.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun TogetherServerField(url: String) {
    var value by remember(url) { mutableStateOf(url) }
    val error = serverUrlError(value.trim())
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Server URL", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value, { value = it }, Modifier.weight(1f), label = { Text("WebSocket URL") }, singleLine = true, isError = error != null)
            OutlinedButton({ Stores.settings.update { it.copy(ltServerUrl = value.trim()) } }, enabled = error == null && value.trim() != url) { Text("Apply") }
        }
        Text(error ?: if (value.trim().startsWith("ws://")) "Unencrypted connection. Prefer wss:// outside a trusted local network." else "Used when connecting to a room. Changing it does not disconnect an active room.",
            style = MaterialTheme.typography.bodySmall, color = if (error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun CookieLoginDialog(onDismiss: () -> Unit) {
    var cookie by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val validation = loginCookieError(cookie)
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!busy) { cookie = ""; onDismiss() } }, title = { Text("Sign in with a cookie") }, text = {
        Column(Modifier.width(540.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("In your browser, sign in to music.youtube.com, open Developer Tools → Network, and copy the Cookie header from a request to that site. Paste only the header value, not 'Cookie:'.")
            Text("Cookies grant access to your account. Never share them. Metrodesk keeps the validated cookie in its local settings file with restricted access; it is not encrypted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(cookie, { cookie = it; error = null }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 180.dp), enabled = !busy,
                label = { Text("Cookie header value") }, visualTransformation = PasswordVisualTransformation(), singleLine = false,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), isError = cookie.isNotEmpty() && validation != null,
                supportingText = { Text(if (busy) "Checking your account…" else validation ?: "${cookie.toByteArray(Charsets.UTF_8).size}/16384 bytes") })
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }, confirmButton = { TextButton({
        val candidate = cookie
        cookie = ""; busy = true; error = null
        scope.launch {
            try { signInWithCookie(candidate); onDismiss() }
            catch (_: TimeoutCancellationException) { error = "Account verification timed out. The previous login was kept. Try again." }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "Could not verify this account. The previous login was kept. Paste a fresh cookie and try again." }
            finally { busy = false }
        }
    }, enabled = !busy && validation == null) { Text(if (busy) "Signing in…" else "Sign in") } },
        dismissButton = { TextButton({ cookie = ""; onDismiss() }, enabled = !busy) { Text("Cancel") } })
}

/** Current version plus a manual "check for updates" against GitHub releases. */
@Composable
private fun UpdateRow() {
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var release by remember { mutableStateOf<com.metrodesk.platform.Updates.Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
    SectionTitle("About")
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Metrodesk ${com.metrodesk.platform.Updates.current}", style = MaterialTheme.typography.titleMedium)
            Text(status ?: "Updates come from GitHub releases.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        release?.let { r -> Button({ com.metrodesk.platform.Updates.open(r.html_url) }) { Text("Download ${r.tag_name}") } }
        OutlinedButton({
            busy = true
            scope.launch {
                val res = withContext(Dispatchers.IO) { runCatching { com.metrodesk.platform.Updates.check() } }
                release = res.getOrNull()
                status = when {
                    res.isFailure -> "Couldn't check for updates: ${res.exceptionOrNull()?.message}"
                    release != null -> "${release!!.tag_name} is available."
                    com.metrodesk.platform.Updates.current == "dev" -> "Development build, update check is off."
                    else -> "You're on the latest version."
                }
                busy = false
            }
        }, enabled = !busy) { Text(if (busy) "Checking…" else "Check for updates") }
    }
}

internal fun settingsScreenSelfCheck() {
    check(serverUrlError("wss://example.org/ws") == null)
    check(serverUrlError("ws://localhost:1234/ws") == null)
    listOf("https://example.org", "wss://name:pass@example.org/ws", "wss://example.org/#frag", "wss://example.org\n", "wss://example.org:0").forEach { check(serverUrlError(it) != null) }
    check(loginCookieError("SAPISID=value; a=b") == null)
    listOf("", "a=b", "SAPISID=", "SAPISID=value\r\n", "SAPISID=" + "x".repeat(16_384), "SAPISID=" + "é".repeat(9000)).forEach { check(loginCookieError(it) != null) }
}

fun screensSelfCheck() {
    libraryScreensSelfCheck()
    settingsScreenSelfCheck()
    println("Library and Settings self-checks passed")
}
