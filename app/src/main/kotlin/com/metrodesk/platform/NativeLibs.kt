package com.metrodesk.platform

import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import java.io.File

/** Locates libVLC before [com.metrodesk.playback.Player.init] runs. */
object NativeLibs {
    private val os = System.getProperty("os.name").lowercase()
    private val isWindows = "win" in os
    private val isMac = "mac" in os

    /** Directory libVLC was found in, once [discoverVlc] succeeded. */
    var vlcPath: String? = null
        private set

    /** Returns null when libVLC is loadable, otherwise a user-facing message explaining how to fix it. */
    fun discoverVlc(): String? {
        vlcPath?.let { return null }
        if (runCatching { tryDiscover() }.getOrDefault(false)) return null
        // vlcj's JnaLibraryPathDirectoryProvider checks jna.library.path, so feed it our extra candidates one by one.
        val original = System.getProperty("jna.library.path")
        for (dir in candidates()) {
            System.setProperty("jna.library.path", listOfNotNull(dir.path, original).joinToString(File.pathSeparator))
            if (runCatching { tryDiscover() }.getOrDefault(false)) return null
        }
        if (original == null) System.clearProperty("jna.library.path") else System.setProperty("jna.library.path", original)
        return missingMessage()
    }

    private fun tryDiscover(): Boolean {
        val d = NativeDiscovery()
        return d.discover().also { if (it) vlcPath = d.discoveredPath() }
    }

    private val libName = when {
        isWindows -> "libvlc.dll"
        isMac -> "libvlc.dylib"
        else -> "libvlc.so"
    }

    private fun candidates(): List<File> {
        val env = System.getenv()
        val dirs = buildList {
            env["VLC_PATH"]?.let { add(it); add("$it/lib") }
            if (isWindows) {
                listOf("ProgramFiles", "ProgramW6432", "ProgramFiles(x86)").mapNotNullTo(this) { env[it]?.let { p -> "$p\\VideoLAN\\VLC" } }
                env["LOCALAPPDATA"]?.let { add("$it\\Programs\\VideoLAN\\VLC") }
                add("C:\\Program Files\\VideoLAN\\VLC"); add("C:\\Program Files (x86)\\VideoLAN\\VLC")
            } else if (isMac) {
                add("/Applications/VLC.app/Contents/MacOS/lib")
                add("${System.getProperty("user.home")}/Applications/VLC.app/Contents/MacOS/lib")
                add("/opt/homebrew/lib"); add("/usr/local/lib")
            } else {
                listOf("/usr/lib/x86_64-linux-gnu", "/usr/lib/aarch64-linux-gnu", "/usr/lib64", "/usr/lib", "/usr/local/lib",
                    "/app/lib", "/snap/vlc/current/usr/lib").forEach(::add)
            }
        }
        return dirs.map(::File).distinct().filter { dir ->
            File(dir, libName).exists() || (!isWindows && !isMac && dir.listFiles { f -> f.name.startsWith("libvlc.so") }?.isNotEmpty() == true)
        }
    }

    private fun missingMessage(): String = when {
        isWindows -> "VLC media player (64-bit) was not found. Install it from https://www.videolan.org/vlc/ " +
            "or set the VLC_PATH environment variable to the folder containing libvlc.dll, then restart Metrodesk."
        isMac -> "VLC was not found. Install VLC.app into /Applications (https://www.videolan.org/vlc/) " +
            "or set VLC_PATH to the folder containing libvlc.dylib, then restart Metrodesk."
        else -> "libVLC was not found. Install VLC (e.g. 'sudo apt install vlc', 'sudo dnf install vlc' or 'sudo pacman -S vlc') " +
            "or set VLC_PATH to the folder containing libvlc.so, then restart Metrodesk."
    }
}
