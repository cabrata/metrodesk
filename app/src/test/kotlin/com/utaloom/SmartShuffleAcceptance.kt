package com.utaloom

import com.utaloom.data.Settings
import com.utaloom.data.Stores
import com.utaloom.data.toSong
import com.utaloom.innertube.YouTube
import com.utaloom.innertube.models.AlbumItem
import com.utaloom.platform.NativeLibs
import com.utaloom.playback.Player
import com.utaloom.playback.PlayerHooks
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

/** Live public-player acceptance: real YouTube album/radio and libVLC, isolated by the Gradle task. */
fun main() = runBlocking {
    val settings = Stores.settings.value
    val ready = AtomicBoolean()
    try {
        check(!Settings().smartShuffle) { "Smart shuffle must default to off" }
        check(NativeLibs.discoverVlc() == null && Player.init()) { "Native VLC unavailable" }
        Player.setVolume(0)
        Player.setSmartShuffle(false)
        val search = withTimeout(30_000) {
            YouTube.search("Daft Punk Random Access Memories", YouTube.SearchFilter.FILTER_ALBUM).getOrThrow()
        }
        val album = search.items.filterIsInstance<AlbumItem>().first {
            it.title == "Random Access Memories" && it.artists?.any { artist -> artist.name == "Daft Punk" } == true
        }
        val originals = withTimeout(30_000) { YouTube.album(album.browseId).getOrThrow() }.songs
            .map { it.toSong() }.distinctBy { it.id }.take(13)
        check(originals.size == 13) { "Expected a real 13-song album" }
        val originalIds = originals.map { it.id }.toSet()
        Player.hooks = object : PlayerHooks {
            override fun onBufferReady(songId: String) { if (songId == originals.first().id) ready.set(true) }
        }
        Player.playQueue(originals, title = album.title, play = false)
        withTimeout(90_000) {
            while (!ready.get()) {
                Player.state.value.error?.let { error(it) }
                delay(100)
            }
        }
        check(!Player.state.value.isPlaying) { "Paused native load unexpectedly played" }
        println("Real album: 13 songs, native VLC paused readiness: OK")

        suspend fun awaitMix() {
            withTimeout(35_000) { while (Player.state.value.queue.size == 13) delay(100) }
            val mixed = Player.state.value
            check(mixed.queue.size == 17) { "Expected 13 originals + 4 recommendations" }
            check(mixed.queue.map { it.id }.distinct().size == 17) { "Duplicate recommendations" }
            check(mixed.queue.mapIndexedNotNull { i, song -> i.takeIf { song.id !in originalIds } } == listOf(4, 8, 12, 16)) {
                "Recommendations must follow every third original after the current song"
            }
            check(Stores.queue.value.songs.map { it.id }.toSet() == originalIds) { "Temporary recommendations persisted" }
            check(mixed.current?.id == originals.first().id && !mixed.isPlaying) { "Mix interrupted current playback" }
        }
        Player.toggleShuffle()
        check(Player.state.value.queue.size == 13) { "Normal shuffle added recommendations with setting off" }
        Player.setSmartShuffle(true)
        awaitMix()
        println("Live setting on: 13 -> 17, unique recommendations at 4/8/12/16, persisted originals only: OK")
        val position = Player.state.value.positionMs
        Player.setSmartShuffle(false)
        check(Player.state.value.shuffle && Player.state.value.queue.size == 13)
        check(Player.state.value.current?.id == originals.first().id && Player.state.value.positionMs == position)
        Player.setSmartShuffle(true)
        awaitMix()
        Player.toggleShuffle()
        check(!Player.state.value.shuffle && Player.state.value.queue == originals)
        println("Setting off and shuffle off: 17 -> 13, original order/current/position preserved: OK")

        Player.toggleShuffle()
        awaitMix()
        val recommendation = Player.state.value.queue[4]
        Player.skipTo(4)
        Player.pause()
        Player.setSmartShuffle(false)
        check(Player.state.value.current?.id == recommendation.id && Player.state.value.queue.size == 14)
        check(Player.state.value.queue.count { it.id !in originalIds } == 1)
        check(Stores.queue.value.songs[Stores.queue.value.index].id == recommendation.id)
        println("Disabling during a recommended track keeps only that current recommendation: OK")

        Player.playQueue(originals, play = false)
        Player.setSmartShuffle(true)
        Player.toggleShuffle()
        val replacement = originals.take(2)
        Player.playQueue(replacement, play = false)
        delay(2000)
        check(Player.state.value.queue == replacement && !Player.state.value.shuffle) { "Stale recommendations entered replacement queue" }
        Player.remoteControlled = true
        Player.setQueueFromRemote(originals, originals.first().id)
        Player.toggleShuffle()
        Player.setSmartShuffle(false)
        Player.setSmartShuffle(true)
        check(Player.state.value.queue == originals && !Player.state.value.shuffle) { "Guest changed host queue" }
        println("Queue replacement cancellation and Listen Together guest restrictions: OK")
        Player.remoteControlled = false
        Player.setSmartShuffle(false)
        Player.playQueue(originals, play = false)
        Player.release()
        Stores.flushAll()
        check(Player.init())
        check(Player.state.value.queue == originals && !Player.state.value.isPlaying)
        println("Native release/init restores originals paused, without temporary recommendations: OK")
    } finally {
        Player.remoteControlled = false
        Player.hooks = null
        Player.stop()
        Player.release()
        Stores.settings.update { settings }
        Stores.flushAll()
    }
}
