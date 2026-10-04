package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import org.junit.Assert.*
import org.junit.Test

class DocumentIdentityTest {
    private val source = ChapterContent(ChapterIdentity(BookIdentity("provider:a", "book|原🌏"), "chapter:one"), "Title", "en",
        listOf(ContentParagraph("p:1", 0, " A🌏\nB|C "), ContentParagraph("p|2", 1, "끝")))
    private fun document(identity: DocumentIdentity, paragraphs: List<ExchangeParagraph> = source.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) }) =
        ExchangeDocument("untrusted-id", "Title", "Chapter", identity.targetLanguage ?: identity.sourceLanguage,
            identity.kind.name.lowercase(), paragraphs)

    @Test fun identityRetainsRawComponentsAndChecksEveryParagraphInOrder() {
        val identity = DocumentIdentities.original(source)
        DocumentIdentities.validateDocument(document(identity), identity)
        assertEquals("book|原🌏", identity.bookId)
        val changes = listOf(document(identity).copy(paragraphs = document(identity).paragraphs.reversed()),
            document(identity).copy(language = "EN"), document(identity).copy(kind = "translation"),
            document(identity).copy(paragraphs = listOf(ExchangeParagraph("p:1", " A🌏\nB|C"), ExchangeParagraph("p|2", "끝"))),
            document(identity).copy(assets = listOf(ExchangeAssetReference("assets/" + "0".repeat(64), "pdf"))))
        changes.forEach { changed -> assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.validateDocument(changed, identity) } }
        assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.original(source.copy(paragraphs = source.paragraphs.map { it.copy(ordinal = it.ordinal + 1) })) }
    }

    @Test fun framedHashSeparatesLegacyDelimiterCollisionAndRejectsInvalidUnicode() {
        // Both payloads have the same old `${id}:${text}` newline concatenation.
        val a = listOf(ExchangeParagraph("a", "x\nb:y"))
        val b = listOf(ExchangeParagraph("a", "x"), ExchangeParagraph("b", "y"))
        assertNotEquals(DocumentIdentities.paragraphHash(a), DocumentIdentities.paragraphHash(b))
        assertNotEquals(DocumentIdentities.paragraphHash(listOf(ExchangeParagraph("a:b", "c"))), DocumentIdentities.paragraphHash(listOf(ExchangeParagraph("a", "b:c"))))
        listOf(listOf(ExchangeParagraph("p", "\uD800")), listOf(ExchangeParagraph("\uDC00", "x")),
            listOf(ExchangeParagraph("p", "x"), ExchangeParagraph("p", "y"))).forEach {
            assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.paragraphHash(it) }
        }
    }

    @Test fun translatedIdentityChecksVariantAndBodyWithoutChangingLegacyArtifactHashes() {
        val artifact = TranslationArtifact(source.identity, "source-v1", "auto", "ko", "translator", "", "", "",
            listOf(TranslatedParagraph("p:1", "번역🌏"), TranslatedParagraph("p|2", "끝")))
        val identity = DocumentIdentities.translation(artifact)
        val doc = document(identity, artifact.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) })
        DocumentIdentities.validateDocument(doc, identity)
        assertEquals(artifact.artifactId, identity.artifactId); assertEquals(artifact.payloadHash, identity.payloadHash)
        assertEquals("", identity.modelId)
        for (changed in listOf(identity.copy(modelId = "other"), identity.copy(bookId = "book"), identity.copy(sourceRevision = "other"),
            identity.copy(revision = "0".repeat(64)), identity.copy(targetLanguage = "ja"))) {
            assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.validateDocument(doc, changed) }
        }
        assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.validate(DocumentIdentities.original(source).copy(targetLanguage = "en")) }
        assertThrows(IllegalArgumentException::class.java) { DocumentIdentities.validate(identity.copy(modelId = null)) }
    }
}
