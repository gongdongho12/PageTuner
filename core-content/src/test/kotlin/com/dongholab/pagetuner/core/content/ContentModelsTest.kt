package com.dongholab.pagetuner.core.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContentModelsTest {
    private val chapter = ChapterIdentity(BookIdentity("wtr-lab", "book-42"), "chapter-7")

    @Test
    fun sourceRevisionIsStableAcrossClients() {
        val paragraphs = listOf(
            ContentParagraph("p-1", 0, "First paragraph"),
            ContentParagraph("p-2", 1, "Second paragraph"),
        )

        val appModel = ChapterContent(chapter, "Chapter 7", "en", paragraphs)
        val webModel = ChapterContent(chapter, "Chapter 7", "en", paragraphs.map { it.copy() })

        assertEquals(appModel.sourceRevision, webModel.sourceRevision)
        assertEquals("wtr-lab:book-42:chapter-7", chapter.canonicalId)
    }

    @Test
    fun sourceRevisionChangesWhenOriginalTextChanges() {
        val first = ChapterContent(
            chapter,
            "Chapter 7",
            "en",
            listOf(ContentParagraph("p-1", 0, "Original")),
        )
        val changed = first.copy(paragraphs = listOf(ContentParagraph("p-1", 0, "Changed")))

        assertNotEquals(first.sourceRevision, changed.sourceRevision)
    }
}
