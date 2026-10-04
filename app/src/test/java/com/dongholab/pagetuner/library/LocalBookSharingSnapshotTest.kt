package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalBookSharingSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun book(bytes: ByteArray) = LocalBook("id", "Book", DocumentFormat.TEXT, "books/test.txt", DocumentIds.sha256(bytes), 3, 2, 100, 200, bytes.size.toLong())

    @Test fun sharingSnapshotLeavesMetadataAndOriginalBytesUntouched() {
        val root = temporary.newFolder("library")
        root.resolve("books").mkdir()
        val bytes = "Original text".toByteArray()
        root.resolve("books/test.txt").writeBytes(bytes)
        val metadata = root.resolve("books.json").apply { writeText("sentinel metadata") }
        val timestamp = metadata.lastModified()
        val result = readLocalBookSnapshot(root, book(bytes), 100)
        assertArrayEquals(bytes, result.bytes)
        assertEquals(2, result.book.currentPageIndex)
        assertEquals(200, result.book.lastOpenedAtMillis)
        assertEquals("sentinel metadata", metadata.readText())
        assertEquals(timestamp, metadata.lastModified())
        assertArrayEquals(bytes, root.resolve("books/test.txt").readBytes())
    }
    @Test fun siblingPrefixAndParentTraversalAreRejected() {
        val root = temporary.newFolder("library")
        listOf("../library-other/book.txt", "../book.txt", ".").forEach { path ->
            try { safeLocalBookFile(root, path); fail("Allowed $path") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun aChangedFileCannotBePublishedUnderOldBookIdentity() {
        val root = temporary.newFolder("library")
        root.resolve("books").mkdir()
        root.resolve("books/test.txt").writeText("new content")
        try { readLocalBookSnapshot(root, book("old content".toByteArray()), 100); fail("Must refuse identity mismatch") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun oversizedSourceIsRejectedBeforeParsing() {
        val root = temporary.newFolder("library")
        root.resolve("books").mkdir()
        val bytes = ByteArray(100)
        root.resolve("books/test.txt").writeBytes(bytes)
        try { readLocalBookSnapshot(root, book(bytes), 99); fail("Must refuse oversized source") }
        catch (_: LocalBookSnapshotTooLargeException) { }
    }
}
