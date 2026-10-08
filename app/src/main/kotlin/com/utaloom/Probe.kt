package com.utaloom

import com.utaloom.playback.StreamResolver
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking

/** Dev check: `bash gradlew :app:probe -PvideoId=...` resolves a direct VLC-compatible stream. */
fun main(args: Array<String>) = runBlocking {
    val id = args.firstOrNull() ?: "dQw4w9WgXcQ"
    YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG).onSuccess { println("search ok: ${it.items.size} items") }
        .onFailure { println("search failed: $it") }
    val stream = StreamResolver.resolve(id, allowBoundedRange = false)
    println("client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType} bitrate=${stream.bitrate}")
    println("stream host=${java.net.URI(stream.audioUrl).host} boundedRange=${stream.requireBoundedRange}")
    println("header names=${stream.headers.keys}")
    check(stream.audioUrl.startsWith("https://")) { "bad url" }
}
