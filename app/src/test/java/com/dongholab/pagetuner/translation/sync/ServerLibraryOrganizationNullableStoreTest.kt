package com.dongholab.pagetuner.translation.sync

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests the production nullable transaction/locking with injected IO, not Android AtomicFile. */
class ServerLibraryOrganizationNullableStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "00000000-0000-4000-8000-000000000001")
    private val empty = DeviceLibraryOrganization()
    private val value = DeviceLibraryOrganization(pending = LibraryOrganizationMutation(0,
        "00000000-0000-4000-8000-000000000002", LibraryOrganization("읽을  책 e\u0301", listOf("é", "e\u0301", "Tag", "tag", "中  文"), true)),
        retryAfterUntil = 12_345)

    @Test fun missingAndPresentEmptyAreDifferentWithoutChangingLegacyRead() {
        val io = MemoryIo(); val store = store(io)
        assertNull(store.readNullable(target)); assertEquals(empty, store.read(target))
        assertTrue(store.compareAndWrite(target, null, empty) {})
        assertEquals(empty, store.readNullable(target)); assertEquals(empty, store.read(target))
        assertFalse(store.compareAndWrite(target, null, value) {})
        assertEquals(empty, store.readNullable(target)); assertEquals(1, io.writes)
    }

    @Test fun comparesWholeJournalIncludingPendingQueuedConflictAndRetryDeadline() {
        val io = MemoryIo(); val store = store(io)
        val remote = ServerLibraryOrganization(target.kind, target.recordId, 1, LibraryOrganization("Remote"), "2026-10-24T00:00:00Z")
        val original = value.copy(remote = remote, queued = LibraryOrganization("Queued"), conflict = remote)
        store.write(target, original)
        for (stale in listOf(null, empty, original.copy(retryAfterUntil = 0), original.copy(queued = null), original.copy(conflict = null))) {
            assertFalse(store.compareAndWrite(target, stale, empty) {})
            assertEquals(original, store.readNullable(target))
        }
        assertTrue(store.compareAndWrite(target, original, original.copy(queued = LibraryOrganization("New queued"))) {})
        assertEquals(original.retryAfterUntil, store.readNullable(target)!!.retryAfterUntil)
    }

    @Test fun twoNullWritersAcrossCanonicalAliasesHaveOneWinner() {
        val directory = temporary.newFolder(); val io = MemoryIo()
        val first = FileServerLibraryOrganizationStore(directory, io)
        val second = FileServerLibraryOrganizationStore(File(directory, "."), io)
        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(first, second).mapIndexed { index, store -> executor.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                store.compareAndWrite(target, null, value.copy(retryAfterUntil = index.toLong())) {}
            } }
            start.countDown()
            assertEquals(1, results.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(1, io.writes)
            assertNotNull(first.readNullable(target)?.pending)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun stagedGuardFailureLeavesMissingJournalAbsentAndExistingJournalUnchanged() {
        for (existing in listOf(false, true)) {
            val io = MemoryIo(); val store = store(io)
            if (existing) store.write(target, value)
            var current = true
            io.staged = { current = false }
            assertThrows(IllegalStateException::class.java) {
                store.compareAndWrite(target, if (existing) value else null, empty) { check(current) }
            }
            assertEquals(if (existing) value else null, store.readNullable(target))
            assertEquals(if (existing) 1 else 0, io.writes)
        }
    }

    @Test fun noPostCommitGuardCanTurnAcceptedIntentIntoRollback() {
        val io = MemoryIo(); val store = store(io); var current = true
        io.committed = { current = false }
        assertTrue(store.compareAndWrite(target, null, value) { check(current) })
        assertFalse(current); assertEquals(value, store.readNullable(target)); assertEquals(1, io.writes)
    }

    @Test fun capturesExpectedAndReplacementListsBeforeExternalGuard() {
        val io = MemoryIo(); val store = store(io)
        val oldTags = mutableListOf("Old", "old")
        val newTags = mutableListOf("é", "e\u0301", "中  文")
        val previous = value.copy(pending = value.pending!!.copy(organization = LibraryOrganization("Before", oldTags)))
        val replacement = value.copy(pending = value.pending!!.copy(organization = LibraryOrganization("After", newTags)))
        store.write(target, previous)
        assertTrue(store.compareAndWrite(target, previous, replacement) {
            oldTags.clear(); newTags.clear()
        })
        assertEquals(listOf("é", "e\u0301", "中  文"), store.readNullable(target)!!.pending!!.organization.tags)
    }

    @Test fun readAndWriteFromDuplicateStoreWaitForTransactionCommit() {
        val directory = temporary.newFolder(); val io = MemoryIo()
        val first = FileServerLibraryOrganizationStore(directory, io)
        val second = FileServerLibraryOrganizationStore(File(directory, "."), io)
        val staged = CountDownLatch(1); val release = CountDownLatch(1); val readerEntered = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            io.staged = { staged.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val write = executor.submit<Boolean> { first.compareAndWrite(target, null, value) {} }
            assertTrue(staged.await(5, TimeUnit.SECONDS))
            val read = executor.submit<DeviceLibraryOrganization?> { readerEntered.countDown(); second.readNullable(target) }
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS)); assertFalse(read.isDone)
            release.countDown()
            assertTrue(write.get(5, TimeUnit.SECONDS)); assertEquals(value, read.get(5, TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun invalidRecordAndOversizedStoredInputNeverGetOverwrittenByNullCas() {
        val io = MemoryIo(); val store = store(io)
        store.write(target, value)
        val path = io.rows.keys.single()
        for (corrupt in listOf("{}".toByteArray(), ByteArray(65_537) { ' '.code.toByte() })) {
            io.rows[path] = corrupt
            assertThrows(Exception::class.java) { store.compareAndWrite(target, null, empty) {} }
            assertArrayEquals(corrupt, io.rows[path]); assertEquals(1, io.writes)
        }
        assertThrows(IllegalArgumentException::class.java) { store.readNullable(target.copy(accountKey = "wrong")) }
        assertThrows(IllegalArgumentException::class.java) {
            store.compareAndWrite(target, null, value.copy(pending = value.pending!!.copy(organization = LibraryOrganization("x".repeat(201))))) {}
        }
    }

    @Test fun existingStoresWithoutAtomicSupportFailClosed() {
        val unsupported = object : ServerLibraryOrganizationStore {
            override fun read(target: ServerReadingTarget) = empty
            override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) = fail("Unsupported CAS called write")
            override fun targets(accountKey: String) = emptyList<ServerReadingTarget>()
        }
        assertThrows(UnsupportedOperationException::class.java) { unsupported.readNullable(target) }
        assertThrows(UnsupportedOperationException::class.java) { unsupported.compareAndWrite(target, null, value) {} }
    }

    private fun store(io: MemoryIo) = FileServerLibraryOrganizationStore(temporary.newFolder(), io)
    private class MemoryIo : LibraryOrganizationJournalIo {
        val rows = ConcurrentHashMap<String, ByteArray>()
        var writes = 0
        var staged: () -> Unit = {}
        var committed: () -> Unit = {}
        override fun exists(file: File) = rows.containsKey(file.canonicalPath)
        override fun read(file: File) = rows.getValue(file.canonicalPath).copyOf()
        override fun write(file: File, bytes: ByteArray, guard: () -> Unit) {
            guard(); staged(); guard()
            rows[file.canonicalPath] = bytes.copyOf(); writes++
            committed()
        }
    }
}
