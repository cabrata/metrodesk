package com.utaloom.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.utaloom.data.Library
import com.utaloom.data.SavedRef
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.playback.Downloads
import com.utaloom.playback.Player
import com.utaloom.together.ListenTogether
import com.utaloom.together.Role
import com.utaloom.innertube.models.AlbumItem
import com.utaloom.innertube.models.ArtistItem
import com.utaloom.innertube.models.EpisodeItem
import com.utaloom.innertube.models.PlaylistItem
import com.utaloom.innertube.models.PodcastItem
import com.utaloom.innertube.models.SongItem
import com.utaloom.innertube.models.YTItem

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** YouTube thumbnails come in many sizes; ask for a crisp square one. */
fun hiRes(url: String?, size: Int = 544): String? = url
    ?.replace(Regex("=w\\d+-h\\d+"), "=w$size-h$size")
    ?.replace(Regex("=s\\d+"), "=s$size")

@Composable
fun Thumb(url: String?, size: Dp, shape: Shape = RoundedCornerShape(8.dp), modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
        if (url == null) Icon(Icons.Default.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else AsyncImage(hiRes(url, if (size > 80.dp) 544 else 120), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier.fillMaxSize()) = Box(modifier, contentAlignment = Alignment.Center) { CircularProgressIndicator() }

@Composable
fun ErrorBox(message: String, retry: (() -> Unit)? = null) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(message, color = MaterialTheme.colorScheme.error)
        if (retry != null) TextButton(retry) { Text("Retry") }
    }
}

@Composable
fun EmptyBox(message: String, icon: ImageVector = Icons.Default.MusicNote) = Box(Modifier.fillMaxSize().padding(48.dp), contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Opens the right screen / action for any YouTube item. */
fun openItem(item: YTItem, contextSongs: List<Song>? = null, title: String? = null) {
    when (item) {
        is SongItem -> {
            val songs = contextSongs ?: listOf(item.toSong())
            val idx = songs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
            if (contextSongs == null) Player.playRadio(item.toSong()) else Player.playQueue(songs, idx, title)
        }
        is EpisodeItem -> Player.playQueue(listOf(item.asSongItem().toSong()))
        is AlbumItem -> Nav.go(Screen.Album(item.browseId))
        is ArtistItem -> Nav.go(Screen.Artist(item.id))
        is PlaylistItem -> Nav.go(Screen.Playlist(item.id))
        is PodcastItem -> Nav.go(Screen.Playlist(item.id))
    }
}

fun YTItem.subtitle(): String = when (this) {
    is SongItem -> listOfNotNull(artists.joinToString(", ") { it.name }.ifBlank { null }, album?.name).joinToString(" • ")
    is AlbumItem -> listOfNotNull("Album", artists?.joinToString(", ") { it.name }, year?.toString()).joinToString(" • ")
    is ArtistItem -> "Artist"
    is PlaylistItem -> listOfNotNull("Playlist", author?.name, songCountText).joinToString(" • ")
    is PodcastItem -> listOfNotNull("Podcast", author?.name).joinToString(" • ")
    is EpisodeItem -> listOfNotNull("Episode", author?.name).joinToString(" • ")
}

@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showThumb: Boolean = true,
    playing: Boolean = false,
    extraMenu: (@Composable (close: () -> Unit) -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    var menu by remember { mutableStateOf(false) }
    val liked by Stores.library.state.collectAsState()
    val isLiked = liked.liked.any { it.id == song.id }
    val bg = if (playing) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onDoubleClick = onClick)
            .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) menu = true }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (index != null) Text("$index", Modifier.width(28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        if (showThumb) Box {
            Thumb(song.thumbnail, 48.dp)
            if (playing) Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.VolumeUp, null, tint = androidx.compose.ui.graphics.Color.White)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium,
                color = if (playing) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.explicit) Icon(Icons.Default.Explicit, null, Modifier.size(16.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                if (Downloads.isDownloaded(song.id)) Icon(Icons.Default.DownloadDone, null, Modifier.size(16.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.primary)
                Text(listOfNotNull(song.artistText.ifBlank { null }, song.album).joinToString(" • "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        trailing?.invoke()
        if (isLiked) Icon(Icons.Default.Favorite, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        song.duration?.let { Text(formatTime(it * 1000L), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Box {
            IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
            SongMenu(song, menu, { menu = false }, extraMenu)
        }
    }
}

@Composable
fun SongMenu(song: Song, expanded: Boolean, onDismiss: () -> Unit, extra: (@Composable (close: () -> Unit) -> Unit)? = null) {
    var addTo by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val lt by ListenTogether.state.collectAsState()
    DropdownMenu(expanded, onDismiss) {
        if (lt.role == Role.GUEST) {
            MenuItem("Suggest to host", Icons.Default.Groups) { ListenTogether.suggest(song); onDismiss() }
        } else {
            MenuItem("Play next", Icons.Default.SkipNext) { Player.playNext(listOf(song)); onDismiss() }
            MenuItem("Add to queue", Icons.AutoMirrored.Filled.QueueMusic) { Player.addToQueue(listOf(song)); onDismiss() }
            MenuItem("Start radio", Icons.Default.Radio) { Player.playRadio(song); onDismiss() }
        }
        HorizontalDivider()
        val liked = Library.isLiked(song.id)
        MenuItem(if (liked) "Remove from liked" else "Like", if (liked) Icons.Default.Favorite else Icons.Default.FavoriteBorder) { Library.toggleLike(song); onDismiss() }
        MenuItem("Add to playlist", Icons.AutoMirrored.Filled.PlaylistAdd) { addTo = true }
        if (Downloads.isDownloaded(song.id)) MenuItem("Remove download", Icons.Default.Delete) { Downloads.delete(song.id); onDismiss() }
        else MenuItem("Download", Icons.Default.Download) { Downloads.download(listOf(song)); onDismiss() }
        song.artistIds.firstOrNull { it != null }?.let { id -> MenuItem("Go to artist", Icons.Default.Person) { Nav.go(Screen.Artist(id)); onDismiss() } }
        song.albumId?.let { id -> MenuItem("Go to album", Icons.Default.Album) { Nav.go(Screen.Album(id)); onDismiss() } }
        MenuItem("Copy link", Icons.Default.ContentCopy) { clipboard.setText(AnnotatedString("https://music.youtube.com/watch?v=${song.id}")); onDismiss() }
        extra?.invoke(onDismiss)
    }
    if (addTo) AddToPlaylistDialog(listOf(song)) { addTo = false; onDismiss() }
}

@Composable
fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(text) }, onClick = onClick, leadingIcon = { Icon(icon, null) })

@Composable
fun AddToPlaylistDialog(songs: List<Song>, onDismiss: () -> Unit) {
    val lib by Stores.library.state.collectAsState()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lib.playlists.forEach { p ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).combinedClickableCompat { Library.addToPlaylist(p.id, songs); onDismiss() }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.AutoMirrored.Filled.QueueMusic, null)
                        Text(p.name, Modifier.weight(1f))
                        Text("${p.songs.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(name, { name = it }, label = { Text("New playlist") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton({ if (name.isNotBlank()) { Library.createPlaylist(name.trim(), songs); onDismiss() } }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.then(Modifier.combinedClickable(onClick = onClick))

/** Square / round card used in carousels and grids. */
@OptIn(ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun ItemCard(item: YTItem, width: Dp = 168.dp, onClick: () -> Unit = { openItem(item) }) {
    var menu by remember { mutableStateOf(false) }
    val round = item is ArtistItem
    Column(
        Modifier.width(width).clip(RoundedCornerShape(12.dp)).combinedClickable(onClick = onClick)
            .onPointerEvent(PointerEventType.Press) { if (it.buttons.isSecondaryPressed) menu = true }
            .padding(8.dp),
        horizontalAlignment = if (round) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box {
            Thumb(item.thumbnail, width - 16.dp, if (round) CircleShape else RoundedCornerShape(10.dp))
            ItemMenu(item, menu) { menu = false }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
        Text(item.subtitle(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ItemMenu(item: YTItem, expanded: Boolean, onDismiss: () -> Unit) {
    when (item) {
        is SongItem -> SongMenu(item.toSong(), expanded, onDismiss)
        else -> DropdownMenu(expanded, onDismiss) {
            val (kind, ref) = when (item) {
                is AlbumItem -> "album" to SavedRef(item.browseId, item.title, item.artists?.joinToString(", ") { it.name }, item.thumbnail, item.playlistId)
                is ArtistItem -> "artist" to SavedRef(item.id, item.title, null, item.thumbnail)
                else -> "playlist" to SavedRef(item.id, item.title, (item as? PlaylistItem)?.author?.name, item.thumbnail)
            }
            MenuItem("Open", Icons.Default.PlayArrow) { openItem(item); onDismiss() }
            when (item) {
                is ArtistItem -> item.radioEndpoint
                is PlaylistItem -> item.radioEndpoint
                else -> null
            }?.let { ep -> MenuItem("Start radio", Icons.Default.Radio) { Player.startRadio(ep, item.title); onDismiss() } }
            val saved = Library.isSaved(kind, ref.id)
            MenuItem(if (saved) "Remove from library" else "Save to library", if (saved) Icons.Default.Favorite else Icons.Default.FavoriteBorder) {
                Library.toggleSaved(kind, ref); onDismiss()
            }
        }
    }
}

fun LazyListScope.carousel(title: String, items: List<YTItem>, onMore: (() -> Unit)? = null) {
    if (items.isEmpty()) return
    item {
        SectionTitle(title) { if (onMore != null) TextButton(onMore) { Text("More") } }
    }
    val songsOnly = items.all { it is SongItem }
    item {
        if (songsOnly && items.size > 4) {
            // Quick-picks style: songs laid out in columns of 4 rows.
            val songs = items.filterIsInstance<SongItem>().map { it.toSong() }
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
                items(songs.chunked(4)) { col ->
                    Column(Modifier.width(380.dp)) {
                        col.forEach { s -> SongRow(s, { Player.playRadio(s) }) }
                    }
                }
            }
        } else {
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
                items(items, key = { it.id }) { ItemCard(it) }
            }
        }
    }
}
