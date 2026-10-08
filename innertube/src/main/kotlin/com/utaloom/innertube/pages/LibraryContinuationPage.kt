package com.utaloom.innertube.pages

import com.utaloom.innertube.models.YTItem

data class LibraryContinuationPage(
    val items: List<YTItem>,
    val continuation: String?,
)
