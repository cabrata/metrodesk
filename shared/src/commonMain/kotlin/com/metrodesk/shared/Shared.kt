package com.metrodesk.shared

data class LyricWord(val text: String, val startMs: Long, val endMs: Long)

data class LyricLine(val timeMs: Long, val text: String, val words: List<LyricWord> = emptyList(), val background: Boolean = false)

data class Lyrics(val provider: String, val synced: Boolean, val lines: List<LyricLine>, val plain: String) {
    /** Background vocals overlap the main line, so they never become the active line. */
    fun currentIndex(posMs: Long): Int = if (!synced) -1 else lines.indexOfLast { !it.background && it.timeMs <= posMs + 300 }
}

/**
 * Groups timed syllables ("Ne","ver") into the space-separated words of [text], so a karaoke view can
 * wrap only between words. Syllables not found in the text become their own word.
 */
fun lyricWordGroups(text: String, words: List<LyricWord>): List<List<LyricWord>> {
    val out = mutableListOf<MutableList<LyricWord>>()
    var cursor = 0
    var open = false
    for (w in words) {
        val t = w.text.trim()
        if (t.isEmpty()) continue
        val at = text.indexOf(t, cursor)
        if (at < 0) { out += mutableListOf(w.copy(text = t)); open = false; continue }
        // Start a new word unless this syllable directly continues the previous one.
        if (!open || at != cursor) out += mutableListOf<LyricWord>()
        out.last() += w.copy(text = t)
        cursor = at + t.length
        open = cursor < text.length && !text[cursor].isWhitespace()
    }
    return out
}

private val timeTag = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
private val wordTag = Regex("""<\d+:\d+[.:]\d+>""")
private val voiceTag = Regex("""^\{(agent:\w+|bg)}""")
private val wordBlock = Regex("""^<(.+)>$""")

fun plainLyrics(provider: String, text: String) = Lyrics(provider, false, text.lines().map { LyricLine(0, it) }, text)

/**
 * Parses LRC, including multiple time tags per line and Metrolist's extended format:
 * `{agent:v1}`/`{bg}` voice tags and a following `<word:startSec:endSec|...>` word-sync line.
 */
fun parseLrc(provider: String, lrc: String): Lyrics {
    val lines = mutableListOf<LyricLine>()
    var lastAdded = 0
    for (raw in lrc.lines()) {
        wordBlock.find(raw.trim())?.let { m ->
            val words = m.groupValues[1].split('|').mapNotNull { w ->
                val p = w.split(':')
                val start = p.getOrNull(p.size - 2)?.toDoubleOrNull()
                val end = p.lastOrNull()?.toDoubleOrNull()
                if (p.size < 3 || start == null || end == null) null else LyricWord(p.dropLast(2).joinToString(":"), (start * 1000).toLong(), (end * 1000).toLong())
            }
            for (i in lines.size - lastAdded until lines.size) lines[i] = lines[i].copy(words = words)
            lastAdded = 0
            continue
        }
        val tags = timeTag.findAll(raw).toList()
        lastAdded = 0
        if (tags.isEmpty()) continue
        var text = raw.substring(tags.last().range.last + 1).replace(wordTag, "").trim()
        val voice = voiceTag.find(text)?.value
        if (voice != null) text = text.substring(voice.length).trim()
        // ponytail: only explicitly tagged {bg} lines count as background; untagged lines continuing a bg run show as main.
        val bg = voice == "{bg}"
        for (t in tags) {
            val (m, s, f) = t.destructured
            val frac = when (f.length) { 0 -> 0; 1 -> f.toInt() * 100; 2 -> f.toInt() * 10; else -> f.take(3).toInt() }
            lines += LyricLine(m.toLong() * 60_000 + s.toLong() * 1000 + frac, text, background = bg)
            lastAdded++
        }
    }
    val sorted = lines.sortedBy { it.timeMs }
    return if (sorted.isEmpty()) plainLyrics(provider, lrc) else Lyrics(provider, true, sorted, sorted.joinToString("\n") { it.text })
}

/** Returns an error for unsafe or unusable YouTube cookie headers. */
fun cookieError(cookie: String): String? = when {
    cookie.isBlank() -> "Cookie is empty"
    cookie.length > 16_384 -> "Cookie is too long"
    '\r' in cookie || '\n' in cookie -> "Cookie must be one line"
    cookie.split(';').none { it.trim().startsWith("SAPISID=") || it.trim().startsWith("__Secure-3PAPISID=") } -> "Cookie needs SAPISID"
    else -> null
}
