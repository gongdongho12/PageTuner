package com.dongholab.pagetuner.reader

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAnnotationExportTest {
    @Test
    fun buildsReadableTextExport() {
        val document = ReaderDocument(
            id = "book",
            title = "Example Book",
            format = DocumentFormat.TEXT,
            pages = listOf(
                ReaderPage(
                    index = 0,
                    segments = listOf(
                        TextSegment(
                            id = "segment",
                            pageIndex = 0,
                            indexInPage = 0,
                            text = "Hello",
                        ),
                    ),
                ),
            ),
        )
        val export = ReaderAnnotationExport.buildText(
            document = document,
            annotations = listOf(
                ReaderAnnotation(
                    id = "note",
                    type = ReaderAnnotationType.Note,
                    pageIndex = 0,
                    text = "Remember this.",
                    createdAtMillis = 20L,
                ),
                ReaderAnnotation(
                    id = "highlight",
                    type = ReaderAnnotationType.Highlight,
                    pageIndex = 0,
                    text = "Highlighted text.",
                    createdAtMillis = 10L,
                ),
            ),
        )

        assertTrue(export.contains("Example Book"))
        assertTrue(export.contains("[Highlight] Page 1"))
        assertTrue(export.contains("Highlighted text."))
        assertTrue(export.contains("[Note] Page 1"))
        assertTrue(export.contains("Remember this."))
    }
}
