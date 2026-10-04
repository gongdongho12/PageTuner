package com.dongholab.pagetuner.ui

import com.dongholab.pagetuner.ui.common.EinkCollectionAnchor
import com.dongholab.pagetuner.ui.common.EinkPagingState
import com.dongholab.pagetuner.ui.common.calculateEinkAutoFitPageSize
import com.dongholab.pagetuner.ui.common.calculateEinkAutoFitPagePlan
import com.dongholab.pagetuner.ui.common.coerceEinkPageIndex
import com.dongholab.pagetuner.ui.common.resolveEinkCollectionAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EinkAutoFitPagingContainerTest {
    @Test
    fun boundedViewportUsesAllCompleteRowsWithoutAnArbitraryCap() {
        listOf(400f to 5, 600f to 8, 900f to 13, 1600f to 24).forEach { (height, expected) ->
            val size = calculateEinkAutoFitPageSize(height, 58f, 6f, 3)
            assertEquals(expected, size)
            assertTrue(60f + size * (58f + 6f) <= height)
            assertTrue("An additional complete row must not fit", 60f + (size + 1) * 64f > height)
        }
    }

    @Test
    fun onlyAnUnboundedViewportUsesTheFallback() {
        listOf(null, Float.POSITIVE_INFINITY, Float.NaN).forEach { height ->
            val plan = calculateEinkAutoFitPagePlan(height, 58f, 6f, 24, 100)
            assertEquals(5, plan.pageSize)
            assertTrue(plan.showNavigation)
            assertNull(plan.requiredHeight)
        }
        assertEquals(1, calculateEinkAutoFitPageSize(null, 58f, 6f, 0))
    }

    @Test
    fun zeroAndShortViewportsNeverComposeClippedRowsOrNavigation() {
        listOf(0f, 40f, 103f, 104f, 150f, 169f).forEach { height ->
            val plan = calculateEinkAutoFitPagePlan(height, 104f, 6f, 3, 29)
            assertEquals(0, plan.pageSize)
            assertFalse(plan.showNavigation)
            assertEquals(170f, plan.requiredHeight)
        }
    }

    @Test
    fun navigationAndOneRowFitExactlyAtTheBoundary() {
        val plan = calculateEinkAutoFitPagePlan(170f, 104f, 6f, 3, 29)
        assertEquals(1, plan.pageSize)
        assertTrue(plan.showNavigation)
        assertNull(plan.requiredHeight)
    }

    @Test
    fun oneItemNeedsNoNavigationButMustStillFitCompletely() {
        val fitted = calculateEinkAutoFitPagePlan(104f, 104f, 6f, 3, 1)
        assertEquals(1, fitted.pageSize)
        assertFalse(fitted.showNavigation)
        assertNull(fitted.requiredHeight)
        val tooShort = calculateEinkAutoFitPagePlan(103f, 104f, 6f, 3, 1)
        assertEquals(0, tooShort.pageSize)
        assertEquals(104f, tooShort.requiredHeight)
    }

    @Test
    fun allItemsCanUseNavigationSpaceWhenTheyFitWithoutIt() {
        val plan = calculateEinkAutoFitPagePlan(110f, 52f, 6f, 3, 2)
        assertEquals(2, plan.pageSize)
        assertFalse(plan.showNavigation)
        val short = calculateEinkAutoFitPagePlan(109f, 52f, 6f, 3, 2)
        assertEquals(0, short.pageSize)
        assertEquals(110f, short.requiredHeight)
    }

    @Test
    fun fontScaledNavigationHeightIsReservedInThePlan() {
        val plan = calculateEinkAutoFitPagePlan(390f, 104f, 6f, 3, 29, 104f)
        assertEquals(2, plan.pageSize)
        assertTrue(plan.showNavigation)
        assertTrue(104f + plan.pageSize * 110f <= 390f)
        val short = calculateEinkAutoFitPagePlan(213f, 104f, 6f, 3, 29, 104f)
        assertEquals(0, short.pageSize)
        assertEquals(214f, short.requiredHeight)
    }

    @Test
    fun emptyListsDoNotRequestMoreSpace() {
        val plan = calculateEinkAutoFitPagePlan(0f, 104f, 6f, 3, 0)
        assertFalse(plan.showNavigation)
        assertNull(plan.requiredHeight)
    }

    @Test
    fun normalPagesNeverOverflowAndUseAllAvailableRows() {
        for (height in 0..1600) {
            val plan = calculateEinkAutoFitPagePlan(height.toFloat(), 104f, 6f, 3, 29)
            if (plan.requiredHeight == null) {
                val used = if (plan.showNavigation) 60f + plan.pageSize * 110f
                else plan.pageSize * 110f - 6f
                assertTrue("$height: $used", used <= height)
                assertTrue("$height: unused complete row", used + 110f > height)
            }
        }
    }

    @Test
    fun refreshWithEnoughItemsKeepsTheRequestedViewportPage() {
        assertEquals(4, coerceEinkPageIndex(4, 30, 5))
        assertEquals(1, coerceEinkPageIndex(4, 7, 5))
        assertEquals(0, coerceEinkPageIndex(4, 0, 5))
    }

    @Test
    fun resizeKeepsTheSameAnchorVisibleWithoutDriftingOnRepeatedResize() {
        val items = (0 until 100).toList()
        val state = EinkPagingState()
        state.moveTo(4, items, 5) { it }
        listOf(8, 3, 13, 4, 5).forEach { size ->
            val anchor = state.resolve(items, size) { it }
            assertEquals(20, anchor.index)
            val page = anchor.index / size
            assertTrue(20 in page * size until (page + 1) * size)
            state.rememberAnchor(anchor, page)
        }
        assertEquals(4, state.currentPageIndex)
    }

    @Test
    fun keyedRefreshFindsTheVisibleItemAfterInsertionAndReorder() {
        val items = listOf("a", "b", "c", "d", "e", "f")
        val state = EinkPagingState()
        state.moveTo(1, items, 3) { it }
        val updated = listOf("new", "f", "e", "a", "b", "c", "d")
        val anchor = state.resolve(updated, 3) { it }
        assertEquals(EinkCollectionAnchor(6, "d"), anchor)
    }

    @Test
    fun deletedAnchorFallsBackToNearestSurvivingPosition() {
        val anchor = resolveEinkCollectionAnchor(EinkCollectionAnchor(8, "deleted"), listOf("a", "b", "c"), 2, 4) { it }
        assertEquals(EinkCollectionAnchor(2, "c"), anchor)
    }

    @Test
    fun unkeyedRefreshAndResizeKeepTheItemIndex() {
        val anchor = resolveEinkCollectionAnchor(EinkCollectionAnchor(20), (0 until 50).toList(), 8, 4, null)
        assertEquals(20, anchor.index)
        assertEquals(2, anchor.index / 8)
    }

    @Test
    fun explicitPageTurnAndResetReplaceThePreviousAnchor() {
        val items = (0 until 100).toList()
        val state = EinkPagingState()
        state.moveTo(4, items, 5) { it }
        val resized = state.resolve(items, 8) { it }
        state.rememberAnchor(resized, resized.index / 8)
        state.moveTo(3, items, 8) { it }
        assertEquals(24, state.resolve(items, 8) { it }.index)
        state.reset()
        assertEquals(0, state.resolve(items, 8) { it }.index)
    }
}
