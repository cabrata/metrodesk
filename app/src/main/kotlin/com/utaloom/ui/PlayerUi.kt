package com.utaloom.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import com.utaloom.shared.LyricLine
import com.utaloom.shared.lyricWordGroups
import com.utaloom.shared.lyricItems
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameNanos
import com.utaloom.shared.LyricWord
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role as SemanticRole
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.utaloom.data.Library
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.lyrics.LyricsRepository
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerState
import com.utaloom.playback.RepeatMode
import com.utaloom.shared.Lyrics
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** True when Listen Together makes us a guest: transport controls are host-only. */
@Composable
private fun isGuest(): Boolean = ListenTogether.state.collectAsState().value.role == Role.GUEST

/** Seek slider that only commits on release so dragging doesn't spam seeks. */
@Composable
private fun SeekBar(s: PlayerState, enabled: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    var dragging by remember(s.current?.id) { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val dur = s.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (s.positionMs.toFloat() / dur).coerceIn(0f, 1f)
    Column(modifier) {
        Slider(
            value = value,
            onValueChange = { dragging = true; dragValue = it },
            onValueChangeFinished = { if (enabled && !Player.remoteControlled) Player.seekTo((dragValue * dur).toLong()); dragging = false },
            enabled = enabled && s.current != null && s.durationMs > 0,
            modifier = Modifier.height(if (compact) 48.dp else 24.dp).semantics { contentDescription = "Playback position" },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(if (dragging) (dragValue * dur).toLong() else s.positionMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(s.durationMs), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Transport(s: PlayerState, guest: Boolean, big: Boolean, compact: Boolean = false) {
    val size = if (big) 64.dp else 44.dp
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 0.dp else if (big) 12.dp else 4.dp)) {
        IconButton(Player::toggleShuffle, enabled = !guest) {
            Icon(Icons.Default.Shuffle, "Shuffle", tint = if (s.shuffle) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(Player::previous, enabled = !guest && s.current != null) { Icon(Icons.Default.SkipPrevious, "Previous") }
        FilledIconButton(Player::playPause, Modifier.size(size), enabled = !guest && s.current != null) {
            when {
                s.isBuffering -> CircularProgressIndicator(Modifier.size(size / 2), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                s.isPlaying -> Icon(Icons.Default.Pause, "Pause", Modifier.size(size / 2))
                else -> Icon(Icons.Default.PlayArrow, "Play", Modifier.size(size / 2))
            }
        }
        IconButton({ Player.next() }, enabled = !guest && s.hasNext) { Icon(Icons.Default.SkipNext, "Next") }
        IconButton(Player::cycleRepeat, enabled = !guest) {
            Icon(
                if (s.repeat == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat, "Repeat: ${s.repeat.name.lowercase()}",
                tint = if (s.repeat != RepeatMode.OFF) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LikeButton(song: Song) {
    val lib by Stores.library.state.collectAsState()
    val liked = lib.liked.any { it.id == song.id }
    IconButton({ Library.toggleLike(song) }) {
        Icon(if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder, if (liked) "Unlike" else "Like",
            tint = if (liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DownloadButton(song: Song) {
    val progress by Downloads.progress.collectAsState()
    val lib by Stores.library.state.collectAsState()
    val p = progress[song.id]
    val done = remember(lib.downloaded, song.id) { Downloads.isDownloaded(song.id) }
    when {
        p != null -> Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(progress = { p }, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
        }
        done -> IconButton({ Downloads.delete(song.id) }) { Icon(Icons.Default.DownloadDone, "Remove download", tint = MaterialTheme.colorScheme.primary) }
        else -> IconButton({ Downloads.download(listOf(song)) }) { Icon(Icons.Default.Download, "Download") }
    }
}

@Composable
private fun VolumeControl(s: PlayerState) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(Player::toggleMute) {
            Icon(if (s.muted || s.volume == 0) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, if (s.muted) "Unmute" else "Mute")
        }
        Slider(
            value = if (s.muted) 0f else s.volume / 100f,
            onValueChange = { Player.setVolume((it * 100).toInt()) },
            modifier = Modifier.width(110.dp).height(24.dp),
        )
    }
}

@Composable
private fun SleepTimerButton(s: PlayerState, guest: Boolean) {
    var open by remember { mutableStateOf(false) }
    val remaining by produceState<Long?>(null, s.sleepTimerEndsAt) {
        while (true) {
            value = s.sleepTimerEndsAt?.let { (it - System.currentTimeMillis()).coerceAtLeast(0) }
            if (value == null) break
            delay(1000)
        }
    }
    Box {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton({ open = true }, enabled = !guest || s.sleepTimerEndsAt != null) {
                Icon(Icons.Default.Bedtime, "Sleep timer", tint = if (s.sleepTimerEndsAt != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            remaining?.let { Text(formatTime(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
        }
        DropdownMenu(open, { open = false }) {
            if (!guest) listOf(5, 10, 15, 30, 45, 60, 90).forEach { m ->
                MenuItem("$m minutes", Icons.Default.Bedtime) { Player.setSleepTimer(m); open = false }
            }
            if (s.sleepTimerEndsAt != null) MenuItem("Cancel timer", Icons.Default.Close) { Player.setSleepTimer(null); open = false }
        }
    }
}

@Composable
private fun GuestBadge() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    Icon(Icons.Default.Groups, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.tertiary)
    Text("Host controls playback", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
}

/** Bottom mini player. */
@Composable
fun PlayerBar(onOpenFull: () -> Unit, onOpenQueue: () -> Unit, onOpenLyrics: () -> Unit) {
    val s by Player.state.collectAsState()
    val guest = isGuest()
    val song = s.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth()) {
            s.error?.let { err ->
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    TextButton(Player::clearError) { Text("Dismiss") }
                }
            }
            Row(Modifier.fillMaxWidth().height(88.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(enabled = song != null, onClick = onOpenFull).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Thumb(song?.thumbnail, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(song?.title ?: "Nothing playing", maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(song?.artistText.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (guest) GuestBadge()
                    }
                    if (song != null) LikeButton(song)
                }
                Column(Modifier.weight(1.4f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Transport(s, guest, big = false)
                    SeekBar(s, enabled = !guest, Modifier.widthIn(max = 560.dp))
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onOpenLyrics, enabled = song != null) { Icon(Icons.Default.Lyrics, "Lyrics") }
                    IconButton(onOpenQueue) { Icon(Icons.AutoMirrored.Filled.QueueMusic, "Queue") }
                    VolumeControl(s)
                }
            }
        }
    }
}

/** Phone controls stay separate from the tappable title so their accessibility actions do not overlap. */
@Composable
fun MobilePlayerBar(onOpenFull: () -> Unit) {
    val s by Player.state.collectAsState()
    val song = s.current ?: return
    val guest = isGuest()
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClickLabel = "Open player", role = SemanticRole.Button, onClick = onOpenFull).padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Thumb(song.thumbnail, 44.dp)
                    Column(Modifier.weight(1f)) {
                        Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                        Text(song.artistText, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(Player::playPause, enabled = !guest) {
                    if (s.isBuffering) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else Icon(if (s.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, if (s.isPlaying) "Pause" else "Play")
                }
                IconButton({ Player.next() }, enabled = !guest && s.hasNext) { Icon(Icons.Default.SkipNext, "Next") }
            }
            LinearProgressIndicator(
                progress = { (s.positionMs.toFloat() / s.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(2.dp).semantics { contentDescription = "Playback progress" },
            )
        }
    }
}

enum class PlayerPage { NOW_PLAYING, LYRICS, QUEUE }

@Composable
private fun MobileFullPlayer(onClose: () -> Unit, initialPage: PlayerPage) {
    val s by Player.state.collectAsState()
    val guest = isGuest()
    var page by remember(initialPage) { mutableStateOf(initialPage) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.ExpandMore, "Close player") }
                Text(s.queueTitle ?: "Now playing", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                SleepTimerButton(s, guest)
            }
            TabRow(selectedTabIndex = page.ordinal) {
                PlayerPage.entries.forEach { item ->
                    Tab(selected = page == item, onClick = { page = item }, text = {
                        Text(when (item) { PlayerPage.NOW_PLAYING -> "Playing"; PlayerPage.LYRICS -> "Lyrics"; PlayerPage.QUEUE -> "Up next" })
                    })
                }
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                when (page) {
                    PlayerPage.LYRICS -> LyricsPanel(compact = true)
                    PlayerPage.QUEUE -> QueuePanel(compact = true)
                    PlayerPage.NOW_PLAYING -> {
                        val artSize = minOf(360.dp, (maxWidth - 48.dp).coerceAtLeast(1.dp), (maxHeight - 240.dp).coerceAtLeast(160.dp))
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            item { Thumb(hiRes(s.current?.thumbnail), artSize, RoundedCornerShape(16.dp)) }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.current?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        Text(s.current?.artistText.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                    s.current?.let { LikeButton(it) }
                                }
                            }
                            item {
                                SeekBar(s, enabled = !guest, modifier = Modifier.fillMaxWidth(), compact = true)
                                Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { Transport(s, guest, big = true, compact = true) }
                            }
                            item {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                                    if (guest) GuestBadge()
                                    s.current?.let { DownloadButton(it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Full-screen now playing adapts to phone pages instead of squeezing two columns. */
@Composable
fun FullPlayer(onClose: () -> Unit, compact: Boolean = false, initialPage: PlayerPage = PlayerPage.NOW_PLAYING) {
    if (compact) return MobileFullPlayer(onClose, initialPage)
    val s by Player.state.collectAsState()
    val guest = isGuest()
    var tab by remember { mutableStateOf(0) } // 0 lyrics, 1 queue
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // Apple Music style backdrop: the cover art, heavily blurred, tints the whole view.
        AsyncImage(hiRes(s.current?.thumbnail, 120), null, Modifier.fillMaxSize().graphicsLayer { alpha = 0.45f; scaleX = 1.3f; scaleY = 1.3f }.blur(90.dp), contentScale = ContentScale.Crop)
        Column(Modifier.fillMaxSize().padding(24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClose) { Icon(Icons.Default.ExpandMore, "Close player") }
                Text(s.queueTitle ?: "Now playing", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (guest) GuestBadge()
            }
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val song = s.current
                    Thumb(hiRes(song?.thumbnail), 380.dp, RoundedCornerShape(16.dp))
                    Spacer(Modifier.height(24.dp))
                    Column(Modifier.widthIn(max = 460.dp).fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(song?.title ?: "Nothing playing", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(song?.artistText.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (song != null) { LikeButton(song); DownloadButton(song) }
                        }
                        Spacer(Modifier.height(8.dp))
                        SeekBar(s, enabled = !guest)
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Transport(s, guest, big = true) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            SleepTimerButton(s, guest)
                            Spacer(Modifier.weight(1f))
                            VolumeControl(s)
                        }
                    }
                }
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton({ tab = 0 }) { Text("Lyrics", fontWeight = if (tab == 0) FontWeight.Bold else FontWeight.Normal) }
                        TextButton({ tab = 1 }) { Text("Up next", fontWeight = if (tab == 1) FontWeight.Bold else FontWeight.Normal) }
                    }
                    if (tab == 0) LyricsPanel() else QueuePanel()
                }
            }
        }
    }
}

/** Current queue with click-to-play, reorder and remove (host / solo only). */
@Composable
fun QueuePanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    val s by Player.state.collectAsState()
    val guest = isGuest()
    val list = rememberLazyListState()
    LaunchedEffect(s.index) { if (s.index > 0) list.animateScrollToItem((s.index - 1).coerceAtLeast(0)) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.queueTitle ?: "Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${s.queue.size} songs", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!guest && s.queue.size > 1) TextButton(Player::clearQueue) { Text("Clear") }
        }
        if (s.queue.isEmpty()) return@Column EmptyBox("Queue is empty", Icons.AutoMirrored.Filled.QueueMusic)
        LazyColumn(state = list) {
            itemsIndexed(s.queue, key = { i, song -> "$i-${song.id}" }) { i, song ->
                SongRow(
                    song,
                    onClick = { if (!guest) Player.skipTo(i) },
                    playing = i == s.index,
                    extraMenu = if (guest || !compact) null else ({ close ->
                        if (i > 0) MenuItem("Move up", Icons.Default.ArrowUpward) { Player.moveInQueue(i, i - 1); close() }
                        if (i < s.queue.lastIndex) MenuItem("Move down", Icons.Default.ArrowDownward) { Player.moveInQueue(i, i + 1); close() }
                        MenuItem("Remove from queue", Icons.Default.Close) { Player.removeFromQueue(i); close() }
                    }),
                    trailing = if (guest || compact) null else ({
                        Row {
                            IconButton({ Player.moveInQueue(i, i - 1) }, enabled = i > 0) { Icon(Icons.Default.ArrowUpward, "Move up", Modifier.size(18.dp)) }
                            IconButton({ Player.moveInQueue(i, i + 1) }, enabled = i < s.queue.lastIndex) { Icon(Icons.Default.ArrowDownward, "Move down", Modifier.size(18.dp)) }
                            IconButton({ Player.removeFromQueue(i) }) { Icon(Icons.Default.Close, "Remove from queue", Modifier.size(18.dp)) }
                        }
                    }),
                )
            }
        }
    }
}

/** Synced lyrics: auto-scrolls to the active line, click a line to seek (host / solo only). */
@Composable
fun LyricsPanel(modifier: Modifier = Modifier, compact: Boolean = false) {
    val s by Player.state.collectAsState()
    val settings by Stores.settings.state.collectAsState()
    val guest = isGuest()
    val song = s.current ?: return EmptyBox("Nothing playing", Icons.Default.Lyrics)
    var reload by remember { mutableStateOf(0) }
    val lyrics by produceState<Result<Lyrics?>?>(null, song.id, settings.lyricsProviders, reload) {
        value = null
        value = try {
            Result.success(withContext(Dispatchers.IO) { LyricsRepository.get(song, settings.lyricsProviders) })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        }
    }
    val result = lyrics ?: return Loading(modifier)
    val ly = result.getOrNull()
    if (ly == null) {
        return Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(result.exceptionOrNull()?.message?.let { "Couldn't load lyrics: $it" } ?: "No lyrics found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({ LyricsRepository.invalidate(song.id); reload++ }) { Text("Retry") }
            }
        }
    }
    val fontSize = settings.lyricsTextSize.sp
    val style = LyricStyle.copy(fontSize = fontSize, lineHeight = fontSize * 1.3f)
    Column(modifier.fillMaxSize()) {
        if (!ly.synced) {
            SelectionContainer(Modifier.weight(1f)) {
                LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                    item { Text(ly.plain, style = style.copy(fontWeight = FontWeight.Medium, fontSize = fontSize * 0.75f, lineHeight = fontSize)) }
                }
            }
        } else {
            val pos = smoothPosition(s.positionMs, s.isPlaying)
            val items = remember(ly) { lyricItems(ly.lines) }
            // Focus = latest started line or gap. Background vocals never take focus, they light up with their line.
            val current = items.indexOfLast { (it.line < 0 || !ly.lines[it.line].background) && it.startMs <= pos + 300 }
            val list = rememberLazyListState()
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            LaunchedEffect(song.id, current) { list.animateScrollToItem(current.coerceAtLeast(0)) }
            BoxWithConstraints(
                Modifier.weight(1f).hoverable(hover)
                    // Soft fade at the top and bottom edges, like Apple Music.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.8f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    },
            ) {
                LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(top = maxHeight * 0.25f, bottom = maxHeight * 0.7f)) {
                    itemsIndexed(items) { i, item ->
                        if (item.line < 0) {
                            // Gap: dots only while it is playing, collapsed otherwise so lines don't jump around.
                            Box(Modifier.fillMaxWidth().animateContentSize().padding(horizontal = 16.dp)) {
                                if (i == current && pos < item.endMs) InterludeDots((pos - item.startMs).toFloat() / (item.endMs - item.startMs), pos)
                            }
                            return@itemsIndexed
                        }
                        val line = ly.lines[item.line]
                        val active = i == current || (current >= 0 && i > current && line.background && line.timeMs <= pos + 300 && (current + 1 until i).all { items[it].line >= 0 && ly.lines[items[it].line].background })
                        LyricRow(
                            line, active, distance = if (current < 0) i + 1 else abs(i - current), pos = if (active) pos else 0L,
                            style = if (line.background) style.copy(fontSize = fontSize * 0.7f, lineHeight = fontSize * 0.9f, fontStyle = FontStyle.Italic) else style,
                            sharp = hovered || compact,
                            onClick = if (guest) null else ({ Player.seekTo(line.timeMs); if (!s.isPlaying) Player.play() }),
                        )
                    }
                }
            }
        }
        Text("Source: ${ly.provider}", Modifier.padding(16.dp, 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** VLC reports time every ~250 ms, so interpolate between reports with the frame clock for smooth word fills. */
@Composable
private fun smoothPosition(reported: Long, playing: Boolean): Long {
    var now by remember { mutableLongStateOf(reported) }
    LaunchedEffect(reported, playing) {
        now = reported
        if (!playing) return@LaunchedEffect
        // The frame clock has its own time base, so measure from the first frame.
        val base = withFrameNanos { it }
        while (true) withFrameNanos { now = reported + (it - base) / 1_000_000 }
    }
    return now
}

/**
 * One lyric line, Apple Music style: the active line is full size and sharp, the others shrink, dim and blur
 * with distance (sharp while hovered so they are easy to read and click). Word-synced lines fill word by word.
 */
@Composable
private fun LyricRow(line: LyricLine, active: Boolean, distance: Int, pos: Long, style: TextStyle, sharp: Boolean, onClick: (() -> Unit)?) {
    val color = MaterialTheme.colorScheme.onSurface
    val d = distance.coerceAtMost(4)
    val motion = spring<Float>(dampingRatio = 0.8f, stiffness = 120f)
    val scale by animateFloatAsState(if (active) 1f else 0.94f, motion)
    val alpha by animateFloatAsState(if (active) 1f else if (sharp) 0.5f else 0.42f - d * 0.05f, motion)
    val blur by animateDpAsState(if (active || sharp) 0.dp else (d * 1.2f).dp, spring(stiffness = 120f))
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha; transformOrigin = TransformOrigin(0f, 0.5f) }
            .blur(blur, BlurredEdgeTreatment.Unbounded)
            .animateContentSize(),
    ) {
        when {
            active && line.words.isNotEmpty() -> {
                val gap = with(LocalDensity.current) { (style.fontSize * 0.26f).toDp() }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(gap)) {
                    for (w in lyricWordGroups(line.text, line.words)) KaraokeWord(w, pos, style, color)
                }
            }
            else -> Text(line.text, style = style, color = color)
        }
    }
}

/** A word fills left to right with a soft edge, lifts slightly as it is sung, and glows when held long. */
@Composable
private fun KaraokeWord(sylls: List<LyricWord>, pos: Long, style: TextStyle, color: Color) {
    val text = sylls.joinToString("") { it.text }
    var lit = 0f
    for (w in sylls) {
        val f = ((pos - w.startMs).toFloat() / (w.endMs - w.startMs).coerceAtLeast(1)).coerceIn(0f, 1f)
        lit += f * w.text.length
        if (f < 1f) break
    }
    val p = lit / text.length.coerceAtLeast(1)
    val start = sylls.first().startMs
    val dur = sylls.last().endMs - start
    val rise = ((pos - start).toFloat() / dur.coerceAtLeast(250)).coerceIn(0f, 1f)
    val glow = if (dur > 1000 && p > 0f && p < 1f) sin(p * PI).toFloat() else 0f
    val dim = color.copy(alpha = 0.35f)
    val brush = when {
        p <= 0f -> SolidColor(dim)
        p >= 1f -> SolidColor(color)
        else -> {
            val e = 0.12f
            val q = -e + p * (1 + 2 * e)
            Brush.horizontalGradient((q - e).coerceIn(0f, 1f) to color, (q + e).coerceIn(0f, 1f) to dim)
        }
    }
    Text(
        text,
        style = style.copy(brush = brush, shadow = if (glow > 0f) Shadow(color.copy(alpha = 0.7f * glow), blurRadius = 28f * glow) else null),
        modifier = Modifier.graphicsLayer {
            translationY = -3.dp.toPx() * (1 - (1 - rise) * (1 - rise) * (1 - rise))
            scaleX = 1 + 0.03f * glow; scaleY = scaleX
            transformOrigin = TransformOrigin(0.5f, 1f)
        },
    )
}

/** Three dots that fill one by one with [progress] and gently breathe. Each dot scales around its own center so the row stays level. */
@Composable
private fun InterludeDots(progress: Float, pos: Long) {
    val color = MaterialTheme.colorScheme.onSurface
    val breathe = 1f + 0.12f * sin(pos / 1000.0 * 4).toFloat()
    val fade = (minOf(progress, 1 - progress) * 8).coerceIn(0f, 1f)
    Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { k ->
            Box(
                Modifier.size(14.dp).graphicsLayer { scaleX = breathe * fade; scaleY = breathe * fade }
                    .background(color.copy(alpha = 0.3f + 0.7f * (progress * 3 - k).coerceIn(0f, 1f)), CircleShape),
            )
        }
    }
}
