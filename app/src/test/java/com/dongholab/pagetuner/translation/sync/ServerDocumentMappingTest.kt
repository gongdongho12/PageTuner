package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.CachedTranslation
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationSettings
import org.junit.Assert.*
import org.junit.Test

class ServerDocumentMappingTest {
    private val settings = TranslationSettings(providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "", sourceLanguage = "en", targetLanguage = "ko")
    private val document = ReaderDocument("stable-document", "Book", DocumentFormat.TEXT, listOf(
        ReaderPage(7, listOf(TextSegment("actual-a", 7, 0, "First source paragraph."))),
        ReaderPage(20, listOf(TextSegment("actual-b", 20, 0, "Second source paragraph."))),
    ))

    @Test
    fun mapsEveryActualSegmentWithoutInventingPageBasedParagraphIdentity() {
        val mapping = ServerDocumentMapping.create(document, settings, null, "google-web-public:glossary-existing")
        assertEquals("pageturner-app", mapping.chapter.identity.book.providerId)
        assertEquals(document.id, mapping.chapter.identity.book.bookId)
        assertEquals("document", mapping.chapter.identity.chapterId)
        assertEquals(listOf("actual-a", "actual-b"), mapping.chapter.paragraphs.map { it.paragraphId })
        assertEquals(listOf(0, 1), mapping.chapter.paragraphs.map { it.ordinal })
        assertTrue(mapping.keys.all { it.providerId == "google-web-public:glossary-existing" })
        val records = mapping.keys.mapIndexed { index, key -> CachedTranslation(key, "번역 $index", 1) }.associateBy { it.key.id }
        assertEquals(mapping.chapter.sourceRevision, mapping.toArtifact(records).sourceRevision)
        assertThrows(IllegalArgumentException::class.java) { mapping.toArtifact(records - mapping.keys.last().id) }
        assertThrows(IllegalArgumentException::class.java) { mapping.toArtifact(records.mapValues { it.value.copy(text = " ") }) }
    }

    @Test
    fun blankSourceAndDuplicateSegmentsAreRejected() {
        val bad = document.copy(pages = listOf(ReaderPage(0, listOf(TextSegment("blank", 0, 0, " ")))))
        assertThrows(IllegalArgumentException::class.java) { ServerDocumentMapping.create(bad, settings, null, "provider") }
        assertThrows(IllegalArgumentException::class.java) {
            ServerDocumentMapping.create(document.copy(pages = document.pages + document.pages.first()), settings, null, "provider")
        }
    }

    @Test
    fun aliasCacheIsExplicitlyMarkedWithoutInventingHistoricalGlossarySnapshot() {
        val mapping = ServerDocumentMapping.create(document, settings, null, "provider:character-alias-v1:glossary-old")
        assertEquals("app-dynamic-alias-unrecorded-v1", mapping.variant.glossaryRevision)
        assertTrue(mapping.variant.promptRevision.endsWith(":character-alias-v1"))
        assertEquals("provider:character-alias-v1:glossary-old", mapping.variant.translationProviderId)
    }
}
