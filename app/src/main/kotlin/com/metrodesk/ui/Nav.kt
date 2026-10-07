package com.metrodesk.ui

import androidx.compose.runtime.mutableStateListOf
import com.metrolist.innertube.models.BrowseEndpoint

sealed interface Screen {
    data object Home : Screen
    data object Explore : Screen
    data object Library : Screen
    data object Together : Screen
    data object Settings : Screen
    data class Search(val query: String) : Screen
    data class Album(val browseId: String) : Screen
    data class Artist(val browseId: String) : Screen
    data class Playlist(val playlistId: String) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class Browse(val title: String, val endpoint: BrowseEndpoint) : Screen
    data class ArtistItems(val title: String, val endpoint: BrowseEndpoint) : Screen
    data class SongList(val title: String, val kind: String) : Screen // liked | history | downloads | top
}

object Nav {
    val stack = mutableStateListOf<Screen>(Screen.Home)
    val current get() = stack.last()

    fun go(s: Screen) {
        if (stack.last() != s) stack.add(s)
    }

    /** Switch top-level tab, clearing history. */
    fun root(s: Screen) {
        stack.clear(); stack.add(s)
    }

    fun back(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        return true
    }
}
