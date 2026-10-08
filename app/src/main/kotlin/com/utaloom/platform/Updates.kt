package com.utaloom.platform

import com.utaloom.shared.isNewerVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.awt.Desktop
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Checks GitHub for a newer production release (drafts and pre-releases are skipped by /releases/latest). */
object Updates {
    const val REPO = "cabrata/utaloom"

    /** Set by the packaged app's launcher (-Dutaloom.version), "dev" when run from source. */
    val current: String = System.getProperty("utaloom.version") ?: "dev"

    @Serializable
    data class Release(val tag_name: String, val html_url: String, val name: String? = null, val body: String? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Latest release if it is newer than this build, null otherwise. Throws on network errors. */
    fun check(): Release? {
        if (current == "dev") return null
        val req = HttpRequest.newBuilder(URI("https://api.github.com/repos/$REPO/releases/latest"))
            .header("Accept", "application/vnd.github+json").timeout(Duration.ofSeconds(15)).build()
        val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 404) return null // no release yet
        check(res.statusCode() == 200) { "GitHub returned ${res.statusCode()}" }
        return json.decodeFromString<Release>(res.body()).takeIf { isNewerVersion(it.tag_name, current) }
    }

    fun open(url: String) {
        // Only ever open our own release pages.
        require(url.startsWith("https://github.com/$REPO/"))
        runCatching { Desktop.getDesktop().browse(URI(url)) }.onFailure { ProcessBuilder("xdg-open", url).start() }
    }
}
