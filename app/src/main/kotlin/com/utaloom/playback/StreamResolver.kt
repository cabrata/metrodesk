package com.utaloom.playback

import com.utaloom.data.Paths
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.ExtractedStream
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/** Desktop port of Metrolist's InnerTubeXPlayer: resolves a playable direct audio URL for a video id. */
object StreamResolver {
    private const val FAILURE_TTL_MS = 5 * 60 * 1000L

    private class Bundle(val generation: Long, val cipher: YouTubeCipherService, val extractor: InnerTubeExtractor)

    private var bundle: Bundle? = null
    private val mutex = Mutex()
    private val failedClients = ConcurrentHashMap<String, Pair<Set<String>, Long>>()

    private val logger = InnerTubeLogger { e ->
        if (e.level >= InnerTubeLogLevel.WARN || System.getenv("UTALOOM_DEBUG") != null) {
            System.err.println("[${e.level}] ${e.tag}: ${e.message} ${e.details.takeIf { it.isNotEmpty() } ?: ""}")
        }
    }

    suspend fun resolve(videoId: String, quality: AudioQuality = AudioQuality.AUTO, uploaded: Boolean = false, allowBoundedRange: Boolean = true): ExtractedStream {
        val excluded = failedClients[videoId]?.takeIf { System.currentTimeMillis() - it.second < FAILURE_TTL_MS }?.first.orEmpty()
        val hints = ContentHints(isUploaded = uploaded).withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = allowBoundedRange)
        return requireNotNull(
            bundle().extractor.extract(
                videoId = videoId,
                hints = hints,
                excludedClients = excluded,
                audioQuality = quality,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            ),
        ) { "No playable stream" }
    }

    fun markFailed(videoId: String, client: String) {
        failedClients.compute(videoId) { _, old -> (old?.first.orEmpty() + client) to System.currentTimeMillis() }
    }

    suspend fun refreshAfterRejection(): Boolean = bundle().cipher.refreshAfterStreamRejection()

    private suspend fun bundle(): Bundle = mutex.withLock {
        val transport = YouTube.extractionTransport()
        bundle?.takeIf { it.generation == transport.generation }?.let { return it }
        runCatching { bundle?.cipher?.dispose() }
        val store = RemotePlayerConfigStore(transport.httpClient, FileConfigRepository, logger)
        val cipher = YouTubeCipherService(transport.httpClient, store, logger)
        val parser = YtConfigParserImpl(transport.httpClient, transport.innerTube, store, logger).withEmbeddedFallback()
        Bundle(transport.generation, cipher, InnerTubeExtractor(parser, cipher, transport.innerTube, tokenProvider = null, logger = logger))
            .also { bundle = it }
    }

    private fun YtConfigParser.withEmbeddedFallback(): YtConfigParser = object : YtConfigParser by this {
        override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
            try {
                this@withEmbeddedFallback.fetchConfig(videoId, useLoginCookies)
            } catch (_: IllegalStateException) {
                this@withEmbeddedFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            }
    }

    private object FileConfigRepository : PlayerConfigRepository {
        private const val URL = "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
        private val file = Paths.dir.resolve("player_config.properties")
        private val props = Properties().apply { if (file.exists()) file.inputStream().use(::load) }

        private fun get(k: String) = props.getProperty(k, "")
        private fun set(k: String, v: String) = synchronized(props) {
            props.setProperty(k, v)
            file.outputStream().use { props.store(it, null) }
        }

        override val enabled = true
        override val sourceUrl = URL
        override val defaultSourceUrl = URL
        override var cachedJson: String get() = get("json"); set(v) = set("json", v)
        override var cachedAtMs: Long get() = get("cachedAt").toLongOrNull() ?: 0L; set(v) = set("cachedAt", v.toString())
        override var cachedSourceUrl: String get() = get("source"); set(v) = set("source", v)
        override var cachedEtag: String get() = get("etag"); set(v) = set("etag", v)
    }
}
