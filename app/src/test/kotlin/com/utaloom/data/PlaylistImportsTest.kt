package com.utaloom.data

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistImportsTest {
    @Test
    fun invalidLinkReportsFailureWithoutCreatingPlaylist() = runBlocking {
        val before = Stores.library.state.value.playlists
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { PlaylistImports.events.first() } }
        PlaylistImports.start("https://example.com/not-a-playlist")
        assertTrue(event.await() is PlaylistImports.Failed)
        withTimeout(5000) { PlaylistImports.progress.first { it == null } }
        assertEquals(before, Stores.library.state.value.playlists)
    }

    @Test
    fun cancelClearsProgressAndDoesNotLeaveAStaleJob() = runBlocking {
        val before = Stores.library.state.value.playlists
        repeat(20) {
            PlaylistImports.start("https://example.com/not-a-playlist")
            PlaylistImports.toBackground()
            PlaylistImports.cancel()
            assertNull(PlaylistImports.progress.value)
        }
        delay(100)
        assertNull(PlaylistImports.progress.value)
        assertEquals(before, Stores.library.state.value.playlists)
        val event = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(5000) { PlaylistImports.events.first() } }
        PlaylistImports.start("https://example.com/retry")
        assertTrue(event.await() is PlaylistImports.Failed)
        withTimeout(5000) { PlaylistImports.progress.first { it == null } }
        Unit
    }
}
