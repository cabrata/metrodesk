package com.metrodesk

import com.metrodesk.playback.StreamResolver
import com.metrolist.innertube.YouTube
import kotlinx.coroutines.runBlocking

/** Dev check: `bash gradlew :app:probe -PvideoId=...` resolves a stream and prints its URL. */
fun main(args: Array<String>) = runBlocking {
    val id = args.firstOrNull() ?: "dQw4w9WgXcQ"
    YouTube.search("daft punk", YouTube.SearchFilter.FILTER_SONG).onSuccess { println("search ok: ${it.items.size} items") }
        .onFailure { println("search failed: $it") }
    val stream = StreamResolver.resolve(id)
    println("client=${stream.clientName} itag=${stream.itag} mime=${stream.mimeType} bitrate=${stream.bitrate}")
    println("url=${stream.audioUrl}")
    println("headers=${stream.headers}")
    check(stream.audioUrl.startsWith("https://")) { "bad url" }
}
