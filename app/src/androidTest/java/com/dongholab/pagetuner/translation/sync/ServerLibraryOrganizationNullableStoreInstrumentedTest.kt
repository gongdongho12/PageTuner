package com.dongholab.pagetuner.translation.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AtomicFile coverage. Source compilation alone is not device execution evidence. */
@RunWith(AndroidJUnit4::class)
class ServerLibraryOrganizationNullableStoreInstrumentedTest {
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "00000000-0000-4000-8000-000000000001")
    private val empty = DeviceLibraryOrganization()
    private val value = DeviceLibraryOrganization(pending = LibraryOrganizationMutation(0,
        "00000000-0000-4000-8000-000000000002", LibraryOrganization("읽을  책 e\u0301", listOf("é", "e\u0301", "Tag", "tag", "中  文"), true)),
        retryAfterUntil = 12_345)

    @Test fun missingAndExistingEmptySurviveRestartAsDifferentCasBases() = withDirectory { directory ->
        val store = FileServerLibraryOrganizationStore(directory)
        assertNull(store.readNullable(target)); assertEquals(empty, store.read(target))
        assertTrue(store.compareAndWrite(target, null, empty) {})
        val restarted = FileServerLibraryOrganizationStore(File(directory, "."))
        assertEquals(empty, restarted.readNullable(target))
        assertFalse(restarted.compareAndWrite(target, null, value) {})
        assertTrue(restarted.compareAndWrite(target, empty, value) {})
        assertEquals(value, store.readNullable(target))
    }

    @Test fun finalStagedGuardFailureRollsBackMissingAndExistingJournals() {
        for (existing in listOf(false, true)) withDirectory { directory ->
            val store = FileServerLibraryOrganizationStore(directory)
            if (existing) store.write(target, value)
            val base = journal(directory); var sawStaged = false
            assertThrows(IllegalStateException::class.java) {
                store.compareAndWrite(target, if (existing) value else null, empty) {
                    val staged = File(base.path + ".new").exists() || File(base.path + ".bak").exists() || !existing && base.exists()
                    if (staged) { sawStaged = true; error("Selection changed after staging") }
                }
            }
            assertTrue("Final staged guard was not called", sawStaged)
            assertEquals(if (existing) value else null, FileServerLibraryOrganizationStore(directory).readNullable(target))
            assertFalse(File(base.path + ".new").exists()); assertFalse(File(base.path + ".bak").exists())
        }
    }

    @Test fun backupRecoveryPreservesFullIntentBeforeComparingNullableBase() = withDirectory { directory ->
        val base = journal(directory)
        val remote = ServerLibraryOrganization(target.kind, target.recordId, 1, LibraryOrganization("Remote"), "2026-10-24T00:00:00Z")
        val recovered = value.copy(remote = remote, queued = LibraryOrganization("Queued"), conflict = remote)
        base.writeText("partial invalid write")
        File(base.path + ".bak").writeText(encodeLibraryOrganizationJournal(target, recovered).toString())
        val store = FileServerLibraryOrganizationStore(directory)
        assertEquals(recovered, store.readNullable(target))
        val bytes = base.readBytes()
        assertFalse(store.compareAndWrite(target, null, empty) {})
        assertArrayEquals(bytes, base.readBytes()); assertEquals(recovered, store.readNullable(target))
        assertEquals(listOf(target), store.targets(target.accountKey))
    }

    @Test fun concurrentStoresHaveOnlyOneNullCasWinner() = withDirectory { directory ->
        val start = CountDownLatch(1); val executor = Executors.newFixedThreadPool(2)
        try {
            val stores = listOf(FileServerLibraryOrganizationStore(directory), FileServerLibraryOrganizationStore(File(directory, ".")))
            val attempts = stores.mapIndexed { index, store -> executor.submit<Boolean> {
                check(start.await(5, TimeUnit.SECONDS))
                store.compareAndWrite(target, null, value.copy(retryAfterUntil = index.toLong())) {}
            } }
            start.countDown()
            assertEquals(1, attempts.count { it.get(5, TimeUnit.SECONDS) })
            assertNotNull(stores.first().readNullable(target)?.pending)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun corruptAndOversizedExistingBytesAreNotReplacedByMissingBase() = withDirectory { directory ->
        val store = FileServerLibraryOrganizationStore(directory); val base = journal(directory)
        for (bytes in listOf("{}".toByteArray(), ByteArray(65_537) { ' '.code.toByte() })) {
            base.writeBytes(bytes)
            assertThrows(Exception::class.java) { store.compareAndWrite(target, null, empty) {} }
            assertArrayEquals(bytes, base.readBytes())
        }
    }

    private fun journal(directory: File) = File(directory, StableContentHash.sha256(target.key) + ".json")
    private fun withDirectory(block: (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "organization-nullable-${UUID.randomUUID()}")
        check(directory.mkdirs())
        try { block(directory) } finally { directory.deleteRecursively() }
    }
}
