package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.EpubDocumentExports
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.EpubDocumentReader
import com.dongholab.pagetuner.library.LocalBook
import com.dongholab.pagetuner.library.LocalBookReadSnapshot
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PortableEpubDocumentTest {
    @Test fun zipUsesStoredMimetypeFirstAndEverySharedResourceInOrder() {
        val paragraphs = listOf("  한글 & <script> 🌏\r\n본문\t ", "", "끝" + "가".repeat(3000))
        val expected = EpubDocumentExports.create("책 / 제목", "회차", "ko", paragraphs)
        val file = PortableEpubDocument.create("책 / 제목", "회차", "ko", paragraphs)
        assertEquals(expected.filename, file.filename)
        assertEquals("application/epub+zip", file.mimeType)
        assertTrue(file.filename.endsWith(".epub"))
        val resources = linkedMapOf<String, ByteArray>()
        ZipInputStream(file.bytes.inputStream()).use { zip ->
            expected.entries.forEachIndexed { index, source ->
                val entry = requireNotNull(zip.nextEntry)
                val bytes = zip.readBytes()
                assertEquals(source.path, entry.name)
                assertEquals(if (index == 0) ZipEntry.STORED else ZipEntry.DEFLATED, entry.method)
                assertEquals(CRC32().apply { update(bytes) }.value, entry.crc)
                assertArrayEquals(source.text.toByteArray(Charsets.UTF_8), bytes)
                resources[entry.name] = bytes
                if (index == 0) assertEquals(0, entry.extra?.size ?: 0)
            }
            assertNull(zip.nextEntry)
        }
        val parser = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
        val content = parser.parse(resources.getValue("EPUB/content.xhtml").inputStream())
        val actual = content.getElementsByTagNameNS("http://www.w3.org/1999/xhtml", "p")
        assertEquals(paragraphs, (0 until actual.length).map { actual.item(it).textContent })
        val spine = EpubDocumentReader.extractTextForExport(file.bytes).single()
        assertTrue(spine.contains("한글 & <script> 🌏"))
        assertTrue(spine.endsWith(paragraphs.last()))
        val reader = EpubDocumentReader.parse(file.filename, file.bytes, "Untitled")
        assertEquals(DocumentFormat.EPUB, reader.format)
        assertTrue(reader.pageCount > 1)
        assertTrue(reader.pages.joinToString("\n") { it.plainText }.contains("한글 & <script> 🌏"))
        assertTrue(reader.pages.all { it.images.isEmpty() })
        assertArrayEquals(file.bytes, PortableEpubDocument.create("책 / 제목", "회차", "ko", paragraphs).bytes)
    }

    @Test fun archiveAndNativeSnapshotsRetainWholeTextWithoutExportingPrivateFields() {
        val paragraphs = listOf("First 문단", "LAST_MARKER" + "나".repeat(4000))
        val source = ExchangeDocument("private-source-id", "Book", "Chapter", "ko", "translation",
            paragraphs.mapIndexed { index, text -> ExchangeParagraph("private-$index", text) },
            extensionsJson = """{"passive":{"secret":"NO_EXPORT"}}""")
        val archive = LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(source))
        val fromZip = PortableDocumentFiles.fromArchive(archive, 0, PortableDocumentFileFormat.EPUB)
        assertArrayEquals(PortableEpubDocument.create("Book", "Chapter", "ko", paragraphs).bytes, fromZip.bytes)
        assertEquals(source, archive.documents.single())
        val body = paragraphs.joinToString("\r\n\r\n")
        val bytes = body.toByteArray()
        val book = LocalBook("native", "Book", DocumentFormat.TEXT, "book.txt", exchangeSha256(bytes), 90, 89, 0, 0,
            bytes.size.toLong(), currentChapterTitle = "Chapter", contentLanguage = "ko", contentIsTranslated = true)
        val fromNative = PortableDocumentFiles.fromNative(LocalBookReadSnapshot(book, bytes), PortableDocumentFileFormat.EPUB)
        assertArrayEquals(PortableEpubDocument.create("Book", "Chapter", "ko", listOf(body)).bytes, fromNative.bytes)
        assertTrue(EpubDocumentReader.extractTextForExport(fromNative.bytes).single().contains("LAST_MARKER"))
    }

    @Test fun generationRejectsByteOverflowInvalidInputAndCancellationWithoutReturningPartialFile() {
        assertThrows(IllegalArgumentException::class.java) {
            PortableEpubDocument.create("B", "C", "ko", listOf("text"), maximumBytes = 20)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableEpubDocument.create("B", "C", "ko", listOf("broken\uD800"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            PortableEpubDocument.create("B", "C", "ko", listOf("x".repeat(5_000_000)))
        }
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PortableEpubDocument.create("B", "C", "ko", listOf("text"), checkActive = {
                if (++checks >= 4) throw CancellationException("cancelled")
            })
        }
        assertTrue(checks >= 4)
    }

    @Test fun epubUsesItsOwnSafMimeAndTicketBytesAndRejectsExpiredSelection() {
        val selection = PortableFileExportSelection().apply { select("native:a") }
        val tickets = PortableExportTickets()
        val file = PortableEpubDocument.create("A", "A", null, listOf("body"))
        val request = tickets.begin(file.filename, mimeType = file.mimeType)
        val expected = file.bytes.copyOf()
        tickets.prepare(request, file.bytes, null, selection.guard("native:a"))
        file.bytes.fill(0)
        val output = java.io.ByteArrayOutputStream()
        assertEquals("application/epub+zip", tickets.write(request.id, { null }) { output }.mimeType)
        assertArrayEquals(expected, output.toByteArray())
        val expired = tickets.begin(file.filename, mimeType = file.mimeType)
        tickets.prepare(expired, expected, null, selection.guard("native:a"))
        selection.select("native:b")
        assertThrows(PortableExportExpired::class.java) {
            tickets.write(expired.id, { null }) { fail("Expired EPUB must not open the SAF destination"); null }
        }
    }
}
