package com.tabexplorer.share

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NamesTest {
    @Test fun parentPartsAreRefused() {
        assertNull(Names.safeRel("../x", false))
        assertNull(Names.safeRel("a/../x", false))
        assertNull(Names.safeRel("a//b", false))
        assertNull(Names.safeRel("", false))
        assertNull(Names.safeRel((1..65).joinToString("/") { "d" }, false))
    }
    @Test fun controlCharactersAndSlashesAreCleaned() {
        assertEquals(listOf("a", "bc"), Names.safeRel("a/b\u0001c", false))
        assertEquals("a_b", Names.safePart("a\\b".replace("\\", "/"), false))
    }
    @Test fun fatRulesOnlyOnFat() {
        assertEquals("a_b_c", Names.safePart("a:b?c", true))
        assertEquals("a:b?c", Names.safePart("a:b?c", false))
        assertEquals("x", Names.safePart("x. . ", true))
        assertEquals("x. ", Names.safePart("x. ", false))
    }
    @Test fun uniqueNames() {
        val taken = setOf("a.txt", "a (2).txt", "dir")
        assertEquals("a (3).txt", Names.uniqueName({ it in taken }, "a.txt"))
        assertEquals("dir (2)", Names.uniqueName({ it in taken }, "dir"))
        assertEquals(".hidden (2)", Names.uniqueName({ it == ".hidden" }, ".hidden"))
    }
    @Test fun deviceNames() {
        assertEquals("Office", Names.cleanName("  Office\u0000\n"))
        assertEquals(40, Names.cleanName("x".repeat(100)).length)
    }
}
