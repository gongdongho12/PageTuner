package com.dongholab.pagetuner.core.paging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PagingModelsTest {
    private val policy = AlignedPageWindowPolicy(blockSize = 10)

    @Test
    fun pagePositionsMapToStableTenPageBlocks() {
        assertEquals(PageWindow(0, 10), policy.containing(pageIndex = 0, totalPages = 50))
        assertEquals(PageWindow(0, 10), policy.containing(pageIndex = 4, totalPages = 50))
        assertEquals(PageWindow(20, 30), policy.containing(pageIndex = 24, totalPages = 50))
        assertEquals(PageWindow(40, 43), policy.containing(pageIndex = 42, totalPages = 43))
    }

    @Test
    fun followingBlockAndFifthPageTriggerStayAligned() {
        val first = requireNotNull(policy.containing(pageIndex = 0, totalPages = 25))

        assertEquals(4, policy.triggerIndex(first, triggerPageCount = 5))
        assertEquals(PageWindow(10, 20), policy.following(first, totalPages = 25))
        assertEquals(PageWindow(20, 25), policy.following(PageWindow(10, 20), totalPages = 25))
        assertNull(policy.following(PageWindow(20, 25), totalPages = 25))
    }

    @Test
    fun listSliceClampsWithoutDroppingTheLastItems() {
        val page = ListPagePolicy.slice((1..23).toList(), requestedPageIndex = 99, pageSize = 10)

        assertEquals(listOf(21, 22, 23), page.items)
        assertEquals(2, page.pageIndex)
        assertEquals(3, page.pageCount)
        assertEquals(21, page.startItemNumber)
        assertEquals(23, page.endItemNumber)
    }
}
