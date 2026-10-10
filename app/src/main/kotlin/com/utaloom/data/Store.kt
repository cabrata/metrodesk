package com.utaloom.data

import com.utaloom.lyrics.LyricsRepository
import com.utaloom.innertube.models.Album
import com.utaloom.innertube.models.Artist
import com.utaloom.innertube.models.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Serializable song snapshot used for library, history, playlists and the persisted queue. */
@Serializable
data class Song(
    val id: String,
    val title: String,
    val artists: List<String>,
    val artistIds: List<String?> = emptyList(),
    val album: String? = null,
    val albumId: String? = null,
    val duration: Int? = null,
    val thumbnail: String? = null,
    val explicit: Boolean = false,
) {
    val artistText get() = artists.joinToString(", ")

    fun toSongItem() = SongItem(
        id = id,
        title = title,
        artists = artists.mapIndexed { i, n -> Artist(n, artistIds.getOrNull(i)) },
        album = if (album != null && albumId != null) Album(album, albumId) else null,
        duration = duration,
        thumbnail = thumbnail.orEmpty(),
        explicit = explicit,
    )
}

fun SongItem.toSong() = Song(
    id = id,
    title = title,
    artists = artists.map { it.name },
    artistIds = artists.map { it.id },
    album = album?.name,
    albumId = album?.id,
    duration = duration,
    thumbnail = thumbnail,
    explicit = explicit,
)

@Serializable
data class LocalPlaylist(val id: String, val name: String, val songs: List<Song> = emptyList())

@Serializable
data class Settings(
    val darkMode: String = "system", // system | dark | light
    val dynamicColor: Boolean = true,
    val audioQuality: String = "AUTO", // AUTO | HIGH | LOW
    val volume: Int = 80,
    val normalizeVolume: Boolean = true,
    val smartShuffle: Boolean = false,
    val cookie: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val accountEmail: String? = null,
    val accountAvatar: String? = null,
    val useLoginForBrowse: Boolean = true,
    val contentLanguage: String = "en",
    val contentCountry: String = "US",
    val hideExplicit: Boolean = false,
    val pauseHistory: Boolean = false,
    val pauseSearchHistory: Boolean = false,
    val proxy: String? = null,
    val ltServerUrl: String = "wss://utaloom.caliph.dev/ws",
    val ltUsername: String = "",
    val ltAutoApproveJoins: Boolean = false,
    val ltAutoApproveSuggestions: Boolean = false,
    val ltSyncVolume: Boolean = false,
    val lyricsOrder: List<String> = emptyList(),
    val lyricsDisabled: Set<String> = setOf("LyricsPlus"),
    val lyricsTextSize: Int = 26,
    val minimizeToTray: Boolean = true,
) {
    internal fun normalizeLegacyLtServerUrl() =
        if (ltServerUrl.trim() == "wss://metrolist.caliph.dev/ws") copy(ltServerUrl = "wss://utaloom.caliph.dev/ws") else this

    /** Saved order first, then providers added in later versions. */
    val lyricsProviderOrder get() = lyricsOrder.filter { it in LyricsRepository.providers } + LyricsRepository.providers.filter { it !in lyricsOrder }
    val lyricsProviders get() = lyricsProviderOrder.filter { it !in lyricsDisabled }
}

@Serializable
data class PersistedQueue(val songs: List<Song> = emptyList(), val index: Int = 0, val positionMs: Long = 0, val title: String? = null)

@Serializable
data class LibraryState(
    val liked: List<Song> = emptyList(),
    val history: List<Song> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val savedAlbums: List<SavedRef> = emptyList(),
    val savedArtists: List<SavedRef> = emptyList(),
    val savedPlaylists: List<SavedRef> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
    val downloaded: Map<String, String> = emptyMap(), // songId -> absolute file path
    val downloadedSongs: Map<String, Song> = emptyMap(),
)

@Serializable
data class SavedRef(val id: String, val title: String, val subtitle: String? = null, val thumbnail: String? = null, val playlistId: String? = null)

/**
 * JSON-file backed stores. ponytail: whole-file rewrite with debounce; fine up to tens of thousands
 * of songs. Swap to SQLite if libraries get much bigger.
 */
@OptIn(FlowPreview::class)
class JsonStore<T>(private val file: File, initial: () -> T, private val serializer: kotlinx.serialization.KSerializer<T>) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
    private val _state = MutableStateFlow(
        if (!file.exists()) initial() else runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse {
            // Keep the unreadable file instead of overwriting the user's data.
            file.copyTo(File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}"), overwrite = false)
            initial()
        },
    )
    val state: StateFlow<T> = _state.asStateFlow()
    val value: T get() = _state.value

    init {
        _state.drop(1).debounce(400).onEach { save(it) }.launchIn(scope)
    }

    fun update(block: (T) -> T) = _state.update(block)

    fun flush() = save(_state.value)

    @Synchronized
    private fun save(value: T) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, value))
        // Settings can contain a YouTube cookie: keep it owner-only on POSIX systems.
        runCatching { java.nio.file.Files.setPosixFilePermissions(tmp.toPath(), java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")) }
        runCatching {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        }.recoverCatching {
            java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }.onFailure { System.err.println("Couldn't save ${file.name}: ${it.message}") }
    }

    companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

object Stores {
    val settings = JsonStore(Paths.dir.resolve("settings.json"), ::Settings, Settings.serializer())
    val library = JsonStore(Paths.dir.resolve("library.json"), ::LibraryState, LibraryState.serializer())
    val queue = JsonStore(Paths.dir.resolve("queue.json"), ::PersistedQueue, PersistedQueue.serializer())

    init {
        val normalized = settings.value.normalizeLegacyLtServerUrl()
        if (normalized != settings.value) {
            settings.update { normalized }
            settings.flush()
        }
    }

    fun flushAll() {
        settings.flush(); library.flush(); queue.flush()
    }
}

object Library {
    private val store get() = Stores.library

    fun isLiked(id: String) = store.value.liked.any { it.id == id }

    fun toggleLike(song: Song) = store.update { s ->
        if (s.liked.any { it.id == song.id }) s.copy(liked = s.liked.filterNot { it.id == song.id })
        else s.copy(liked = listOf(song) + s.liked)
    }

    fun addHistory(song: Song) {
        if (Stores.settings.value.pauseHistory) return
        store.update { s ->
            s.copy(
                history = (listOf(song) + s.history.filterNot { it.id == song.id }).take(1000),
                playCounts = s.playCounts + (song.id to (s.playCounts[song.id] ?: 0) + 1),
            )
        }
    }

    fun clearHistory() = store.update { it.copy(history = emptyList()) }

    fun addSearch(q: String) {
        if (Stores.settings.value.pauseSearchHistory || q.isBlank()) return
        store.update { s -> s.copy(searchHistory = (listOf(q) + s.searchHistory.filterNot { it == q }).take(50)) }
    }

    fun removeSearch(q: String) = store.update { s -> s.copy(searchHistory = s.searchHistory - q) }

    fun createPlaylist(name: String, songs: List<Song> = emptyList()): String {
        val id = "LP" + System.currentTimeMillis().toString(36)
        store.update { it.copy(playlists = it.playlists + LocalPlaylist(id, name, songs)) }
        return id
    }

    fun renamePlaylist(id: String, name: String) = editPlaylist(id) { it.copy(name = name) }
    fun deletePlaylist(id: String) = store.update { s -> s.copy(playlists = s.playlists.filterNot { it.id == id }) }
    fun addToPlaylist(id: String, songs: List<Song>) = editPlaylist(id) { p ->
        p.copy(songs = p.songs + songs.filter { n -> p.songs.none { it.id == n.id } })
    }
    fun removeFromPlaylist(id: String, songId: String) = editPlaylist(id) { p -> p.copy(songs = p.songs.filterNot { it.id == songId }) }
    fun moveInPlaylist(id: String, from: Int, to: Int) = editPlaylist(id) { p ->
        p.copy(songs = p.songs.toMutableList().apply { add(to.coerceIn(0, size - 1), removeAt(from)) })
    }

    private fun editPlaylist(id: String, f: (LocalPlaylist) -> LocalPlaylist) =
        store.update { s -> s.copy(playlists = s.playlists.map { if (it.id == id) f(it) else it }) }

    fun toggleSaved(kind: String, ref: SavedRef) = store.update { s ->
        fun List<SavedRef>.toggle() = if (any { it.id == ref.id }) filterNot { it.id == ref.id } else listOf(ref) + this
        when (kind) {
            "album" -> s.copy(savedAlbums = s.savedAlbums.toggle())
            "artist" -> s.copy(savedArtists = s.savedArtists.toggle())
            else -> s.copy(savedPlaylists = s.savedPlaylists.toggle())
        }
    }

    fun isSaved(kind: String, id: String) = when (kind) {
        "album" -> store.value.savedAlbums
        "artist" -> store.value.savedArtists
        else -> store.value.savedPlaylists
    }.any { it.id == id }

    fun setDownloaded(id: String, path: String?, song: Song? = null) = store.update { s ->
        s.copy(
            downloaded = if (path == null) s.downloaded - id else s.downloaded + (id to path),
            downloadedSongs = when {
                path == null -> s.downloadedSongs - id
                song != null -> s.downloadedSongs + (id to song)
                else -> s.downloadedSongs
            },
        )
    }
}
