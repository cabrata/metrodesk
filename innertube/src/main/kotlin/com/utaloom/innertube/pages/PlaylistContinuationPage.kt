package com.utaloom.innertube.pages

import com.utaloom.innertube.models.SongItem

data class PlaylistContinuationPage(
    val songs: List<SongItem>,
    val continuation: String?,
)
