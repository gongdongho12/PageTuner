package com.dongholab.pagetuner.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfDocumentReaderTest {
    @Test
    fun createPdfTextSegmentsNormalizesAndMapsTextToPage() {
        val segments = PdfDocumentReader.createPdfTextSegments(
            documentId = "pdf-doc",
            pageIndex = 3,
            rawText = " First line\twith spacing. \n\nSecond block. ",
        )

        assertEquals(2, segments.size)
        assertEquals(3, segments.first().pageIndex)
        assertEquals(0, segments.first().indexInPage)
        assertEquals("First line with spacing.", segments.first().text)
        assertEquals("Second block.", segments.last().text)
    }

    @Test
    fun createPdfTextSegmentsSplitsLongText() {
        val longText = "word ".repeat(180).trim()

        val segments = PdfDocumentReader.createPdfTextSegments(
            documentId = "pdf-doc",
            pageIndex = 0,
            rawText = longText,
        )

        assertTrue(segments.size > 1)
        assertTrue(segments.all { it.text.length <= 520 })
    }
}
