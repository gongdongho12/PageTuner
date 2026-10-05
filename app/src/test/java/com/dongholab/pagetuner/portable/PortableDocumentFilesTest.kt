package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.DocumentFileExports
import com.dongholab.pagetuner.core.content.DocumentFileFormat
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalBookReadSnapshot
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PortableDocumentFilesTest {
    private fun document() = ExchangeDocument("opaque-original-id", "Book 🌏", "Chapter 2", "EN", "translation",
        listOf(ExchangeParagraph("first", "  Untrimmed **text**  "), ExchangeParagraph("empty", ""), ExchangeParagraph("last", "Next\r\nline 🌏")),
        extensionsJson = """{"passive":{"value":"not body"}}""")

    @Test fun archivedTextUsesCanonicalParagraphsInOrderWithoutIncludingPassiveMetadata() {
        val doc = document()
        val archive = LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(doc))
        for ((format, shared) in listOf(PortableDocumentFileFormat.TXT to DocumentFileFormat.TXT,
            PortableDocumentFileFormat.MARKDOWN to DocumentFileFormat.MARKDOWN)) {
            val expected = DocumentFileExports.text(doc.bookTitle, doc.chapterTitle, doc.paragraphs.map { it.text }, shared)
            val exported = PortableDocumentFiles.fromArchive(archive, 0, format)
            assertEquals(expected.filename, exported.filename)
            assertEquals(expected.mimeType, exported.mimeType)
            assertArrayEquals(expected.text.toByteArray(Charsets.UTF_8), exported.bytes)
            assertFalse(exported.bytes.toString(Charsets.UTF_8).contains("not body"))
        }
        assertEquals(doc, archive.documents.single())
    }

    @Test fun pdfExportCopiesOnlyTheVerifiedOriginalPayloadAndNeverUsesZipTextAsExtractionEvidence() {
        val original = "%PDF-1.4\noriginal payload".toByteArray()
        val asset = ExchangeAsset(original.copyOf(), "application/pdf")
        val doc = document().copy(assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
        val archive = LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(doc), listOf(asset))
        val exported = PortableDocumentFiles.fromArchive(archive, 0, PortableDocumentFileFormat.PDF)
        assertEquals("application/pdf", exported.mimeType)
        assertEquals(DocumentFileExports.safeFilename(doc.bookTitle, doc.chapterTitle, "pdf"), exported.filename)
        assertArrayEquals(original, exported.bytes)
        for (format in listOf(PortableDocumentFileFormat.TXT, PortableDocumentFileFormat.MARKDOWN)) {
            assertThrows(IllegalArgumentException::class.java) { PortableDocumentFiles.fromArchive(archive, 0, format) }
        }
        asset.bytes.fill(0)
        assertArrayEquals(original, exported.bytes)
        assertThrows(Exception::class.java) { PortableDocumentFiles.fromArchive(archive, 0, PortableDocumentFileFormat.PDF) }
    }

    @Test fun nativeTextUsesTheFullSavedDocumentAndRejectsChangedOrMalformedOriginalBytes() {
        val text = "First paragraph\n\nSecond paragraph 🌏"
        val bytes = text.toByteArray(Charsets.UTF_8)
        val book = LocalBook("native", "Book", DocumentFormat.TEXT, "book.txt", exchangeSha256(bytes), 2, 1, 0, 0, bytes.size.toLong(),
            currentChapterTitle = "Stored chapter", contentIsTranslated = true)
        val snapshot = LocalBookReadSnapshot(book, bytes)
        val exported = PortableDocumentFiles.fromNative(snapshot, PortableDocumentFileFormat.TXT)
        assertEquals(DocumentFileExports.text(book.title, "Stored chapter", listOf(text), DocumentFileFormat.TXT).text,
            exported.bytes.toString(Charsets.UTF_8))
        assertThrows(IllegalArgumentException::class.java) { PortableDocumentFiles.fromNative(snapshot.copy(bytes = bytes + 1), PortableDocumentFileFormat.TXT) }
        assertThrows(IllegalArgumentException::class.java) { PortableDocumentFiles.fromNative(snapshot, PortableDocumentFileFormat.PDF) }
        val invalid = byteArrayOf(0xc0.toByte(), 0xaf.toByte())
        assertThrows(Exception::class.java) {
            PortableDocumentFiles.fromNative(LocalBookReadSnapshot(book.copy(contentHash = exchangeSha256(invalid)), invalid), PortableDocumentFileFormat.TXT)
        }
    }

    @Test fun nativeTextKeepsLongUnicodeWhitespaceLineBreaksAndMarkdownSourceWithoutReaderPagination() {
        val body = "  \t" + "a".repeat(396) + "🌏" + "z".repeat(1400) + "  \r\n\r\n\r\n# Header\r\n> quoted **body**\t \r\n"
        val bytes = body.toByteArray(Charsets.UTF_8)
        for (inputFormat in listOf(DocumentFormat.TEXT, DocumentFormat.MARKDOWN)) {
            val book = LocalBook("native", "Title", inputFormat, "book.txt", exchangeSha256(bytes), 99, 98, 0, 0, bytes.size.toLong())
            for ((format, shared) in listOf(PortableDocumentFileFormat.TXT to DocumentFileFormat.TXT, PortableDocumentFileFormat.MARKDOWN to DocumentFileFormat.MARKDOWN)) {
                val value = PortableDocumentFiles.fromNative(LocalBookReadSnapshot(book, bytes), format)
                assertArrayEquals(DocumentFileExports.text("Title", "Title", listOf(body), shared).text.toByteArray(Charsets.UTF_8), value.bytes)
            }
        }
    }

    @Test fun cancelledOrReplacedSelectionCannotPublishPreparedBytesToSaf() {
        val selections = PortableFileExportSelection()
        selections.select("native:one")
        val guard = selections.guard("native:one")
        val tickets = PortableExportTickets()
        val request = tickets.begin("one.txt", mimeType = "text/plain")
        tickets.prepare(request, "fixed text".toByteArray(), null, guard)
        selections.select(null)
        selections.select("native:one")
        assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { null }) { fail("Must not open an expired destination"); null } }
        val next = tickets.begin("one.md", mimeType = "text/markdown")
        tickets.prepare(next, byteArrayOf(7), null, selections.guard("native:one"))
        val output = ByteArrayOutputStream()
        assertEquals("text/markdown", tickets.write(next.id, { null }) { output }.mimeType)
        assertArrayEquals(byteArrayOf(7), output.toByteArray())
    }

    @Test fun selectionChangeWhileSafProviderOpensAndProcessRecreationBothWriteNothing() {
        val selections = PortableFileExportSelection().apply { select("portable:one") }
        val tickets = PortableExportTickets()
        val request = tickets.begin("original.pdf", mimeType = "application/pdf")
        tickets.prepare(request, byteArrayOf(1), null, selections.guard("portable:one"))
        val output = ByteArrayOutputStream()
        assertThrows(PortableExportExpired::class.java) {
            tickets.write(request.id, { null }) { selections.select("portable:two"); output }
        }
        assertEquals(0, output.size())
        assertThrows(PortableExportExpired::class.java) {
            PortableExportTickets().write(request.id, { null }) { fail("No bytes survive process recreation"); null }
        }
    }
}
