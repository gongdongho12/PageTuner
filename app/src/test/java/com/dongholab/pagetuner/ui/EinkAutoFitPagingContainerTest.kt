package com.dongholab.pagetuner.ui

import com.dongholab.pagetuner.ui.common.calculateEinkAutoFitPageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkAutoFitPagingContainerTest {

    @Test
    fun calculatePageSize_smallMobileScreen_fitsExactItemsWithoutClipping() {
        val smallScreenHeightDp = 400
        val pageSize = calculateEinkAutoFitPageSize(smallScreenHeightDp.toFloat(), 58f, 6f, 3)

        // 340dp available / 64dp slot = 5 items strictly fit
        assertEquals(5, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= small screen height $smallScreenHeightDp dp", renderedHeight <= smallScreenHeightDp)
    }

    @Test
    fun calculatePageSize_mediumMobileScreen_fitsExactItemsWithoutClipping() {
        val mediumScreenHeightDp = 600
        val pageSize = calculateEinkAutoFitPageSize(mediumScreenHeightDp.toFloat(), 58f, 6f, 3)

        // 540dp available / 64dp slot = 8 items strictly fit
        assertEquals(8, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= medium screen height $mediumScreenHeightDp dp", renderedHeight <= mediumScreenHeightDp)
    }

    @Test
    fun calculatePageSize_largeTabletScreen_fitsExactItemsWithoutClipping() {
        val largeTabletHeightDp = 900
        val pageSize = calculateEinkAutoFitPageSize(largeTabletHeightDp.toFloat(), 58f, 6f, 3)

        assertEquals(8, pageSize)
        val renderedHeight = pageSize * 64 + 60
        assertTrue("Rendered height $renderedHeight dp must be <= large tablet height $largeTabletHeightDp dp", renderedHeight <= largeTabletHeightDp)
    }

    @Test
    fun calculatePageSize_unboundedViewport_usesSafeFallbackCap() {
        assertEquals(5, calculateEinkAutoFitPageSize(null, 58f, 6f, 24))
    }
}
