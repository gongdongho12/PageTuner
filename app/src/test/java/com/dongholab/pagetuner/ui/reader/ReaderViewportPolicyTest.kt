package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.document.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewportPolicyTest {
    @Test
    fun fullScreenRemovesChromeAndCapsPaperMargin() {
        val policy = readerViewportPolicy(fullScreen = true, configuredPageMarginDp = 24)

        assertFalse(policy.showChrome)
        assertEquals(0, policy.rootPaddingDp)
        assertEquals(8, policy.pageMarginDp)
    }

    @Test
    fun translationAndCatalogWorkNeverBlockPageTurns() {
        assertFalse(
            isReaderPageTurnBlocked(
                libraryBusy = false,
                translationBusy = true,
                catalogBusy = true,
            ),
        )
        assertTrue(
            isReaderPageTurnBlocked(
                libraryBusy = true,
                translationBusy = false,
                catalogBusy = false,
            ),
        )
    }

    @Test
    fun staleTranslationIsNotShownOnAChangedPage() {
        val first = page(0)
        val second = page(1)
        val translation = PageTranslation(
            page = first,
            sourceLanguage = "en",
            targetLanguage = "ko",
            segments = emptyList(),
            completedFromCache = true,
        )

        assertEquals(translation, translation.forReaderPage(first))
        assertNull(translation.forReaderPage(second))
    }

    private fun page(index: Int) = ReaderPage(
        index = index,
        segments = listOf(
            TextSegment(
                id = "page-$index",
                pageIndex = index,
                indexInPage = 0,
                text = "text",
            ),
        ),
    )
}
