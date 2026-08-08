package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RollingTranslationPolicyTest {
    private val policy = RollingTranslationPolicy(windowSize = 10, triggerOffset = 5)

    @Test
    fun startsWithTenPagesAndTriggersHalfwayThrough() {
        val window = requireNotNull(policy.initialWindow(currentPageIndex = 0, totalPages = 37))

        assertEquals((0 until 10).toList(), window.pageIndexes)
        assertEquals(5, window.triggerPageIndex)
        assertEquals(10, window.nextWindowStartIndex)
    }

    @Test
    fun requestsNextTenOnlyAfterReaderPassesFivePageTrigger() {
        val state = stateFor(requireNotNull(policy.initialWindow(0, 37)))

        assertNull(policy.nextWindow(currentPageIndex = 4, totalPages = 37, state = state))
        val next = requireNotNull(policy.nextWindow(currentPageIndex = 5, totalPages = 37, state = state))

        assertEquals((10 until 20).toList(), next.pageIndexes)
        assertEquals(15, next.triggerPageIndex)
    }

    @Test
    fun largeJumpStartsWindowAtCurrentReaderPage() {
        val state = stateFor(requireNotNull(policy.initialWindow(0, 100)))
        val next = requireNotNull(policy.nextWindow(currentPageIndex = 42, totalPages = 100, state = state))

        assertEquals((42 until 52).toList(), next.pageIndexes)
        assertEquals(47, next.triggerPageIndex)
    }

    @Test
    fun finalWindowIsBoundedByDocumentEnd() {
        val window = requireNotNull(policy.initialWindow(currentPageIndex = 23, totalPages = 27))

        assertEquals(listOf(23, 24, 25, 26), window.pageIndexes)
        assertEquals(26, window.triggerPageIndex)
        assertEquals(27, window.nextWindowStartIndex)
    }

    private fun stateFor(window: RollingTranslationWindow) = RollingTranslationState(
        enabled = true,
        windowSize = policy.windowSize,
        triggerOffset = policy.triggerOffset,
        windowStartIndex = window.startIndex,
        windowEndExclusive = window.endExclusive,
        nextWindowStartIndex = window.nextWindowStartIndex,
        triggerPageIndex = window.triggerPageIndex,
    )
}
