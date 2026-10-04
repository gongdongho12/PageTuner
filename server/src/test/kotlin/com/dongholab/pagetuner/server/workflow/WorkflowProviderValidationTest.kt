package com.dongholab.pagetuner.server.workflow

import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowProviderValidationTest {
    private val chapter = StoredChapter(UUID.randomUUID(), "source", "book", "Book", "", "chapter", "Chapter", "", "en", "revision",
        listOf(SourceParagraph("p1", 0, "The quiet garden.")), Instant.EPOCH)
    @Test fun `equivalent source languages and automatic target are rejected regardless of case`() {
        val providers = WorkflowProviders()
        for (target in listOf("EN", "en", "AUTO", "Auto")) {
            assertThrows(IllegalArgumentException::class.java) {
                providers.resolve(CreateTranslationJobRequest(chapter.recordId, "GOOGLE_WEB_TRANSLATE_HTML", target, UUID.randomUUID()), chapter)
            }
        }
        val (settings, key) = providers.resolve(CreateTranslationJobRequest(chapter.recordId, "GOOGLE_WEB_TRANSLATE_HTML", "pt-br", UUID.randomUUID(), sourceLanguage = "AUTO"), chapter)
        assertEquals("pt-BR", settings.targetLanguage); assertEquals("auto", settings.sourceLanguage); assertEquals("", key)
    }
}
