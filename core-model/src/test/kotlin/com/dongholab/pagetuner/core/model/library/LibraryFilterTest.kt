package com.dongholab.pagetuner.core.model.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LibraryFilterTest {
    @Test fun absentAndExplicitEmptyFolderRemainDifferent() {
        LibraryFilter().validate()
        LibraryFilter(q = "", folder = "", favorite = false).validate()
        assertNotEquals(LibraryFilter(), LibraryFilter(folder = ""))
    }

    @Test fun boundsUseUtf16AndExactCanonicalStrings() {
        LibraryFilter("😀".repeat(100), "😀".repeat(100), "😀".repeat(30), true).validate()
        LibraryFilter("100%_\\!", "小説", "é,e\u0301").validate()
        for (filter in listOf(LibraryFilter(q = "😀".repeat(100) + "x"), LibraryFilter(folder = "x".repeat(201)),
            LibraryFilter(tag = "x".repeat(61)), LibraryFilter(tag = ""))) {
            assertThrows(IllegalArgumentException::class.java) { filter.validate() }
        }
    }

    @Test fun controlCharactersMalformedSurrogatesAndUntrimmedValuesAreRejected() {
        for (invalid in listOf(" leading", "trailing\ufeff", "\u00a0word", "x\u0000y", "x\u0085y", "\ud800", "\udc00", "\ud800x")) {
            for (filter in listOf(LibraryFilter(q = invalid), LibraryFilter(folder = invalid), LibraryFilter(tag = invalid))) {
                assertThrows(IllegalArgumentException::class.java) { filter.validate() }
            }
        }
    }

    @Test fun trimMatchesEcmaWithoutNormalizingNames() {
        assertEquals("é,e\u0301", LibraryFilter.trim("\ufeff\u00a0\t é,e\u0301 \u3000\n"))
        assertEquals("x\u200by", LibraryFilter.trim("x\u200by"))
        assertEquals("\u0085x\u0085", LibraryFilter.trim("\u0085x\u0085"))
    }
}
