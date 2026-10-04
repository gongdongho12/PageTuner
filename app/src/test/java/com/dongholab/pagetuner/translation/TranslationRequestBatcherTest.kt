package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationRequestBatcherTest {
    @Test
    fun combinesSegmentsFromTenPagesIntoOneRequest() {
        val segments = (0 until 10).map(::segment)

        val batches = TranslationRequestBatcher.batch(segments)

        assertEquals(1, batches.size)
        assertEquals((0 until 10).toList(), batches.single().map(TextSegment::pageIndex))
    }

    @Test
    fun splitsOnlyWhenTheSafeCharacterLimitWouldBeExceeded() {
        val segments = listOf(
            segment(0, "a".repeat(12_000)),
            segment(1, "b".repeat(12_001)),
            segment(2, "short"),
        )

        val batches = TranslationRequestBatcher.batch(segments)

        assertEquals(listOf(1, 2), batches.map(List<TextSegment>::size))
        assertTrue(batches.flatten().map(TextSegment::id) == segments.map(TextSegment::id))
    }

    private fun segment(pageIndex: Int, text: String = "Page ${pageIndex + 1}") = TextSegment(
        id = "segment-$pageIndex",
        pageIndex = pageIndex,
        indexInPage = 0,
        text = text,
    )
}
