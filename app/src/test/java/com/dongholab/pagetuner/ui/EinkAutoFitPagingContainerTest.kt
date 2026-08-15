package com.dongholab.pagetuner.ui

import com.dongholab.pagetuner.ui.common.calculateEinkAutoFitPageSize
import com.dongholab.pagetuner.ui.common.calculateEinkAutoFitPagePlan
import com.dongholab.pagetuner.ui.common.coerceEinkPageIndex
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

    @Test
    fun refreshWithEnoughItemsKeepsTheRequestedViewportPage() {
        assertEquals(4, coerceEinkPageIndex(requestedPageIndex = 4, itemCount = 30, pageSize = 5))
    }

    @Test
    fun shorterFilteredResultsClampToTheLastReachablePage() {
        assertEquals(1, coerceEinkPageIndex(requestedPageIndex = 4, itemCount = 7, pageSize = 5))
        assertEquals(0, coerceEinkPageIndex(requestedPageIndex = 4, itemCount = 0, pageSize = 5))
    }

    @Test
    fun singleItem_doesNotWasteSpaceOnPaginationControls() {
        val plan = calculateEinkAutoFitPagePlan(
            viewportHeightDp = 104f,
            itemHeightDp = 104f,
            itemSpacingDp = 6f,
            fallbackPageSize = 3,
            itemCount = 1,
        )

        assertEquals(1, plan.pageSize)
        assertEquals(false, plan.showNavigation)
    }

    @Test
    fun crampedViewport_prioritizesOneVisibleItemOverNavigationOnlyPage() {
        val plan = calculateEinkAutoFitPagePlan(
            viewportHeightDp = 150f,
            itemHeightDp = 104f,
            itemSpacingDp = 6f,
            fallbackPageSize = 3,
            itemCount = 29,
        )

        assertEquals(1, plan.pageSize)
        assertEquals(true, plan.showNavigation)
        assertEquals(true, plan.navigationAfterItems)
    }

    @Test
    fun normalPagedViewport_reservesNavigationAndNeverOverflows() {
        val viewportHeight = 390f
        val plan = calculateEinkAutoFitPagePlan(
            viewportHeightDp = viewportHeight,
            itemHeightDp = 104f,
            itemSpacingDp = 6f,
            fallbackPageSize = 3,
            itemCount = 29,
        )

        assertEquals(3, plan.pageSize)
        assertEquals(true, plan.showNavigation)
        assertEquals(false, plan.navigationAfterItems)
        val renderedHeight = 60f + plan.pageSize * (104f + 6f)
        assertTrue(renderedHeight <= viewportHeight)
    }
}
