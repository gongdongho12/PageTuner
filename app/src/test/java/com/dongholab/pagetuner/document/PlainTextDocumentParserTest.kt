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
    }
}
