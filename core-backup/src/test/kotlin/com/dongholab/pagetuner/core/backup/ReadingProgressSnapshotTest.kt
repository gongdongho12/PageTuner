package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.*
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import org.junit.Assert.*
import org.junit.Test

class ReadingProgressSnapshotTest {
    private val source = ChapterContent(ChapterIdentity(BookIdentity("provider", "book"), "chapter"), "Title", "en",
        listOf(ContentParagraph(" p:é e\u0301 🌏 ", 0, "a🌏b"), ContentParagraph("empty", 1, "")))
    private val identity = DocumentIdentities.original(source)
    private fun document() = ExchangeDocument("id", "Book", "Chapter", "en", "original",
        source.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) })
    private fun present(id: String = source.paragraphs.first().paragraphId, offset: Int = 3) = ReadingProgressSnapshot(
        identity = identity, presence = ReadingProgressSnapshotPresence.PRESENT, anchor = ReadingProgressSnapshotAnchor(id, offset))
    private fun reject(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java, block)

    @Test fun absentAndPresentAreExplicitAndNeverProvideResetOrDelete() {
        val absent = present().copy(presence = ReadingProgressSnapshotPresence.ABSENT, anchor = null)
        ReadingProgressSnapshotValidation.validate(absent)
        assertNotEquals(absent, present())
        assertEquals(listOf("ABSENT", "PRESENT"), ReadingProgressSnapshotPresence.values().map { it.name })
        reject { ReadingProgressSnapshotValidation.validate(absent.copy(anchor = present().anchor)) }
        reject { ReadingProgressSnapshotValidation.validate(present().copy(anchor = null)) }
        reject { ReadingProgressSnapshotValidation.validate(present().copy(version = 2)) }
    }

    @Test fun offsetsUseExactUtf16IncludingParagraphEndAndPersistedEmptyText() {
        listOf(0, 1, 3, 4).forEach { ReadingProgressSnapshotValidation.validateDocument(document(), present(offset = it)) }
        ReadingProgressSnapshotValidation.validateDocument(document(), present("empty", 0))
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), present(offset = 2)) }
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), present(offset = 5)) }
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), present("empty", 1)) }
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), present("missing", 0)) }
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), present("p:é e\u0301 🌏", 0)) }
        assertEquals(" p:é e\u0301 🌏 ", present().anchor!!.paragraphId)
    }

    @Test fun structuralAnchorBoundsRejectWithoutNormalizingOrTruncating() {
        ReadingProgressSnapshotValidation.validate(present("🌏".repeat(100), Int.MAX_VALUE))
        listOf("", " ", "\ufeff", "x".repeat(201), "\ud800", "a\udfffb").forEach {
            reject { ReadingProgressSnapshotValidation.validate(present(it, 0)) }
        }
        ((0..31) + (127..159)).forEach { code ->
            reject { ReadingProgressSnapshotValidation.validate(present("a${code.toChar()}b", 0)) }
        }
        reject { ReadingProgressSnapshotValidation.validate(present(offset = -1)) }
        ReadingProgressSnapshotValidation.validate(present("\u200b", 0))
    }

    @Test fun structuralValidationIsNotDocumentProofOrAccountOwnership() {
        val wrong = present().copy(identity = identity.copy(paragraphHash = "0".repeat(64)))
        ReadingProgressSnapshotValidation.validate(wrong)
        reject { ReadingProgressSnapshotValidation.validateDocument(document(), wrong) }
        reject { ReadingProgressSnapshotValidation.validate(present().copy(identity = identity.copy(version = 2))) }
        val legacyId = "x".repeat(201)
        val longSource = source.copy(paragraphs = listOf(ContentParagraph(legacyId, 0, "text")))
        val longSnapshot = present(legacyId, 0).copy(identity = DocumentIdentities.original(longSource))
        reject { ReadingProgressSnapshotValidation.validateDocument(document().copy(paragraphs = listOf(ExchangeParagraph(legacyId, "text"))), longSnapshot) }
    }

    @Test fun completeTextProofRejectsChangedIdsOrderBodyLanguageKindAndAssets() {
        val document = document()
        val changed = listOf(document.copy(paragraphs = document.paragraphs.reversed()),
            document.copy(paragraphs = document.paragraphs.map { it.copy(paragraphId = it.paragraphId + "x") }),
            document.copy(paragraphs = document.paragraphs.map { it.copy(text = it.text + "x") }),
            document.copy(language = "EN"), document.copy(kind = "local"), document.copy(kind = "translation"),
            document.copy(assets = listOf(ExchangeAssetReference("assets/" + "0".repeat(64), "pdf"))))
        changed.forEach { reject { ReadingProgressSnapshotValidation.validateDocument(it, present()) } }
        val absent = present().copy(presence = ReadingProgressSnapshotPresence.ABSENT, anchor = null)
        reject { ReadingProgressSnapshotValidation.validateDocument(changed.first(), absent) }
    }

    @Test fun originalAndTranslationOffsetsUseTheirOwnDisplayedTextAndCompleteProvenance() {
        val artifact = TranslationArtifact(source.identity, "source-v1", "en", "ko", "translator", "model", "prompt", "glossary",
            listOf(TranslatedParagraph(source.paragraphs.first().paragraphId, "번역🌏"), TranslatedParagraph("empty", "")))
        val translated = DocumentIdentities.translation(artifact)
        val document = document().copy(language = "ko", kind = "translation",
            paragraphs = artifact.paragraphs.map { ExchangeParagraph(it.paragraphId, it.text) })
        val snapshot = present(offset = 4).copy(identity = translated)
        ReadingProgressSnapshotValidation.validateDocument(document, snapshot)
        reject { ReadingProgressSnapshotValidation.validateDocument(document, snapshot.copy(anchor = snapshot.anchor!!.copy(characterOffset = 3))) }
        val mutations = listOf(translated.copy(sourceRevision = "other"), translated.copy(targetLanguage = "ja"),
            translated.copy(translationProviderId = "other"), translated.copy(modelId = "other"), translated.copy(promptRevision = "other"),
            translated.copy(glossaryRevision = "other"), translated.copy(payloadHash = "0".repeat(64)))
        mutations.forEach { reject { ReadingProgressSnapshotValidation.validateDocument(document, snapshot.copy(identity = it)) } }
    }
}
