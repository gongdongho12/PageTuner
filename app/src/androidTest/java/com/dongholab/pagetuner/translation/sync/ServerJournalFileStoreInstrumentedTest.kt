package com.dongholab.pagetuner.translation.sync

import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android AtomicFile coverage; compiling this source does not execute the regression. */
@RunWith(AndroidJUnit4::class)
class ServerJournalFileStoreInstrumentedTest {
    private val account = "a".repeat(64)
    private val recordId = "00000000-0000-4000-8000-000000000001"
    private val mutationId = "00000000-0000-4000-8000-000000000002"
    private val timestamp = "2026-10-22T00:00:00Z"

    @Test fun duplicateOrganizationStoresSerializeReadTargetsAndWriteWithActiveAtomicCommit() {
        repeat(3) { action ->
            withDirectory { directory ->
                val target = ServerReadingTarget(account, "ORIGINAL", recordId)
                val writer = FileServerLibraryOrganizationStore(directory)
                val other = FileServerLibraryOrganizationStore(File(directory, "."))
                val initial = DeviceLibraryOrganization(remote = ServerLibraryOrganization(
                    target.kind, target.recordId, 1, LibraryOrganization("Before"), timestamp))
                val committed = initial.copy(pending = LibraryOrganizationMutation(1, mutationId, LibraryOrganization("Pending")))
                val subsequent = committed.copy(queued = LibraryOrganization("Queued"))
                writer.write(target, initial)
                val file = File(directory, StableContentHash.sha256(target.key) + ".json")
                atomicCommitWhileBlocked(directory, file, encodeLibraryOrganizationJournal(target, committed).toString()) {
                    when (action) {
                        0 -> assertEquals(committed, other.read(target))
                        1 -> assertEquals(listOf(target), other.targets(account))
                        else -> other.write(target, subsequent)
                    }
                }
                assertEquals(if (action == 2) subsequent else committed, writer.read(target))
            }
        }
    }

    @Test fun duplicateGlossaryStoresSerializeReadTargetsAndWriteWithActiveAtomicCommit() {
        repeat(3) { action ->
            withDirectory { directory ->
                val target = BookGlossaryTarget(account, BookGlossarySyncIdentity("provider", "book", "ko"))
                val writer = FileServerBookGlossaryStore(directory)
                val other = FileServerBookGlossaryStore(File(directory, "."))
                val initial = DeviceBookGlossary(remote = ServerBookGlossary(target.identity, 1,
                    BookGlossaryValue(emptyList()), timestamp))
                val committed = initial.copy(pending = BookGlossaryMutation(1, mutationId, BookGlossaryValue(null)))
                val subsequent = committed.copy(queued = BookGlossaryValue(emptyList()))
                writer.write(target, initial)
                val file = File(File(directory, account), StableContentHash.sha256(target.key) + ".json")
                atomicCommitWhileBlocked(directory, file, encodeBookGlossaryJournal(target, committed).toString()) {
                    when (action) {
                        0 -> assertEquals(committed, other.read(target))
                        1 -> assertEquals(listOf(target), other.targets(account))
                        else -> other.write(target, subsequent)
                    }
                }
                assertEquals(if (action == 2) subsequent else committed, writer.read(target))
            }
        }
    }

    /** Hold a real unfinished atomic write while another instance enters each public operation. */
    private fun atomicCommitWhileBlocked(directory: File, base: File, json: String, operation: () -> Unit) {
        val executor = Executors.newSingleThreadExecutor()
        var future: Future<*>? = null
        try {
            ServerJournalFileLocks.withDirectory(directory) {
                val file = AtomicFile(base)
                val stream = file.startWrite()
                try {
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    val half = bytes.size / 2
                    stream.write(bytes, 0, half)
                    val entered = CountDownLatch(1)
                    future = executor.submit { entered.countDown(); operation() }
                    assertTrue("Worker was not scheduled", entered.await(5, TimeUnit.SECONDS))
                    try {
                        requireNotNull(future).get(200, TimeUnit.MILLISECONDS)
                        fail("Another store accessed a journal during its AtomicFile commit")
                    } catch (_: TimeoutException) {
                        // Correct: the caller must wait, rather than repairing an active .new/.bak.
                    }
                    stream.write(bytes, half, bytes.size - half)
                    file.finishWrite(stream)
                } catch (error: Throwable) {
                    file.failWrite(stream)
                    throw error
                }
            }
            requireNotNull(future).get(5, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
            assertTrue("Journal worker did not stop", executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun withDirectory(block: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "journal-lock-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
