package com.dongholab.pagetuner.server.translation

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TranslationBackupDocumentTest {
    private val request = SaveTranslationRequest(
        contentProviderId = "web", bookId = "book:42", chapterId = "chapter-1",
        sourceRevision = "source-v1", sourceLanguage = "en", targetLanguage = "ko",
        translationProviderId = "translator", modelId = "model", promptRevision = "prompt-v2",
        glossaryRevision = "glossary-v3",
        paragraphs = listOf(TranslatedParagraphRequest("p1", "번역문\n다음 줄")),
    )
    private val artifact = request.toArtifact()
    private val document = TranslationBackupDocument(1, artifact.artifactId, artifact.revision, artifact.payloadHash, request)

    @Test
    fun `JSON round trip preserves all metadata and translated text`() {
        val mapper = jacksonObjectMapper()
        val restored = mapper.readValue<TranslationBackupDocument>(mapper.writeValueAsBytes(document))
        assertEquals(request, restored.verifiedRequest())
    }

    @Test
    fun `modified text and metadata are rejected`() {
        listOf(
            document.copy(translation = request.copy(paragraphs = listOf(TranslatedParagraphRequest("p1", "변조")))),
            document.copy(translation = request.copy(targetLanguage = "ja")),
            document.copy(payloadHash = "invalid"),
            document.copy(revision = "invalid"),
            document.copy(artifactId = "invalid"),
            document.copy(schemaVersion = 2),
        ).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { invalid.verifiedRequest() }
        }
    }
}
