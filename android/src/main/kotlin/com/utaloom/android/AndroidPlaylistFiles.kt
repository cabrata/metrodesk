package com.utaloom.platform

import com.utaloom.android.PlaylistDocuments
import com.utaloom.android.UtaloomApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

suspend fun readPlaylistText(maxBytes: Int): String? {
    val uri = PlaylistDocuments.choose(null) ?: return null
    return withContext(Dispatchers.IO) {
        val output = ByteArrayOutputStream()
        checkNotNull(UtaloomApplication.instance.contentResolver.openInputStream(uri)).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= maxBytes) { "Playlist JSON is too large (10 MiB maximum)" }
                output.write(buffer, 0, count)
            }
        }
        output.toString("UTF-8")
    }
}

suspend fun writePlaylistText(name: String, text: String): Boolean {
    // CreateDocument confirms overwrites in the system picker and returns the chosen destination.
    val uri = PlaylistDocuments.choose(name) ?: return false
    withContext(Dispatchers.IO) {
        checkNotNull(UtaloomApplication.instance.contentResolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray()) }
    }
    return true
}
