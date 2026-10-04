package com.dongholab.pagetuner.core.backup

import com.dongholab.pagetuner.core.backup.exchange.*
import org.junit.Assert.*
import org.junit.Test

class PdfDecoderInputTest {
    @Test fun callerAndDecoderMutationsCannotChangeCapturedSource() {
        val source = "%PDF-fixture-source".toByteArray()
        val original = source.copyOf()
        val captured = PdfDecoderInput.capture(source)
        val decoderBytes = captured.copyBytes()
        source.fill(0)
        decoderBytes.fill(1)
        assertArrayEquals(original, captured.copyBytes())
        assertEquals(exchangeSha256(original), captured.originalFileSha256)
        assertEquals(original.size.toLong(), captured.byteLength)
        assertEquals(VerifiedPdfContext(exchangeSha256(original), 2), captured.verifiedContext(2))
    }

    @Test fun boundsAreExplicitAndZeroPagesAreNeverCoercedToOne() {
        reject { PdfDecoderInput.capture(byteArrayOf()) }
        reject { PdfDecoderInput.capture(ByteArray(5), 4) }
        reject { PdfDecoderInput.capture(byteArrayOf(1), 0) }
        reject { PdfDecoderInput.capture(byteArrayOf(1), LibraryExchangeLimits.ARCHIVE_BYTES + 1) }
        val captured = PdfDecoderInput.capture(ByteArray(4), 4)
        reject { captured.verifiedContext(0) }
        reject { captured.verifiedContext(-1) }
        assertEquals(1, captured.verifiedContext(1).pageCount)
        assertEquals(Int.MAX_VALUE, captured.verifiedContext(Int.MAX_VALUE).pageCount)
    }

    @Test fun physicalAnchorRequiresTheCapturedSourceAndRawDecoderBounds() {
        // A fake decoder boundary verifies adapter plumbing, not validity of these fixture bytes.
        val bytes = "%PDF-first-source".toByteArray()
        val source = PdfDecoderInput.capture(bytes)
        val asset = ExchangeAsset(bytes, "application/pdf")
        val document = ExchangeDocument(id = "pdf", bookTitle = "PDF", chapterTitle = "", language = "EN", kind = "local",
            paragraphs = emptyList(), assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
        val proof = PortableContentProofs.compute(PortableRepresentation.PDF, document, listOf(asset), source.copyBytes())
        val decoded = source.verifiedContext(2)
        PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(source.originalFileSha256, 1), decoded)
        reject { PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(source.originalFileSha256, 2), decoded) }
        val other = PdfDecoderInput.capture("%PDF-other-source".toByteArray()).verifiedContext(2)
        reject { PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(source.originalFileSha256, 0), other) }
        assertEquals("EN", proof.language)
    }

    private fun reject(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) { } }
}
