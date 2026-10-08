package com.utaloom

import com.utaloom.data.Library
import com.utaloom.data.LocalPlaylist
import com.utaloom.data.Paths
import com.utaloom.data.SavedRef
import com.utaloom.data.Song
import com.utaloom.data.Stores
import com.utaloom.ui.decodePlaylist
import com.utaloom.ui.loginCookieError
import com.utaloom.ui.playlistNameError
import com.utaloom.ui.serverUrlError
import com.utaloom.innertube.YouTube
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class WorkflowAcceptanceTest {

    @Test
    fun libraryPersistenceAndPlaylistExportImport() {
        Stores.library.update { it.copy(liked = emptyList(), savedAlbums = emptyList(), savedArtists = emptyList(), savedPlaylists = emptyList(), playlists = emptyList()) }
        val s1 = Song("item0000001", "Song Alpha", listOf("Artist A"), album = "Album A", albumId = "MPREb_A", duration = 180)
        val s2 = Song("item0000002", "Song Beta", listOf("Artist B"), duration = 210)
        Library.clearHistory()
        Library.addHistory(s1)
        Library.toggleLike(s1)
        assertTrue(Library.isLiked(s1.id))
        Library.toggleSaved("album", SavedRef("MPREb_A", "Album A", "Artist A"))
        assertTrue(Library.isSaved("album", "MPREb_A"))

        val pid = Library.createPlaylist("Test Playlist", listOf(s1))
        Library.addToPlaylist(pid, listOf(s2))
        Library.moveInPlaylist(pid, 1, 0)
        val p = Stores.library.value.playlists.first { it.id == pid }
        assertEquals(listOf(s2.id, s1.id), p.songs.map { it.id })

        val json = kotlinx.serialization.json.Json.encodeToString(LocalPlaylist.serializer(), p)
        val decoded = decodePlaylist(json)
        assertEquals(p.name, decoded.name)
        assertEquals(p.songs, decoded.songs)

        Library.renamePlaylist(pid, "Renamed Playlist")
        assertEquals("Renamed Playlist", Stores.library.value.playlists.first { it.id == pid }.name)
        Library.deletePlaylist(pid)
        assertFalse(Stores.library.value.playlists.any { it.id == pid })

        Library.addSearch("Daft Punk")
        Library.addSearch("Rick Astley")
        assertTrue(Stores.library.value.searchHistory.contains("Rick Astley"))
        Library.removeSearch("Daft Punk")
        assertFalse(Stores.library.value.searchHistory.contains("Daft Punk"))
    }

    @Test
    fun inputAndDataLossBoundaries() {
        assertNotNull(playlistNameError(""))
        assertNotNull(playlistNameError("   "))
        assertNotNull(playlistNameError("a".repeat(201)))
        assertNull(playlistNameError("Valid Name"))

        assertNotNull(serverUrlError("http://example.com"))
        assertNotNull(serverUrlError("wss://example.com:0"))
        assertNotNull(serverUrlError("wss://user:pass@example.com/ws"))
        assertNull(serverUrlError("wss://utaloom.caliph.dev/ws"))

        assertNotNull(loginCookieError(""))
        assertNotNull(loginCookieError("a=b; c=d"))
        assertNotNull(loginCookieError("SAPISID=; a=b"))
        assertNotNull(loginCookieError("SAPISID=123\r\nInjected: value"))
        assertNull(loginCookieError("SAPISID=test1234; secure=1"))

        // Corrupt file preservation
        val tmp = Paths.dir.resolve("corrupt_check.json")
        tmp.writeText("{ invalid json")
        val store = com.utaloom.data.JsonStore(tmp, { com.utaloom.data.Settings() }, com.utaloom.data.Settings.serializer())
        assertEquals(com.utaloom.data.Settings().audioQuality, store.value.audioQuality)
        val backups = Paths.dir.listFiles { f -> f.name.startsWith("corrupt_check.json.corrupt-") }.orEmpty()
        assertTrue("Corrupted store was preserved rather than silently destroyed", backups.isNotEmpty())
        tmp.delete()
        backups.forEach { it.delete() }
    }

    @Test
    fun publicInnerTubeBrowseAndSearchContracts() = runBlocking {
        assumeTrue("Skip live YouTube network test on CI where cloud IPs may be rate-limited", System.getenv("CI") == null)
        val home = YouTube.home().getOrThrow()
        assertTrue("Home feed returned sections", home.sections.isNotEmpty())

        val search = YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG).getOrThrow()
        assertTrue("Song search returned items", search.items.isNotEmpty())

        val song = search.items.filterIsInstance<com.utaloom.innertube.models.SongItem>().first()
        val suggestions = YouTube.searchSuggestions("daft").getOrThrow()
        assertTrue("Suggestions returned queries", suggestions.queries.isNotEmpty())

        val albumRef = home.sections.flatMap { it.items }.filterIsInstance<com.utaloom.innertube.models.AlbumItem>().firstOrNull()
        if (albumRef != null) {
            val album = YouTube.album(albumRef.browseId).getOrThrow()
            assertTrue("Album songs resolved", album.songs.isNotEmpty())
        }
    }
}
