package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingTranslationPolicyTest {
    private val policy = RollingTranslationPolicy(windowSize = 10, triggerPageCount = 5)

    @Test
    fun startsWithTenPagesAndTriggersHalfwayThrough() {
        val window = requireNotNull(policy.initialWindow(currentPageIndex = 0, totalPages = 37))

        assertEquals((0 until 10).toList(), window.pageIndexes)
        assertEquals(4, window.triggerPageIndex)
        assertEquals(10, window.nextWindowStartIndex)
    }

    @Test
    fun requestsNextTenOnlyAfterReaderPassesFivePageTrigger() {
        val state = stateFor(requireNotNull(policy.initialWindow(0, 37)))

        assertNull(policy.nextWindow(currentPageIndex = 3, totalPages = 37, state = state))
        val next = requireNotNull(policy.nextWindow(currentPageIndex = 4, totalPages = 37, state = state))

        assertEquals((10 until 20).toList(), next.pageIndexes)
        assertEquals(14, next.triggerPageIndex)
    }

    @Test
    fun largeJumpUsesTheAlignedBlockContainingTheReaderPage() {
        val state = stateFor(requireNotNull(policy.initialWindow(0, 100)))
        val next = requireNotNull(policy.nextWindow(currentPageIndex = 42, totalPages = 100, state = state))

        assertEquals((40 until 50).toList(), next.pageIndexes)
        assertEquals(44, next.triggerPageIndex)
    }

    @Test
    fun pageTwentyFiveLoadsPagesTwentyOneThroughThirty() {
        val state = stateFor(requireNotNull(policy.initialWindow(0, 100)))
        val next = requireNotNull(policy.nextWindow(currentPageIndex = 24, totalPages = 100, state = state))

        assertEquals((20 until 30).toList(), next.pageIndexes)
        assertEquals(24, next.triggerPageIndex)
    }

    @Test
    fun finalWindowIsBoundedByDocumentEnd() {
        val window = requireNotNull(policy.initialWindow(currentPageIndex = 23, totalPages = 27))

        assertEquals((20 until 27).toList(), window.pageIndexes)
        assertEquals(24, window.triggerPageIndex)
        assertEquals(27, window.nextWindowStartIndex)
    }

    private fun stateFor(window: RollingTranslationWindow) = RollingTranslationState(
        enabled = true,
        windowSize = policy.windowSize,
        triggerPageCount = policy.triggerPageCount,
        windowStartIndex = window.startIndex,
        windowEndExclusive = window.endExclusive,
        nextWindowStartIndex = window.nextWindowStartIndex,
        triggerPageIndex = window.triggerPageIndex,
    )
}
