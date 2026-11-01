package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real production store/lock with injected file IO; these tests do not execute Android AtomicFile. */
class ServerReadingProgressStoreLockTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
    private val anchor = ServerReadingAnchor("paragraph-one", 7)
    private val remote = ServerReadingProgress(target.kind, target.recordId, 3, anchor, "2026-11-01T00:00:00Z")
    private val value = DeviceReadingProgress(remote,
        ServerReadingMutation(3, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor),
        ServerReadingAnchor("queued e\u0301  🌏", 0), remote.copy(version = 4), 123_456)

    @Test fun missingReadDoesNotCreateDirectoryOrRecord() {
        val directory = File(temporary.root, "not-created")
        val io = FileIo()
        assertEquals(DeviceReadingProgress(), FileServerReadingProgressStore(directory, io).read(target))
        assertFalse(directory.exists())
        assertEquals(0, io.reads)
        assertEquals(0, io.writes)
    }

    @Test fun fullJournalAndLegacyMissingRetryFieldRoundTripUnchangedAcrossAliases() {
        val directory = temporary.newFolder(); val io = FileIo()
        val first = FileServerReadingProgressStore(directory, io)
        val other = FileServerReadingProgressStore(File(directory, "."), io)
        first.write(target, value)
        assertEquals(value, other.read(target))
        assertEquals(listOf(target), other.pending(target.accountKey))
        val base = journal(directory)
        assertEquals(1, JSONObject(base.readText()).get("format"))
        val legacy = JSONObject(base.readText()).also { it.remove("retryAfterUntil") }
        base.writeText(legacy.toString())
        assertEquals(value.copy(retryAfterUntil = null), other.read(target))
        assertEquals(1, io.writes)
    }

    @Test fun writerExcludesDuplicateReadPendingAndWriteUntilCommit() {
        repeat(3) { operation ->
            val directory = temporary.newFolder(); val io = FileIo()
            val first = FileServerReadingProgressStore(directory, io)
            val other = FileServerReadingProgressStore(File(directory, "."), io)
            first.write(target, DeviceReadingProgress(remote = remote))
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val contenderThread = AtomicReference<Thread>()
            val executor = Executors.newFixedThreadPool(2)
            io.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            try {
                val writer = executor.submit { first.write(target, value) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val contender = executor.submit {
                    contenderThread.set(Thread.currentThread())
                    when (operation) {
                        0 -> assertEquals(value, other.read(target))
                        1 -> assertEquals(listOf(target), other.pending(target.accountKey))
                        else -> other.write(target, value.copy(retryAfterUntil = 999_999))
                    }
                }
                awaitBlocked(contenderThread)
                assertFalse(contender.isDone)
                // No read/recovery can enter IO while the writer has the shared directory monitor.
                assertEquals(0, io.reads)
                release.countDown(); writer.get(5, TimeUnit.SECONDS); contender.get(5, TimeUnit.SECONDS)
                io.beforeWrite = {}
                assertEquals(if (operation == 2) value.copy(retryAfterUntil = 999_999) else value, first.read(target))
            } finally {
                release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun existenceAndBackupReadExcludeWritesAcrossCanonicalAliases() {
        for (holdExistence in listOf(true, false)) {
            val directory = temporary.newFolder(); val io = FileIo()
            val first = FileServerReadingProgressStore(directory, io)
            val other = FileServerReadingProgressStore(File(directory, "."), io)
            val backup = File(journal(directory).path + ".bak")
            backup.writeText(encodeDeviceReadingProgress(target, value).toString())
            val before = backup.readBytes()
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            val pause = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); Unit }
            if (holdExistence) io.beforeExists = pause else io.beforeRead = pause
            val executor = Executors.newFixedThreadPool(2); val contenderThread = AtomicReference<Thread>()
            try {
                val reader = executor.submit<DeviceReadingProgress> { first.read(target) }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                val writer = executor.submit { contenderThread.set(Thread.currentThread()); other.write(target, value) }
                awaitBlocked(contenderThread); assertFalse(writer.isDone); assertEquals(0, io.writes)
                release.countDown(); assertEquals(value, reader.get(5, TimeUnit.SECONDS)); writer.get(5, TimeUnit.SECONDS)
                assertArrayEquals(before, backup.readBytes())
            } finally {
                release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun pendingReadsBackupOnceWithoutChangingStoredIntent() {
        val directory = temporary.newFolder(); val io = FileIo()
        val backup = File(journal(directory).path + ".bak")
        val bytes = encodeDeviceReadingProgress(target, value).toString().toByteArray()
        backup.writeBytes(bytes)
        val store = FileServerReadingProgressStore(directory, io)
        assertEquals(listOf(target), store.pending(target.accountKey))
        assertEquals(value, store.read(target))
        assertArrayEquals(bytes, backup.readBytes()); assertFalse(journal(directory).exists()); assertEquals(0, io.writes)
    }

    @Test fun corruptOversizedAndMalformedUtf8RecordsAreRetainedByReadAndPending() {
        val directory = temporary.newFolder(); val io = FileIo()
        val store = FileServerReadingProgressStore(directory, io); val base = journal(directory)
        val json = encodeDeviceReadingProgress(target, value).toString()
        val index = json.indexOf("paragraph-one")
        val invalidUtf8 = json.substring(0, index).toByteArray() + byteArrayOf(0xc3.toByte(), 0x28) +
            json.substring(index + "paragraph-one".length).toByteArray()
        for (bytes in listOf("{}".toByteArray(), ByteArray(65_537) { ' '.code.toByte() }, invalidUtf8)) {
            base.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.read(target) }
            assertTrue(store.pending(target.accountKey).isEmpty())
            assertArrayEquals(bytes, base.readBytes()); assertEquals(0, io.writes)
        }
    }

    @Test fun uppercaseHistoricalKeyIsNotLowercasedMigratedOrDeleted() {
        val directory = temporary.newFolder(); val io = FileIo()
        val upper = target.copy(recordId = target.recordId.uppercase())
        val base = journal(directory, upper)
        val bytes = encodeDeviceReadingProgress(upper, DeviceReadingProgress()).toString().toByteArray()
        base.writeBytes(bytes)
        val store = FileServerReadingProgressStore(directory, io)
        assertThrows(IllegalArgumentException::class.java) { store.read(upper) }
        assertTrue(store.pending(target.accountKey).isEmpty())
        assertEquals(DeviceReadingProgress(), store.read(target))
        assertArrayEquals(bytes, base.readBytes()); assertFalse(journal(directory).exists()); assertEquals(0, io.writes)
    }

    @Test fun failedReadAndWriteReleaseTheDirectoryMonitorAndPreserveExistingBytes() {
        val directory = temporary.newFolder(); val io = FileIo()
        val first = FileServerReadingProgressStore(directory, io)
        val other = FileServerReadingProgressStore(File(directory, "."), io)
        first.write(target, value); val bytes = journal(directory).readBytes()
        io.beforeRead = { throw IOException("injected read failure") }
        assertThrows(IOException::class.java) { first.read(target) }
        io.beforeRead = {}; io.beforeWrite = { throw IOException("injected write failure") }
        assertThrows(IOException::class.java) { first.write(target, DeviceReadingProgress()) }
        assertArrayEquals(bytes, journal(directory).readBytes())
        io.beforeWrite = {}
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(value, executor.submit<DeviceReadingProgress> { other.read(target) }.get(5, TimeUnit.SECONDS))
            executor.submit { other.write(target, value.copy(retryAfterUntil = 999)) }.get(5, TimeUnit.SECONDS)
            assertEquals(999L, requireNotNull(first.read(target).retryAfterUntil))
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun journal(directory: File, value: ServerReadingTarget = target) = File(directory, StableContentHash.sha256(value.key) + ".json")
    private fun awaitBlocked(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals("Contender did not wait on the production directory monitor", Thread.State.BLOCKED, thread.get()?.state)
    }
    private class FileIo : ReadingProgressJournalIo {
        @Volatile var beforeExists: () -> Unit = {}
        @Volatile var beforeRead: () -> Unit = {}
        @Volatile var beforeWrite: () -> Unit = {}
        var reads = 0
        var writes = 0
        override fun exists(file: File): Boolean { beforeExists(); return file.exists() || File(file.path + ".bak").exists() }
        override fun read(file: File): ByteArray { beforeRead(); reads++; return (if (file.exists()) file else File(file.path + ".bak")).readBytes() }
        override fun write(file: File, bytes: ByteArray) { beforeWrite(); file.writeBytes(bytes); writes++ }
    }
}
