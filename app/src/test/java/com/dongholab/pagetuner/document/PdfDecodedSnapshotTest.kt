package com.dongholab.pagetuner.document

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.portable.PortableDocumentMapper
import com.dongholab.pagetuner.reader.ReaderViewModel
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

/** Decoder adapters are stubbed here. Real PdfRenderer execution requires the instrumentation tests. */
class PdfDecodedSnapshotTest {
    private val bytes = "%PDF-1.4\nfixed-original".toByteArray()
    private fun decoded(text: List<String?> = listOf("First 🌏", "Second"), count: Int = text.size) =
        PdfDecodedSnapshot.decode(PdfDecoderInput.capture(bytes), "Title", "content://same-uri", "PDF") { PdfDecodedPages(count, text) }
    private fun book(snapshot: PdfDecodedSnapshot) = LocalBook("local-id", "Book", DocumentFormat.PDF, "books/source.pdf",
        snapshot.context.originalFileSha256, snapshot.context.pageCount, 0, 0, 0, bytes.size.toLong())

    @Test fun decoderReceivesCapturedBytesAndRawCountIsBoundToTheirFullHash() {
        val original = bytes.copyOf(); val input = PdfDecoderInput.capture(original); original.fill(0)
        val snapshot = PdfDecodedSnapshot.decode(input, "Title", "uri", "PDF") { source ->
            assertArrayEquals(bytes, source.copyBytes())
            PdfDecodedPages(2, listOf("one", "two"))
        }
        assertEquals(exchangeSha256(bytes), snapshot.context.originalFileSha256)
        assertEquals(2, snapshot.context.pageCount); assertEquals(2, snapshot.document.pageCount)
        assertEquals(PdfTextExtraction.Complete, snapshot.extraction)
        val reread = snapshot.exportOriginal(snapshot.document, bytes); reread.fill(0)
        assertArrayEquals(bytes, snapshot.exportOriginal(snapshot.document, bytes))
    }

    @Test fun zeroNegativeMismatchedAndOversizedDecoderResultsAreRejectedWithoutFallback() {
        listOf(0, -1, 50_001).forEach { count -> assertThrows(IllegalArgumentException::class.java) { decoded(emptyList(), count) } }
        assertThrows(IllegalArgumentException::class.java) { decoded(listOf("one"), 2) }
        assertThrows(IllegalArgumentException::class.java) { decoded(listOf("x".repeat(LibraryExchangeLimits.MAX_CHARACTERS + 1))) }
    }

    @Test fun unavailableExtractionIsDifferentFromActualBlankPages() {
        val unavailable = decoded(listOf(null, null)); val blank = decoded(listOf("", "")); val partial = decoded(listOf("", null))
        assertEquals(PdfTextExtraction.Unavailable, unavailable.extraction)
        assertEquals(PdfTextExtraction.Complete, blank.extraction)
        assertEquals(PdfTextExtraction.Partial, partial.extraction)
        assertEquals(2, unavailable.context.pageCount); assertTrue(unavailable.document.pages.all { it.segments.isEmpty() })
        val exported = PortableDocumentMapper.native(book(unavailable), unavailable.document, bytes, pdfSnapshot = unavailable)
        assertTrue(exported.documents.single().extensionsJson!!.contains("unavailable"))
    }

    @Test fun translatedExportRejectsMissingPagesEvenWhenEveryAvailableSegmentHasATranslation() {
        val partial = decoded(listOf("All available text is translated", null))
        val unavailable = decoded(listOf(null, null))
        for (snapshot in listOf(partial, unavailable)) {
            // Original PDF export remains lossless, but its incomplete text cannot form a complete translated companion.
            PortableDocumentMapper.native(book(snapshot), snapshot.document, bytes, pdfSnapshot = snapshot)
            assertThrows(IllegalArgumentException::class.java) { snapshot.validateCompleteText(snapshot.document) }
        }
        val completeWithBlankPage = decoded(listOf("Translated text", ""))
        completeWithBlankPage.validateCompleteText(completeWithBlankPage.document)
        assertThrows(IllegalArgumentException::class.java) {
            completeWithBlankPage.validateCompleteText(completeWithBlankPage.document.copy(pages = completeWithBlankPage.document.pages.reversed()))
        }
        completeWithBlankPage.close()
        assertThrows(CancellationException::class.java) { completeWithBlankPage.validateCompleteText(completeWithBlankPage.document) }
    }

    @Test fun changedSourceDisplayTextSegmentIdOrderOrPhysicalPagesCannotBeExportedTogether() {
        val snapshot = decoded(); val doc = snapshot.document
        assertThrows(IllegalArgumentException::class.java) { snapshot.exportOriginal(doc, bytes + 0) }
        val first = doc.pages.first(); val segment = first.segments.single()
        val altered = listOf(doc.copy(pages = doc.pages.reversed()), doc.copy(pages = doc.pages.dropLast(1)),
            doc.copy(pages = listOf(first.copy(segments = listOf(segment.copy(text = "different")))) + doc.pages.drop(1)),
            doc.copy(pages = listOf(first.copy(segments = listOf(segment.copy(id = "other")))) + doc.pages.drop(1)),
            doc.copy(pages = listOf(first.copy(segments = listOf(segment.copy(indexInPage = 2)))) + doc.pages.drop(1)))
        altered.forEach { changed -> assertThrows(IllegalArgumentException::class.java) { snapshot.exportOriginal(changed, bytes) } }
        assertArrayEquals(bytes, snapshot.exportOriginal(doc.copy(id = "portable:new-key", title = "Renamed"), bytes))
    }

    @Test fun nativeExportRequiresDecodedSourceAndMatchingStoredMetadataWithoutInferringPositions() {
        val snapshot = decoded(); val originalBook = book(snapshot)
        for (bad in listOf(originalBook.copy(contentHash = "0".repeat(64)), originalBook.copy(fileSizeBytes = 1),
            originalBook.copy(pageCount = 100), originalBook.copy(currentPageIndex = 99))) {
            assertThrows(IllegalArgumentException::class.java) { PortableDocumentMapper.native(bad, snapshot.document, bytes, pdfSnapshot = snapshot) }
        }
        assertThrows(IllegalArgumentException::class.java) { PortableDocumentMapper.native(originalBook, snapshot.document, bytes) }
        val exported = PortableDocumentMapper.native(originalBook, snapshot.document, bytes, pdfSnapshot = snapshot)
        assertEquals(snapshot.document.pages.flatMap { it.segments }.map { it.id }, exported.documents.single().paragraphs.map { it.paragraphId })
        assertArrayEquals(bytes, exported.assets.single().bytes)
        val proof = PortableContentProofs.compute(PortableRepresentation.PDF, exported.documents.single(), exported.assets, bytes)
        PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(exchangeSha256(bytes), 1), snapshot.context)
        assertThrows(IllegalArgumentException::class.java) { PortableContentProofs.validatePdfAnchor(proof, PortableProofAnchor.Pdf(exchangeSha256(bytes), 2), snapshot.context) }
    }

    @Test fun replacementAndResetCloseOldRenderingSessionEvenWithSameUriAndDocumentId() {
        val one = decoded(); val two = decoded()
        val reader = ReaderViewModel(one.document)
        reader.applyLoadedDocument(LoadedReaderDocument(one.document, "content://same", one), null, 0)
        assertSame(one, reader.uiState.value.pdfSnapshot)
        reader.applyLoadedDocument(LoadedReaderDocument(two.document, "content://same", two), null, 0)
        assertThrows(CancellationException::class.java) { one.requireCurrent() }
        assertNotEquals(one.session, two.session)
        two.requireCurrent(); reader.resetDocument(two.document)
        assertNull(reader.uiState.value.pdfSnapshot)
        assertThrows(CancellationException::class.java) { two.exportOriginal(two.document, bytes) }
    }

    @Test fun invalidDisplayedReplacementDoesNotCloseTheActiveSource() {
        val one = decoded(); val two = decoded(); val reader = ReaderViewModel(one.document)
        reader.applyLoadedDocument(LoadedReaderDocument(one.document, pdfSnapshot = one), null, 0)
        assertThrows(IllegalArgumentException::class.java) {
            reader.applyLoadedDocument(LoadedReaderDocument(two.document.copy(pages = two.document.pages.reversed()), pdfSnapshot = two), null, 0)
        }
        one.requireCurrent(); assertSame(one, reader.uiState.value.pdfSnapshot)
    }

    @Test fun renderingDimensionsStayBoundedAndUtf16SegmentsNeverSplitSurrogatePairs() {
        for ((width, height) in listOf(1 to 1, 10000 to 20000, Int.MAX_VALUE to 1, 1 to Int.MAX_VALUE)) {
            val (w, h) = PdfDocumentReader.renderSize(width, height)
            assertTrue(w in 1..1080); assertTrue(h in 1..1440)
        }
        assertThrows(IllegalArgumentException::class.java) { PdfDocumentReader.renderSize(0, 10) }
        val source = "a".repeat(519) + "🌏tail"
        val snapshot = decoded(listOf(source))
        assertEquals(source, snapshot.document.pages.single().segments.joinToString("") { it.text })
        PortableDocumentMapper.native(book(snapshot), snapshot.document, bytes, pdfSnapshot = snapshot).also(LibraryExchangeCodec::write)
    }
}
