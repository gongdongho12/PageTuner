package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.portable.PortableLibraryStore
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SharingStoredLibraryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun portableInventoryContainsSummariesAndSelectedReadPreservesArchive() {
        val root = temporary.newFolder("portable")
        val store = PortableLibraryStore(root)
        val original = ExchangeDocument("server:original", "Book", "Chapter", "en", "original", listOf(ExchangeParagraph("p1", "Original")))
        val translated = original.copy(id = "server:translation", language = "ko", kind = "translation", paragraphs = listOf(ExchangeParagraph("p1", "번역")))
        store.importArchive(LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-02T00:00:00.000Z", listOf(original, translated))))
        val archive = root.listFiles()!!.single()
        val before = archive.readBytes()
        val entries = store.listForSharing()
        assertEquals(2, entries.size)
        assertEquals(listOf(false, true), entries.map { it.translated })
        assertEquals("번역", store.readForSharing(entries.last()).documents.last().paragraphs.single().text)
        assertArrayEquals(before, archive.readBytes())
    }

    @Test fun offlineInventoryDoesNotRetainBodyAndSelectedReadHasOriginalWithoutMutation() {
        val root = temporary.newFolder("offline")
        val store = OfflineNovelStorageStore.forDirectory(root)
        store.saveOfflineChapter("novel", 3, "Chapter", "Offline body")
        val source = root.walk().first { it.isFile }
        val before = source.readBytes()
        val entry = store.listForSharing().single()
        assertEquals("Chapter", entry.title)
        assertFalse(entry.toString().contains("Offline body"))
        assertEquals("Offline body", store.readForSharing(entry.path)?.originalText)
        assertArrayEquals(before, source.readBytes())
        try { store.readForSharing("../escape.json"); fail("Must reject path traversal") } catch (_: IllegalArgumentException) { }
    }

    @Test fun brokenAndOversizedOfflineFilesDoNotHideTheReadableBook() {
        val root = temporary.newFolder("offline-mixed")
        val store = OfflineNovelStorageStore.forDirectory(root)
        store.saveOfflineChapter("novel", 1, "Readable", "Still here")
        root.resolve("broken.json").writeText("invalid json")
        java.io.RandomAccessFile(root.resolve("large.json"), "rw").use { it.setLength(16L * 1024 * 1024 + 1) }
        val entries = store.listForSharing()
        assertEquals(3, entries.size)
        assertEquals(setOf("document_too_large", "document_unavailable"), entries.mapNotNull { it.errorCode }.toSet())
        assertEquals("Still here", store.readForSharing(entries.single { it.title == "Readable" }.path)?.originalText)
    }

    @Test fun brokenAndOversizedArchivesDoNotHideTheReadableDocument() {
        val root = temporary.newFolder("portable-mixed")
        val store = PortableLibraryStore(root)
        val book = ExchangeDocument("book", "Readable", "Chapter", "en", "original", listOf(ExchangeParagraph("p", "Still here")))
        store.importArchive(LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-02T00:00:00.000Z", listOf(book))))
        root.resolve("${"a".repeat(64)}.zip").writeText("broken archive")
        java.io.RandomAccessFile(root.resolve("${"b".repeat(64)}.zip"), "rw").use { it.setLength(LibraryExchangeLimits.ARCHIVE_BYTES.toLong() + 1) }
        val entries = store.listForSharing()
        assertEquals(3, entries.size)
        assertEquals(setOf("document_too_large", "document_unavailable"), entries.mapNotNull { it.errorCode }.toSet())
        assertEquals("Still here", store.readForSharing(entries.single { it.title == "Readable" }).documents.single().paragraphs.single().text)
    }
}
