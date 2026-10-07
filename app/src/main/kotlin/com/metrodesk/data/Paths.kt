package com.metrodesk.data

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
        File(base, "metrodesk").apply { mkdirs() }
    }
    val downloads: File get() = dir.resolve("downloads").apply { mkdirs() }
}
