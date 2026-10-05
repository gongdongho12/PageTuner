package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import org.junit.Assert.*
import org.junit.Test

class LibraryOrganizationSnapshotTest {
    private val source = ChapterContent(
        ChapterIdentity(BookIdentity("provider:a", "book|原🌏"), "chapter:one"), "Title", "en",
        listOf(ContentParagraph("p:1", 0, "  Café e\u0301🌏\r\nbody  "), ContentParagraph("p|2", 1, ""),
            ContentParagraph("p:3", 2, "끝")),
    )
    private val identity = DocumentIdentities.original(source)
    private val empty = LibraryOrganizationSnapshotValue("", emptyList(), false)
    private fun present(value: LibraryOrganizationSnapshotValue = empty) = LibraryOrganizationSnapshot(
        identity = identity, presence = LibraryOrganizationSnapshotPresence.PRESENT, organization = value,
    )
    private fun document() = ExchangeDocument(
        "untrusted-local-id", "Display title", "Displayed chapter", "en", "original",
        source.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) },
        organization = ExchangeOrganization(" Legacy folder ", listOf(" Legacy tag "), true),
        extensionsJson = """{"opaque":{"label":"keep"}}""",
    )
    private fun reject(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)

    @Test fun absentAndExplicitlyPresentEmptyAreDistinctWithoutADeletedState() {
        val absent = present().copy(presence = LibraryOrganizationSnapshotPresence.ABSENT, organization = null)
        LibraryOrganizationSnapshotValidation.validate(absent)
        LibraryOrganizationSnapshotValidation.validate(present())
        assertNotEquals(absent, present())
        assertEquals(listOf("ABSENT", "PRESENT"), LibraryOrganizationSnapshotPresence.values().map { it.name })
        reject { LibraryOrganizationSnapshotValidation.validate(absent.copy(organization = empty)) }
        reject { LibraryOrganizationSnapshotValidation.validate(present().copy(organization = null)) }
        reject { LibraryOrganizationSnapshotValidation.validate(present().copy(version = 2)) }
    }

    @Test fun exactIdentityAndOrganizationSurviveWithoutChangingLegacyDocumentFields() {
        val tags = listOf("fantasy", "Fantasy", "Ｆａｎｔａｓｙ", "é", "e\u0301", "한국어🌏", "\u200btag\u200b")
        val organization = LibraryOrganizationSnapshotValue("書架  A\u00a0B\ufeffC", tags, true)
        val snapshot = present(organization)
        val document = document()
        val before = document.copy()
        LibraryOrganizationSnapshotValidation.validateDocument(document, snapshot)
        assertEquals(identity, snapshot.identity)
        assertEquals("book|原🌏", snapshot.identity.bookId)
        assertEquals(organization, snapshot.organization)
        assertEquals(tags, snapshot.organization!!.tags)
        assertEquals("書架  A\u00a0B\ufeffC", snapshot.organization!!.folder)
        assertEquals(before, document)
        assertEquals(" Legacy folder ", document.organization.folder)
        assertEquals(listOf(" Legacy tag "), document.organization.tags)
    }

    @Test fun structuralValidationDoesNotSubstituteForDocumentProof() {
        val wrongHash = present().copy(identity = identity.copy(paragraphHash = "0".repeat(64)))
        LibraryOrganizationSnapshotValidation.validate(wrongHash)
        reject { LibraryOrganizationSnapshotValidation.validateDocument(document(), wrongHash) }
        reject { LibraryOrganizationSnapshotValidation.validate(present().copy(identity = identity.copy(version = 2))) }
        reject { LibraryOrganizationSnapshotValidation.validate(present().copy(identity = identity.copy(paragraphHash = "bad"))) }
        reject { LibraryOrganizationSnapshotValidation.validate(present().copy(identity = identity.copy(targetLanguage = "ko"))) }
    }

    @Test fun inclusiveBoundsCountUtf16AndRejectDuplicateOrEmptyTagsInsteadOfRepairing() {
        val tags = listOf("🌏".repeat(30)) + (1 until 32).map { "tag-$it".padEnd(60, 'x') }
        val boundary = LibraryOrganizationSnapshotValue("🌏".repeat(100), tags, true)
        LibraryOrganizationSnapshotValidation.validate(present(boundary))
        assertEquals(200, boundary.folder.length)
        assertEquals(32, boundary.tags.size)
        val invalid = listOf(boundary.copy(folder = boundary.folder + "x"), boundary.copy(tags = tags + "extra"),
            empty.copy(tags = listOf("🌏".repeat(30) + "x")), empty.copy(tags = listOf("")),
            empty.copy(tags = listOf("exact", "exact")))
        invalid.forEach { value -> reject { LibraryOrganizationSnapshotValidation.validate(present(value)) } }
        LibraryOrganizationSnapshotValidation.validate(present(empty.copy(tags = listOf("A", "a"))))
    }

    @Test fun everyEcmaTrimEdgeIsRejectedWithoutNormalizingNames() {
        val trimCharacters = ('\u0009'..'\u000d').toList() + listOf('\u0020', '\u00a0', '\u1680') +
            ('\u2000'..'\u200a').toList() + listOf('\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff')
        trimCharacters.forEach { char ->
            listOf("${char}name", "name$char").forEach { value ->
                reject { LibraryOrganizationSnapshotValidation.validate(present(empty.copy(folder = value))) }
                reject { LibraryOrganizationSnapshotValidation.validate(present(empty.copy(tags = listOf(value)))) }
            }
        }
        LibraryOrganizationSnapshotValidation.validate(present(empty.copy(folder = "A\u3000B", tags = listOf("A\u2028B"))))
    }

    @Test fun controlsAndMalformedSurrogatesAreRejectedEverywhereInNames() {
        val controls = (0..31).toList() + (127..159).toList()
        val invalid = controls.map { "a${it.toChar()}b" } + listOf("\ud800", "\udfff", "a\ud800b", "\udfff\ud800")
        invalid.forEach { value ->
            reject { LibraryOrganizationSnapshotValidation.validate(present(empty.copy(folder = value))) }
            reject { LibraryOrganizationSnapshotValidation.validate(present(empty.copy(tags = listOf(value)))) }
        }
    }

    @Test fun documentProofRejectsChangedParagraphsIdsOrderLanguageKindsAndAssets() {
        val document = document()
        val changed = listOf(
            document.copy(paragraphs = document.paragraphs.reversed()),
            document.copy(paragraphs = document.paragraphs.drop(1)),
            document.copy(paragraphs = document.paragraphs.map { it.copy(paragraphId = it.paragraphId + "x") }),
            document.copy(paragraphs = document.paragraphs.map { it.copy(text = it.text + " ") }),
            document.copy(language = "EN"), document.copy(kind = "local"), document.copy(kind = "translation"),
            document.copy(assets = listOf(ExchangeAssetReference("assets/" + "0".repeat(64), "pdf"))),
        )
        changed.forEach { reject { LibraryOrganizationSnapshotValidation.validateDocument(it, present()) } }
        reject { LibraryOrganizationSnapshotValidation.validateDocument(document, present().copy(identity = identity.copy(sourceRevision = "0".repeat(64)))) }
        val absent = present().copy(presence = LibraryOrganizationSnapshotPresence.ABSENT, organization = null)
        reject { LibraryOrganizationSnapshotValidation.validateDocument(changed.first(), absent) }
    }

    @Test fun translationProofPreservesAndVerifiesTheCompleteTranslationVariant() {
        val artifact = TranslationArtifact(source.identity, "source-v1", "auto", "ko", "translator", "model", "prompt", "glossary",
            listOf(TranslatedParagraph("p:1", "번역🌏"), TranslatedParagraph("p|2", ""), TranslatedParagraph("p:3", "끝")))
        val translatedIdentity = DocumentIdentities.translation(artifact)
        val document = document().copy(language = "ko", kind = "translation",
            paragraphs = artifact.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) })
        val snapshot = present().copy(identity = translatedIdentity)
        LibraryOrganizationSnapshotValidation.validateDocument(document, snapshot)
        assertEquals(translatedIdentity, snapshot.identity)
        val changed = listOf(translatedIdentity.copy(contentProviderId = "other"), translatedIdentity.copy(bookId = "other"),
            translatedIdentity.copy(chapterId = "other"), translatedIdentity.copy(sourceRevision = "other"),
            translatedIdentity.copy(sourceLanguage = "en"), translatedIdentity.copy(targetLanguage = "ja"),
            translatedIdentity.copy(translationProviderId = "other"), translatedIdentity.copy(modelId = "other"),
            translatedIdentity.copy(promptRevision = "other"), translatedIdentity.copy(glossaryRevision = "other"),
            translatedIdentity.copy(artifactId = "0".repeat(64)), translatedIdentity.copy(revision = "0".repeat(64)),
            translatedIdentity.copy(payloadHash = "0".repeat(64)))
        changed.forEach { reject { LibraryOrganizationSnapshotValidation.validateDocument(document, snapshot.copy(identity = it)) } }
    }
}
