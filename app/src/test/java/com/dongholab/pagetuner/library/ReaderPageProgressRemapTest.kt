package com.dongholab.pagetuner.library

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPageProgressRemapTest {
    @Test
    fun preservesApproximateProgressWhenDenseReflowReducesPageCount() {
        assertEquals(
            5,
            remapReaderPageIndex(
                currentPageIndex = 10,
                previousPageCount = 16,
                newPageCount = 9,
            ),
        )
    }

    @Test
    fun keepsFirstAndLastPageStable() {
        assertEquals(0, remapReaderPageIndex(0, 16, 9))
        assertEquals(8, remapReaderPageIndex(15, 16, 9))
    }
}
