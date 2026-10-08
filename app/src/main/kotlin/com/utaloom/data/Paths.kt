package com.utaloom.data

import java.io.File

object Paths {
    val dir: File by lazy {
        val os = System.getProperty("os.name").lowercase()
        val home = System.getProperty("user.home")
        val base = when {
            os.contains("win") -> System.getenv("APPDATA") ?: "$home/AppData/Roaming"
            os.contains("mac") -> "$home/Library/Application Support"
            else -> System.getenv("XDG_DATA_HOME") ?: "$home/.local/share"
        }
        val dir = File(base, "utaloom")
        // One-time move of data from the old app name so settings/library/downloads survive the rename.
        val old = File(base, "metrodesk")
        if (old.isDirectory && !dir.exists() && old.renameTo(dir)) {
            // library.json stores absolute download paths, retarget them (JSON-escaped form for Windows backslashes).
            val lib = dir.resolve("library.json")
            fun esc(f: File) = f.absolutePath.replace("\\", "\\\\")
            if (lib.isFile) lib.writeText(lib.readText().replace(esc(old), esc(dir)))
        }
        dir.apply { mkdirs() }
    }
    val downloads: File get() = dir.resolve("downloads").apply { mkdirs() }
}
