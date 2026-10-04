package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import org.junit.Assert.*
import org.junit.Test

class PdfContentDocumentsTest {
    private val pdf = ExchangeAsset("%PDF-1.4\nopaque".toByteArray(), "application/pdf")
    private val image = ExchangeAsset(byteArrayOf(1, 2, 3), "image/png")
    private val unrelated = ExchangeAsset(byteArrayOf(9, 8, 7), "image/webp")
    private fun document() = ExchangeDocument("copy", "title", "chapter", "KO-kr", "local",
        listOf(ExchangeParagraph(" empty ", ""), ExchangeParagraph("原:😀", " a\n😀 ")),
        assets = listOf(ExchangeAssetReference(image.path, "image", "原:😀", " 原 "), ExchangeAssetReference(pdf.path, "pdf"),
            ExchangeAssetReference(image.path, "image", " empty ", "")), extensionsJson = "{\"unknown\":{\"version\":99}}")
    private fun source() = LibraryExchangePackage("ignored", listOf(document().copy(id = "unselected"), document()), listOf(pdf, unrelated, image))
    private fun reject(block: () -> Unit) { assertThrows(IllegalArgumentException::class.java, block) }

    @Test fun selectionPreservesExactEmptyParagraphsLanguageAndOrderedRepeatedReferences() {
        val source = source()
        val prepared = PdfContentDocuments.fromPackage(source, 1)
        assertEquals(source.documents[1].paragraphs, prepared.content.paragraphs)
        assertEquals(source.documents[1].assets, prepared.content.assets)
        assertEquals("KO-kr", prepared.content.language)
        assertEquals(listOf(image.path, pdf.path), prepared.content.payloads.map { it.path })
        assertEquals(3, prepared.proof.assets.size)
        assertArrayEquals(pdf.bytes, prepared.assets.single { it.mimeType == "application/pdf" }.bytes)
    }

    @Test fun sourcePayloadOrderAndUnrelatedDocumentsNeverChangeDeterministicUploadContent() {
        val source = source()
        val expected = PdfContentDocuments.fromPackage(source, 1)
        val changed = source.copy(documents = listOf(document().copy(language = "fr", paragraphs = emptyList()), document()), assets = source.assets.reversed())
        val actual = PdfContentDocuments.fromPackage(changed, 1)
        assertEquals(expected.content, actual.content)
        assertEquals(expected.requestFingerprint, actual.requestFingerprint)
        val unrelatedMetadata = document().copy(id = "different", bookTitle = "renamed", kind = "translation",
            glossary = listOf(ExchangeGlossaryEntry("x", "y")), organization = ExchangeOrganization("folder", listOf("tag"), true),
            position = ExchangeAnchor("empty", 999), extensionsJson = "uninterpreted")
        assertEquals(expected.content, PdfContentDocuments.fromPackage(source.copy(documents = listOf(unrelatedMetadata)), 0).content)
    }

    @Test fun missingDuplicateCorruptOrCrossDocumentAssetsCannotBeSubstituted() {
        val source = source()
        reject { PdfContentDocuments.fromPackage(source, -1) }
        reject { PdfContentDocuments.fromPackage(source, 2) }
        reject { PdfContentDocuments.fromPackage(source.copy(assets = listOf(pdf, unrelated)), 1) }
        reject { PdfContentDocuments.fromPackage(source.copy(assets = source.assets + image), 1) }
        val wrongPdf = ExchangeAsset("%PDF-different".toByteArray(), "application/pdf")
        reject { PdfContentDocuments.fromPackage(source.copy(assets = listOf(wrongPdf, image)), 1) }
        reject { PdfContentDocuments.fromPackage(source.copy(documents = listOf(document().copy(assets = listOf(document().assets[0])))), 0) }
    }

    @Test fun preparedContentOwnsByteAndListSnapshotsAndNeverMutatesItsSource() {
        val paragraphs = document().paragraphs.toMutableList(); val refs = document().assets.toMutableList(); val assets = source().assets.toMutableList()
        val source = source().copy(documents = listOf(document().copy(paragraphs = paragraphs, assets = refs)), assets = assets)
        val prepared = PdfContentDocuments.fromPackage(source, 0)
        val originalPdf = pdf.bytes.copyOf()
        assertEquals(3, refs.size); assertEquals(3, assets.size)
        paragraphs.clear(); refs.clear(); assets.clear(); pdf.bytes.fill(0); image.bytes.fill(0)
        assertEquals(2, prepared.content.paragraphs.size)
        assertEquals(3, prepared.content.assets.size)
        assertArrayEquals(originalPdf, prepared.assets.single { it.mimeType == "application/pdf" }.bytes)
        assertEquals(prepared.proof, PdfContentValidation.validateContent(prepared.content).proof)
    }

    @Test fun maximumSelectedBytesAreSupportedOnceAndOverflowIsRejectedWithoutTruncation() {
        val bytes = ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES)
        "%PDF-".toByteArray().copyInto(bytes)
        val full = ExchangeAsset(bytes, "application/pdf")
        val d = document().copy(paragraphs = emptyList(), assets = listOf(ExchangeAssetReference(full.path, "pdf")))
        val source = LibraryExchangePackage("ignored", listOf(d), listOf(full, unrelated))
        val prepared = PdfContentDocuments.fromPackage(source, 0)
        assertEquals(PdfContentValidation.MAX_PAYLOAD_BYTES.toLong(), prepared.proof.originalFileByteLength)
        reject { PdfContentDocuments.fromPackage(source.copy(documents = listOf(d.copy(assets = d.assets + ExchangeAssetReference(unrelated.path, "image")))), 0) }
        reject { PdfContentDocuments.fromPackage(source.copy(documents = listOf(d.copy(paragraphs = List(4097) { ExchangeParagraph("p$it", "") }))), 0) }
    }
}
