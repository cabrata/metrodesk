package com.utaloom.platform

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.filechooser.FileNameExtensionFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext

private suspend fun choosePlaylistFile(save: Boolean, name: String = "playlist"): File? = withContext(Dispatchers.Swing) {
    val chooser = JFileChooser().apply {
        fileFilter = FileNameExtensionFilter("Utaloom playlist JSON", "json")
        if (save) selectedFile = File(name.replace(Regex("[^A-Za-z0-9._ -]"), "_").take(100).ifBlank { "playlist" } + ".json")
    }
    if ((if (save) chooser.showSaveDialog(null) else chooser.showOpenDialog(null)) != JFileChooser.APPROVE_OPTION) null
    else {
        val selected = chooser.selectedFile
        val file = if (save && !selected.name.endsWith(".json", true)) File(selected.path + ".json") else selected
        if (save && file.exists() && JOptionPane.showConfirmDialog(null, "Replace ${file.name}?", "Confirm overwrite", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) null
        else file
    }
}

suspend fun readPlaylistText(maxBytes: Int): String? {
    val file = choosePlaylistFile(false) ?: return null
    return withContext(Dispatchers.IO) {
        require(file.isFile && file.length() <= maxBytes) { "Choose a JSON file smaller than 10 MiB" }
        val bytes = Files.newInputStream(file.toPath()).use { it.readNBytes(maxBytes + 1) }
        require(bytes.size <= maxBytes) { "Playlist JSON is too large (10 MiB maximum)" }
        bytes.toString(Charsets.UTF_8)
    }
}

suspend fun writePlaylistText(name: String, text: String): Boolean {
    val file = choosePlaylistFile(true, name) ?: return false
    withContext(Dispatchers.IO) {
        val target = file.toPath().toAbsolutePath()
        val tmp = Files.createTempFile(target.parent, ".utaloom-", ".json")
        try {
            Files.writeString(tmp, text)
            try { Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(tmp) }
    }
    return true
}
