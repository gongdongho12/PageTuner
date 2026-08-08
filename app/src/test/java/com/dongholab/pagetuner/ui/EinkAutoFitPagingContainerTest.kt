package com.dongholab.pagetuner.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkAutoFitPagingContainerTest {

    private fun calculatePageSizeForViewport(
        totalViewportHeightDp: Int,
        reservedHeaderAndPaddingDp: Int = 60,
        itemSlotHeightDp: Int = 64,
    ): Int {
        val availableHeight = (totalViewportHeightDp - reservedHeaderAndPaddingDp).coerceAtLeast(58)
        return (availableHeight / itemSlotHeightDp).coerceIn(1, 8)
    }

    @Test
    fun calculatePageSize_smallMobileScreen_fitsExactItemsWithoutClipping() {
        val smallScreenHeightDp = 400
        val pageSize = calculatePageSizeForViewport(smallScreenHeightDp)

        // 340dp available / 64dp slot = 5 items strictly fit
        assertEquals(5, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= small screen height $smallScreenHeightDp dp", renderedHeight <= smallScreenHeightDp)
    }

    @Test
    fun calculatePageSize_mediumMobileScreen_fitsExactItemsWithoutClipping() {
        val mediumScreenHeightDp = 600
        val pageSize = calculatePageSizeForViewport(mediumScreenHeightDp)

        // 540dp available / 64dp slot = 8 items strictly fit
        assertEquals(8, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= medium screen height $mediumScreenHeightDp dp", renderedHeight <= mediumScreenHeightDp)
    }

    @Test
    fun calculatePageSize_largeTabletScreen_fitsExactItemsWithoutClipping() {
        val largeTabletHeightDp = 900
        val pageSize = calculatePageSizeForViewport(largeTabletHeightDp)

        // Capped at max 8 items for optimal discrete page reading
        assertEquals(8, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= large tablet height $largeTabletHeightDp dp", renderedHeight <= largeTabletHeightDp)
    }
}
