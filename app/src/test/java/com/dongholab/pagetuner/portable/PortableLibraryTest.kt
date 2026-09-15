package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.document.*
import com.dongholab.pagetuner.library.*
import com.dongholab.pagetuner.reader.ReaderBookmark
import com.dongholab.pagetuner.translation.glossary.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PortableLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun commonWebFixtureImportsOpensAndReexportsWithoutLosingAnchorsOrAssets() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("library-exchange-v1/portable-v1.zip")).use { it.readBytes() }
        val original = LibraryExchangeCodec.read(fixture)
        val store = PortableLibraryStore(temporary.newFolder())
        val imported = store.importArchive(fixture)
        assertFalse(imported.duplicate)
        assertEquals(3, imported.entries.size)
        val entry = imported.entries.first()
        val mapping = PortableDocumentMapper.reader(entry.document, original.assets, entry.readerId)
        assertEquals("Hello 🌏.\nSecond line.", mapping.document.pages.first().plainText)
        assertEquals(0, mapping.pageFor(requireNotNull(entry.document.position)))
        store.update(entry) { PortableDocumentMapper.mergeReader(it, mapping, 0,
            PortableDocumentMapper.bookmarks(it, mapping), PortableDocumentMapper.annotations(it, mapping)) }
        val exported = LibraryExchangeCodec.read(store.export(entry))
        assertEquals(entry.document, exported.documents.single())
        assertEquals(8, exported.documents.single().position?.characterOffset)
        assertEquals(original.assets.first { it.mimeType.startsWith("image/") }.sha256, exported.assets.single().sha256)
        val reopened = PortableLibraryStore(temporary.root.listFiles()!!.first()).list()
        assertEquals(3, reopened.size)
    }

    @Test fun importIsAtomicDuplicatePreservesEditsAndConflictingRevisionBecomesSeparateCopy() {
        val directory = temporary.newFolder()
        val store = PortableLibraryStore(directory)
        val initial = simplePackage()
        val bytes = LibraryExchangeCodec.write(initial)
        val entry = store.importArchive(bytes).entries.single()
        store.update(entry) { it.copy(organization = ExchangeOrganization("Edited folder", listOf("kept"), true)) }
        val duplicate = store.importArchive(LibraryExchangeCodec.write(initial.copy(createdAt = "2026-09-16T00:00:00.000Z")))
        assertTrue(duplicate.duplicate)
        assertEquals("Edited folder", duplicate.entries.single().document.organization.folder)
        assertThrows(IllegalArgumentException::class.java) { store.importArchive(bytes.copyOf(bytes.size / 2)) }
        assertEquals(1, directory.listFiles()!!.size)
        val conflict = initial.copy(documents = listOf(initial.documents.single().copy(bookTitle = "A changed title")))
        assertFalse(store.importArchive(LibraryExchangeCodec.write(conflict)).duplicate)
        assertEquals(2, store.list().size)
        assertEquals("Edited folder", store.read(entry).documents.single().organization.folder)
    }

    @Test fun paginatedUnicodeAnchorAndExactRangeSurviveReadingAndNewBookmarks() {
        val body = "x".repeat(1099) + "🌏" + "tail"
        val range = ExchangeRange(ExchangeAnchor("stable-paragraph", 1099), ExchangeAnchor("stable-paragraph", 1101))
        val note = ExchangeNote("range", "highlight", "A precise selection", "", "🌏", range.start, "2026-09-15T00:00:00Z", range)
        val original = simplePackage().documents.single().copy(paragraphs = listOf(ExchangeParagraph("stable-paragraph", body)),
            position = ExchangeAnchor("stable-paragraph", 1102), notes = listOf(note), extensionsJson = "{\"futureNative\":{\"value\":1}}")
        val mapping = PortableDocumentMapper.reader(original, emptyList(), "reader")
        assertEquals(2, mapping.document.pageCount)
        assertFalse(mapping.document.pages.first().plainText.last().isHighSurrogate())
        assertEquals(body, mapping.document.pages.joinToString("") { it.plainText })
        val withBookmark = PortableDocumentMapper.mergeReader(original, mapping, 1,
            listOf(ReaderBookmark("new", 1, "Here", 1L)), PortableDocumentMapper.annotations(original, mapping))
        assertEquals(original.position, withBookmark.position)
        assertEquals(note, withBookmark.notes.first())
        assertEquals(1099, withBookmark.notes.last().anchor.characterOffset)
        assertEquals(original.extensionsJson, withBookmark.extensionsJson)
        LibraryExchangeCodec.write(simplePackage().copy(documents = listOf(withBookmark)))
    }

    @Test fun nativeExportIncludesStableSegmentsOrganizationGlossaryAndImageBytes() {
        val common = requireNotNull(javaClass.classLoader?.getResourceAsStream("library-exchange-v1/portable-v1.zip")).use { LibraryExchangeCodec.read(it.readBytes()) }
        val image = common.assets.first { it.mimeType == "image/png" }.bytes
        val document = ReaderDocument("native-doc", "Book", DocumentFormat.EPUB,
            listOf(ReaderPage(0, listOf(TextSegment("original-id", 0, 0, "Native source 🌏")), "Chapter", 1,
                listOf(ReaderPageImage("image1", "Illustration", "image/png", image)))), listOf(DocumentOutlineItem("Chapter", 0)))
        val book = nativeBook().copy(bookmarks = listOf(LocalBookBookmark("mark", 0, "Read here", 1L)),
            annotations = listOf(LocalBookAnnotation("note", LocalBookAnnotationType.Note, 0, "Native note", 2L)))
        val glossary = BookGlossary(book.id, listOf(BookGlossaryEntry("term", "Mira", "미라", "주인공", GlossaryTermKind.Character, true, false)))
        val native = PortableDocumentMapper.native(book, document, glossary = glossary)
        val nativeBytes = LibraryExchangeCodec.write(native)
        val exported = LibraryExchangeCodec.read(nativeBytes)
        // Optional bridge artifact for a host-driven Android→web interoperability run.
        System.getenv("PAGETUNER_PORTABLE_ANDROID_EXPORT")?.takeIf(String::isNotBlank)?.let { destination -> File(destination).writeBytes(nativeBytes) }
        assertEquals("original-id", exported.documents.single().paragraphs.single().paragraphId)
        assertEquals(book.folder, exported.documents.single().organization.folder)
        assertEquals("주인공", exported.documents.single().glossary.single().displayTerm)
        assertFalse(exported.documents.single().glossary.single().enabled)
        assertEquals(2, exported.documents.single().notes.size)
        assertArrayEquals(image, exported.assets.single().bytes)
        assertFalse(exported.documents.single().extensionsJson!!.contains("account", ignoreCase = true))
    }

    @Test fun missingPdfOrEpubImagesFailBeforeProducingAnIncompleteArchive() {
        val pdf = ReaderDocument("pdf", "PDF", DocumentFormat.PDF, listOf(ReaderPage(0, emptyList())))
        assertThrows(IllegalArgumentException::class.java) { PortableDocumentMapper.native(nativeBook(), pdf) }
        val epub = ReaderDocument("epub", "EPUB", DocumentFormat.EPUB, listOf(ReaderPage(0, listOf(TextSegment("s", 0, 0, "text")), imageCount = 1)))
        assertThrows(IllegalArgumentException::class.java) { PortableDocumentMapper.native(nativeBook(), epub) }
        val archive = PortableDocumentMapper.native(nativeBook(), pdf, "%PDF-1.4\noriginal".toByteArray())
        val decoded = LibraryExchangeCodec.read(LibraryExchangeCodec.write(archive))
        assertTrue(decoded.documents.single().paragraphs.isEmpty())
        assertEquals("pdf", decoded.documents.single().assets.single().role)
    }

    @Test fun boundedInputRejectsOversizeUnknownLengthStream() {
        assertThrows(IllegalArgumentException::class.java) { readPortableBytes(ByteArray(17).inputStream(), 16) }
        assertArrayEquals(ByteArray(16), readPortableBytes(ByteArray(16).inputStream(), 16))
    }

    private fun simplePackage() = LibraryExchangePackage("2026-09-15T00:00:00.000Z", listOf(
        ExchangeDocument("sample", "Book", "Chapter", "en", "original", listOf(ExchangeParagraph("p", "Text")))))
    private fun nativeBook() = LocalBook("book-id", "Book", DocumentFormat.EPUB, "books/book.epub", "hash", 1, 0, 0, 0, 1,
        folder = "Fiction", tags = listOf("fantasy"), remoteAccountId = "private-account-never-export")
}
