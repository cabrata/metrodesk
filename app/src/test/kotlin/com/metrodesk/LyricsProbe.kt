package com.metrodesk

import com.metrodesk.data.Song
import com.metrodesk.lyrics.LyricsRepository
import kotlinx.coroutines.runBlocking

/** Live check: asks each lyrics provider alone for a well-known song. Run: ./gradlew :app:lyricsProbe */
fun main() = runBlocking {
    com.metrolist.innertube.YouTube.run { visitorData = visitorData().getOrNull() }
    val song = Song(System.getenv("VID") ?: "dQw4w9WgXcQ", "Never Gonna Give You Up", listOf("Rick Astley"), album = "Whenever You Need Somebody", duration = 213)
    for (p in LyricsRepository.providers) {
        LyricsRepository.invalidate(song.id)
        val t = System.currentTimeMillis()
        val l = runCatching { LyricsRepository.get(song, listOf(p)) }.getOrNull()
        val words = l?.lines?.count { it.words.isNotEmpty() } ?: 0
        println("%-16s %-5s lines=%-3d wordSynced=%-3d %dms  %s".format(p, l != null, l?.lines?.size ?: 0, words, System.currentTimeMillis() - t, l?.lines?.firstOrNull { it.text.isNotBlank() }?.text.orEmpty().take(40)))
    }
}
