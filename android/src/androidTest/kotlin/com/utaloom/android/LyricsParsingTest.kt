package com.utaloom.android

import com.utaloom.shared.parseLrc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android's ICU regex is stricter than the JVM's, so shared lyric parsing must run on-device. */
class LyricsParsingTest {
    @Test fun lrcParsesOnAndroidRegexEngine() {
        val l = parseLrc("test", "[00:01.50]Never gonna give you up\n[00:03.00]{bg}Never gonna let you down\n[01:02.345]<00:01.50>word")
        assertTrue(l.synced)
        assertEquals(1500L, l.lines.first().timeMs)
        assertEquals(62345L, l.lines.last().timeMs)
    }

    @Test fun lyricParserRegexHoldersInitialize() {
        // These lyric parser/provider objects compile their regex fields during initialization.
        listOf("com.utaloom.shared.SharedKt", "com.utaloom.kugou.KuGou", "com.utaloom.music.betterlyrics.TTMLParser")
            .forEach { Class.forName(it, true, javaClass.classLoader) }
    }
}
