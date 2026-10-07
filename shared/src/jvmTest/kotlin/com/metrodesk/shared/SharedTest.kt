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
    fun validatesCookie() {
        assertNull(cookieError("a=1; SAPISID=x"))
        assertNotNull(cookieError("SAPISID=x\nEvil: y"))
        assertNotNull(cookieError("a=1"))
    }
}
