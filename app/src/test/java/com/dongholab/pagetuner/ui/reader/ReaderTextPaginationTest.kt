package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import org.junit.Assert.*
import org.junit.Test

class ReaderTextPaginationTest {
    @Test fun forwardSlicesCoverEveryUtf16CodeUnitIncludingWhitespaceWithoutSplittingSurrogates() {
        val text = ("  한글😀\nEnglish words\n\n".repeat(41)) + " 마지막  "
        for (capacity in listOf(1, 2, 7, 31, 500, 20_000)) {
            val slices = mutableListOf<String>()
            var start = 0
            while (start < text.length) {
                val end = fittingTextEnd(text, start) { a, b -> text.codePointCount(a, b) <= capacity }
                assertTrue("Pagination must advance", end > start)
                assertEquals(end, safeTextBoundary(text, end))
                slices += text.substring(start, end)
                start = end
            }
            assertEquals(text, slices.joinToString(""))
        }
    }

    @Test fun previousSlicesEndAtCurrentAnchorWithoutSkippingOrRepeatingCharacters() {
        val text = "😀abc한\n\n".repeat(60)
        val slices = mutableListOf<String>()
        var end = text.length
        while (end > 0) {
            val start = fittingTextStart(text, end) { a, b -> text.codePointCount(a, b) <= 17 }
            assertTrue(start < end)
            slices.add(0, text.substring(start, end))
            end = start
        }
        assertEquals(text, slices.joinToString(""))
    }

    @Test fun impossibleViewportDoesNotPretendAnOverflowingCharacterFits() {
        assertEquals(0, fittingTextEnd("😀abc", 0) { _, _ -> false })
        assertEquals(5, fittingTextStart("😀abc", 5) { _, _ -> false })
    }

    @Test fun largeDocumentMeasurementStartsWithSmallProbes() {
        var largestProbe = 0
        val text = "가".repeat(1_000_000)
        val end = fittingTextEnd(text, 500_000) { a, b -> largestProbe = maxOf(largestProbe, b - a); b - a <= 700 }
        assertEquals(500_700, end)
        assertTrue(largestProbe <= 1024)
    }

    @Test fun fittingAcrossCanonicalPagesDoesNotMutatePageOrSegmentIdentity() {
        val document = document("first ".repeat(120), "둘째😀".repeat(200), "end")
        val originalPages = document.pages.toList()
        val display = ReaderDisplayText.original(document, emptyList())
        val end = fittingTextEnd(display.text, 0) { a, b -> b - a <= 1200 }
        assertTrue(end > display.end(0))
        val source = display.source(end)
        assertEquals(1, source.pageIndex)
        assertEquals(end - display.start(1), source.characterOffset)
        assertEquals(document.pages, originalPages)
        assertEquals(listOf("s0", "s1", "s2"), document.pages.map { it.segments.single().id })
        assertEquals(end, display.offset(source.pageIndex, source.characterOffset))
    }

    @Test fun glossaryAliasesKeepRawOffsetsAndOnlySnapInsideAnUnalignedReplacement() {
        val text = "Before Qin Feng arrived.😀 After Qin Feng."
        val glossary = listOf(BookGlossaryEntry("qin", "Qin Feng", "진풍", displayTerm = "주인공"))
        val display = ReaderDisplayText.original(document(text), glossary)
        val displayedAfter = display.text.indexOf("arrived")
        assertEquals(text.indexOf("arrived"), display.source(displayedAfter).characterOffset)
        assertEquals(displayedAfter, display.offset(0, text.indexOf("arrived")))
        assertEquals(text.indexOf("Qin Feng"), display.source(display.text.indexOf("주인공") + 1).characterOffset)
        assertEquals(text.length, display.source(display.text.length).characterOffset)
    }

    @Test fun liveTranslationUsesSegmentIdentityWithoutInventingCharacterAlignment() {
        val page = ReaderPage(0, listOf(TextSegment("a", 0, 0, "Long source paragraph"), TextSegment("b", 0, 1, "Another")))
        val translation = PageTranslation(page, "en", "ko", listOf(TranslatedSegment("a", "번역".repeat(200)),
            TranslatedSegment("b", "두 번째 번역")), false)
        val display = ReaderTranslatedDisplayText.create(page, translation, emptyList())
        assertEquals(0, display.offsetForSource(8))
        assertEquals(8, display.sourceOffset(210, 8))
        assertEquals(23, display.sourceOffset(display.text.indexOf("두 번째"), 8))
    }

    private fun document(vararg texts: String) = ReaderDocument("stable-id", "Book", DocumentFormat.TEXT,
        texts.mapIndexed { index, text -> ReaderPage(index, listOf(TextSegment("s$index", index, 0, text))) })
}
