package com.utaloom.data

import java.io.File

object Paths {
    lateinit var dir: File
        private set
    fun initialize(filesDir: File) { dir = File(filesDir, "utaloom").apply { mkdirs() } }
    val downloads: File get() = dir.resolve("downloads").apply { mkdirs() }
}
