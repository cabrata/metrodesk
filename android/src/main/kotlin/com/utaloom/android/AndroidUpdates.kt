package com.utaloom.platform

import android.content.Intent
import android.net.Uri
import com.utaloom.android.UtaloomApplication
import com.utaloom.shared.isNewerVersion
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Updates {
    const val REPO = "cabrata/utaloom"
    val current: String get() = System.getProperty("utaloom.version") ?: "dev"
    @Serializable
    data class Release(val tag_name: String, val html_url: String, val name: String? = null, val body: String? = null)
    private val json = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

    fun check(): Release? {
        if (current == "dev") return null
        val request = Request.Builder().url("https://api.github.com/repos/$REPO/releases/latest").header("Accept", "application/vnd.github+json").build()
        return http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "GitHub returned ${response.code}" }
            json.decodeFromString<Release>(checkNotNull(response.body).string()).takeIf { isNewerVersion(it.tag_name, current) }
        }
    }

    fun open(url: String) {
        require(url.startsWith("https://github.com/$REPO/"))
        UtaloomApplication.instance.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
