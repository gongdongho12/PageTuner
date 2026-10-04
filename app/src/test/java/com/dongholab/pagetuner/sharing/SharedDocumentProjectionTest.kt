package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.backup.exchange.*
import org.junit.Assert.*
import org.junit.Test

class SharedDocumentProjectionTest {
    private val source = ExchangeDocument("original-book-id", "Book", "Chapter", "ko", "original",
        listOf(ExchangeParagraph("source-paragraph", "Original text")),
        position = ExchangeAnchor("source-paragraph", 3),
        extensionsJson = """{"server":{"accountId":"private-account"},"path":"/private/book"}""")

    @Test fun preservesParagraphIdentityAndAnchorWithoutExposingPrivateExtensions() {
        val result = sharedProjection("opaque", source, emptyList(), "txt").document
        assertEquals("source-paragraph", result.paragraphs.single().paragraphId)
        assertEquals(3, result.anchor?.characterOffset)
        assertFalse(result.toString().contains("private-account"))
        assertFalse(result.toString().contains("/private/book"))
    }

    @Test fun repeatedImageReferencesHaveDistinctIdsAndIdenticalPinnedBytes() {
        val asset = ExchangeAsset(byteArrayOf(1, 2, 3), "image/png")
        val book = source.copy(assets = listOf(ExchangeAssetReference(asset.path, "image", "source-paragraph"),
            ExchangeAssetReference(asset.path, "image", null)))
        val result = sharedProjection("opaque", book, listOf(asset), "epub")
        assertEquals(2, result.document.assets.map { it.id }.distinct().size)
        result.binaries.values.forEach { assertArrayEquals(asset.bytes, it) }
        assertEquals(source.paragraphs.sumOf { it.text.length.toLong() * 2 } + asset.bytes.size, result.retainedBytes)
        assertNull(result.document.assets.last().paragraphId)
    }

    @Test fun contentAndAssetReplacementChangeRevisionButReaderProgressDoesNot() {
        val initial = sharedProjection("opaque", source, emptyList(), "txt").document.revision
        assertEquals(initial, sharedProjection("opaque", source.copy(position = ExchangeAnchor("source-paragraph", 8)), emptyList(), "txt").document.revision)
        assertNotEquals(initial, sharedProjection("opaque", source.copy(paragraphs = listOf(ExchangeParagraph("source-paragraph", "Changed"))), emptyList(), "txt").document.revision)
        val a = ExchangeAsset(byteArrayOf(1), "image/png")
        val b = ExchangeAsset(byteArrayOf(2), "image/png")
        fun projection(asset: ExchangeAsset) = sharedProjection("opaque", source.copy(assets = listOf(ExchangeAssetReference(asset.path, "image"))), listOf(asset), "epub")
        assertNotEquals(projection(a).document.revision, projection(b).document.revision)
    }

    @Test fun physicalPdfPagesExistEvenWithoutExtractedTextAndRestoreStoredPage() {
        val asset = ExchangeAsset(byteArrayOf(1, 2), "application/pdf")
        val pdf = sharedPdfDocument("opaque", "Scanned PDF", "und", false, asset, 5, 3)
        val result = sharedProjection("opaque", pdf, listOf(asset), "pdf").document
        assertEquals(5, result.paragraphs.size)
        assertTrue(result.paragraphs.all { it.text.isEmpty() })
        assertEquals(result.paragraphs[3].paragraphId, result.anchor?.paragraphId)
        assertEquals(5, result.paragraphs.map { it.paragraphId }.distinct().size)
        try {
            sharedPdfDocument("opaque", "Too many pages", "und", false, asset, 2001, 0)
            fail("Must match the shared browser PDF page limit")
        } catch (error: SharingUnavailableException) { assertEquals("document_too_large", error.code) }
    }

    @Test fun badAnchorIsOmittedInsteadOfInventingAParagraphMapping() {
        val result = sharedProjection("opaque", source.copy(position = ExchangeAnchor("unknown", 1)), emptyList(), "txt")
        assertNull(result.document.anchor)
    }

    @Test fun portablePdfUsesCurrentAndroidPageAndPreservesVerifiedWebParagraphIds() {
        val asset = ExchangeAsset(byteArrayOf(1, 2), "application/pdf")
        val webId = "local-sha256:${asset.sha256}"
        val metadata = """{"android":{"pageIndex":0},"pageturnerAndroidReader1":{"format":"pageturner.android-reader","version":1,"mode":"pdf","pageIndex":2,"pageBookmarks":[],"pageAnnotations":[]}}"""
        val pdf = source.copy(id = webId, paragraphs = List(4) { ExchangeParagraph("$webId:p$it", "Text $it") },
            position = ExchangeAnchor("$webId:p1", 2), extensionsJson = metadata)
        val current = sharedPortablePdfDocument("opaque", pdf, asset, 4)
        assertEquals(pdf.paragraphs, current.paragraphs)
        assertEquals("$webId:p2", current.position?.paragraphId)
        val unchanged = sharedPortablePdfDocument("opaque", pdf.copy(extensionsJson = null), asset, 4)
        assertEquals(pdf.position, unchanged.position)
        val native = sharedPortablePdfDocument("opaque", pdf.copy(id = "unverified"), asset, 4)
        assertEquals(native.paragraphs[2].paragraphId, native.position?.paragraphId)
        assertNotEquals(pdf.paragraphs[2].paragraphId, native.position?.paragraphId)
    }

    @Test fun textOverLimitHasActionableSizeFailure() {
        try {
            sharedProjection("opaque", source.copy(paragraphs = listOf(ExchangeParagraph("p", "a".repeat(4_000_001)))), emptyList(), "txt")
            fail("Must reject oversized text")
        } catch (error: SharingUnavailableException) { assertEquals("document_too_large", error.code) }
    }

    @Test fun activeSvgIsNotPublishedAsAnImage() {
        val asset = ExchangeAsset("<svg/>".toByteArray(), "image/svg+xml")
        try {
            sharedProjection("opaque", source.copy(assets = listOf(ExchangeAssetReference(asset.path, "image"))), listOf(asset), "epub")
            fail("Must reject active assets")
        } catch (error: SharingUnavailableException) { assertEquals("unsupported_format", error.code) }
    }
}
