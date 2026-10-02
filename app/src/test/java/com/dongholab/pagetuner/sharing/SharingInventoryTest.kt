package com.dongholab.pagetuner.sharing

import org.junit.Assert.*
import org.junit.Test

class SharingInventoryTest {
    @Test fun selectedDocumentLookupsDoNotReopenOtherArchivesAndRefreshReplacesDescriptors() {
        var scans = 0
        var ids = listOf("a", "b")
        val index = SharingInventory<String>({ it }) { scans++; ids }
        assertEquals("a", index.find("a"))
        repeat(5) { assertEquals("b", index.find("b")); assertNull(index.find("unknown")) }
        assertEquals(1, scans)
        ids = listOf("c")
        assertEquals(listOf("c"), index.refresh())
        assertNull(index.find("a"))
        assertEquals("c", index.find("c"))
        assertEquals(2, scans)
    }
}
