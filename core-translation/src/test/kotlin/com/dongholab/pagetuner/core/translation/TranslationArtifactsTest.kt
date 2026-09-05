package com.dongholab.pagetuner.core.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranslationArtifactsTest {
    @Test
    fun segmentIdentityMatchesAcrossAppWebAndServer() {
        val app = TranslationSegmentIdentity("doc", "p-1", "en", "ko", "deepseek")
        val web = TranslationSegmentIdentity("doc", "p-1", "en", "ko", "deepseek")

        assertEquals(app.id, web.id)
    }

    @Test
    fun editedTranslationCreatesANewRevisionWithoutChangingItsVariantIdentity() {
        val original = artifact("번역문")
        val edited = artifact("수정된 번역문")

        assertEquals(original.artifactId, edited.artifactId)
        assertNotEquals(original.payloadHash, edited.payloadHash)
        assertNotEquals(original.revision, edited.revision)
    }

    private fun artifact(text: String) = TranslationArtifact(
        chapter = ChapterIdentity(BookIdentity("wtr-lab", "book-42"), "chapter-7"),
        sourceRevision = "source-r1",
        sourceLanguage = "en",
        targetLanguage = "ko",
        providerId = "deepseek",
        modelId = "deepseek-chat",
        promptRevision = "prompt-v1",
        glossaryRevision = "glossary-v2",
        paragraphs = listOf(TranslatedParagraph("p-1", text)),
    )
}
