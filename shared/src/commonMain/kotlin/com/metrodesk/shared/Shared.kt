package com.metrodesk.shared

data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(val provider: String, val synced: Boolean, val lines: List<LyricLine>, val plain: String) {
    fun currentIndex(posMs: Long): Int = if (!synced) -1 else lines.indexOfLast { it.timeMs <= posMs + 300 }
}

private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val wordTag = Regex("""<\d+:\d+[.:]\d+>""")

fun plainLyrics(provider: String, text: String) = Lyrics(provider, false, text.lines().map { LyricLine(0, it) }, text)

/** Parses LRC, including multiple time tags per line. Word timing tags are dropped. */
fun parseLrc(provider: String, lrc: String): Lyrics {
    val lines = mutableListOf<LyricLine>()
    for (raw in lrc.lines()) {
        val tags = timeTag.findAll(raw).toList()
        if (tags.isEmpty()) continue
        val text = raw.substring(tags.last().range.last + 1).replace(wordTag, "").trim()
        for (t in tags) {
            val (m, s, f) = t.destructured
            val frac = when (f.length) { 0 -> 0; 1 -> f.toInt() * 100; 2 -> f.toInt() * 10; else -> f.take(3).toInt() }
            lines += LyricLine(m.toLong() * 60_000 + s.toLong() * 1000 + frac, text)
        }
    }
    lines.sortBy { it.timeMs }
    return if (lines.isEmpty()) plainLyrics(provider, lrc) else Lyrics(provider, true, lines, lines.joinToString("\n") { it.text })
}

/** Returns an error for unsafe or unusable YouTube cookie headers. */
fun cookieError(cookie: String): String? = when {
    cookie.isBlank() -> "Cookie is empty"
    cookie.length > 16_384 -> "Cookie is too long"
    '\r' in cookie || '\n' in cookie -> "Cookie must be one line"
    cookie.split(';').none { it.trim().startsWith("SAPISID=") || it.trim().startsWith("__Secure-3PAPISID=") } -> "Cookie needs SAPISID"
    else -> null
}
