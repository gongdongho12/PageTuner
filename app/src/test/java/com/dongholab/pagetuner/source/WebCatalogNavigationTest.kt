package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCatalogNavigationTest {

    @Test
    fun pageClamping_boundsPageWithinValidRange() {
        val totalPages = 8586
        val targetBelow = (-5).coerceIn(1, totalPages)
        val targetAbove = 10000.coerceIn(1, totalPages)
        val targetValid = 42.coerceIn(1, totalPages)

        assertEquals(1, targetBelow)
        assertEquals(totalPages, targetAbove)
        assertEquals(42, targetValid)
    }

    @Test
    fun quickStepCalculations_stepAccurately() {
        val currentPage = 50
        val totalPages = 8586

        val stepMinus50 = (currentPage - 50).coerceIn(1, totalPages)
        val stepMinus10 = (currentPage - 10).coerceIn(1, totalPages)
        val stepPlus10 = (currentPage + 10).coerceIn(1, totalPages)
        val stepPlus50 = (currentPage + 50).coerceIn(1, totalPages)

        assertEquals(1, stepMinus50) // clamped to 1
        assertEquals(40, stepMinus10)
        assertEquals(60, stepPlus10)
        assertEquals(100, stepPlus50)
    }

    @Test
    fun pagingStateNavigation_detectsBoundaries() {
        val metadataFirstPage = PageMetadata(
            currentPage = 1,
            totalPages = 8586,
            pageItemCount = 10,
            totalItems = 85860,
        )

        assertFalse(metadataFirstPage.hasPreviousPage)
        assertTrue(metadataFirstPage.hasNextPage)

        val metadataMiddlePage = PageMetadata(
            currentPage = 42,
            totalPages = 8586,
            pageItemCount = 10,
            totalItems = 85860,
        )

        assertTrue(metadataMiddlePage.hasPreviousPage)
        assertTrue(metadataMiddlePage.hasNextPage)

        val metadataLastPage = PageMetadata(
            currentPage = 8586,
            totalPages = 8586,
            pageItemCount = 10,
            totalItems = 85860,
        )

        assertTrue(metadataLastPage.hasPreviousPage)
        assertFalse(metadataLastPage.hasNextPage)
    }
}
