package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import org.junit.Assert.*
import org.junit.Test

class WebCatalogPageNavigationTest {
    @Test
    fun emptyCatalogDoesNotNavigate() {
        val paging = PageMetadata(currentPage = 1, totalPages = 0, totalItems = 0)
        for (requested in listOf(Int.MIN_VALUE, 0, 1, 2, Int.MAX_VALUE)) {
            assertNull(paging.catalogNavigationTarget(requested))
        }
    }

    @Test
    fun invalidNegativeTotalDoesNotNavigate() {
        assertNull(PageMetadata(totalPages = -1).catalogNavigationTarget(2))
    }

    @Test
    fun knownTotalClampsToFirstAndLastPages() {
        val paging = PageMetadata(currentPage = 3, totalPages = 8)
        assertEquals(1, paging.catalogNavigationTarget(Int.MIN_VALUE))
        assertEquals(8, paging.catalogNavigationTarget(Int.MAX_VALUE))
        assertEquals(4, paging.catalogNavigationTarget(4))
    }

    @Test
    fun currentPageAndClampedCurrentPageDoNotReload() {
        assertNull(PageMetadata(currentPage = 1, totalPages = 8).catalogNavigationTarget(0))
        assertNull(PageMetadata(currentPage = 3, totalPages = 8).catalogNavigationTarget(3))
        assertNull(PageMetadata(currentPage = 8, totalPages = 8).catalogNavigationTarget(9))
    }

    @Test
    fun unknownTotalRemainsNavigable() {
        val paging = PageMetadata(currentPage = 2, totalPages = null)
        assertEquals(1, paging.catalogNavigationTarget(0))
        assertEquals(3, paging.catalogNavigationTarget(3))
        assertEquals(Int.MAX_VALUE, paging.catalogNavigationTarget(Int.MAX_VALUE))
    }
}
