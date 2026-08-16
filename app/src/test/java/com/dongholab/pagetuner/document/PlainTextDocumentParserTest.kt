package com.dongholab.pagetuner.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlainTextDocumentParserTest {
    @Test
    fun parsesMarkdownIntoStablePages() {
        val document = PlainTextDocumentParser.parse(
            title = "Trip Notes.md",
            rawText = """
                # Chapter One

                First paragraph for translation.

                [Second paragraph](https://example.com) keeps readable text.
            """.trimIndent(),
            format = DocumentFormat.MARKDOWN,
        )

        assertEquals("Trip Notes.md", document.title)
        assertEquals(DocumentFormat.MARKDOWN, document.format)
        assertTrue(document.pages.isNotEmpty())
        assertTrue(document.pages.first().plainText.contains("Chapter One"))
        assertTrue(document.pages.first().plainText.contains("Second paragraph"))
    }

    @Test
    fun splitsLongTextAcrossMultiplePages() {
        val longText = (1..80).joinToString(separator = "\n\n") {
            "Paragraph $it has enough text to make pagination predictable for an e-ink reader."
        }

        val document = PlainTextDocumentParser.parse(
            title = "Long",
            rawText = longText,
        )

        assertTrue(document.pageCount > 1)
        assertEquals(0, document.pages.first().index)
        assertEquals(1, document.pages[1].index)
        assertTrue(document.pages.all { page -> page.plainText.length <= 1_100 })
    }

    @Test
    fun denseReflowKeepsLegacyTranslationSegmentIds() {
        val paragraphs = listOf("A".repeat(350), "B".repeat(350), "C".repeat(350))
        val title = "Stable cache"
        val rawText = paragraphs.joinToString("\n\n")
        val document = PlainTextDocumentParser.parse(title = title, rawText = rawText)

        assertEquals(1, document.pageCount)
        assertEquals(
            listOf(
                DocumentIds.segmentId(document.id, 0, 0, paragraphs[0]),
                DocumentIds.segmentId(document.id, 1, 0, paragraphs[1]),
                DocumentIds.segmentId(document.id, 2, 0, paragraphs[2]),
            ),
            document.pages.single().segments.map(TextSegment::id),
        )
    }

    @Test
    fun buildsTableOfContentsForChapterInput() {
        val document = PlainTextDocumentParser.parseChapters(
            title = "Book",
            chapters = listOf(
                PlainTextDocumentParser.TextChapter(
                    title = "One",
                    rawText = "Alpha paragraph.",
                ),
                PlainTextDocumentParser.TextChapter(
                    title = "Two",
                    rawText = "Beta paragraph.",
                ),
            ),
        )

        assertEquals(listOf("One", "Two"), document.tableOfContents.map { it.title })
        assertEquals("One", document.pages[0].chapterTitle)
        assertEquals("Two", document.pages[1].chapterTitle)
    }
}
