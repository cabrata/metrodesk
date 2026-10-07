package com.metrodesk.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SharedTest {
    @Test
    fun parsesLrc() {
        val l = parseLrc("t", "[00:01.50][00:03.5]Hi <00:01.60>there\n[ar:x]\n[01:02.123]Bye")
        assertEquals(listOf(1500L, 3500L, 62123L), l.lines.map { it.timeMs })
        assertEquals("Hi there", l.lines.first().text)
        assertEquals(1, l.currentIndex(3300))
    }

    @Test
    fun parsesWordSyncLrc() {
        val l = parseLrc("t", "[00:01.00]{agent:v1}Never gonna\n<Ne:1.0:1.2|ver:1.2:1.5|gonna:1.5:2.0>\n[00:01.50]{bg}(ooh)\n<ooh:1.5:1.9>\n[00:03.00]Plain")
        assertEquals("Never gonna", l.lines[0].text)
        assertEquals(listOf(1000L, 1200L, 1500L), l.lines[0].words.map { it.startMs })
        assertEquals(true, l.lines[1].background)
        assertEquals("(ooh)", l.lines[1].text)
        assertEquals(1, l.lines[1].words.size)
        assertEquals(emptyList(), l.lines[2].words)
        assertEquals(0, l.currentIndex(1700)) // bg line never becomes active
    }

    @Test
    fun validatesCookie() {
        assertNull(cookieError("a=1; SAPISID=x"))
        assertNotNull(cookieError("SAPISID=x\nEvil: y"))
        assertNotNull(cookieError("a=1"))
    }
}
