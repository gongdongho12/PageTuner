package com.dongholab.pagetuner.translation.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Android AtomicFile/stat coverage. Compiling this source is not evidence that it executed. */
@RunWith(AndroidJUnit4::class)
class ServerReadingProgressSnapshotCasInstrumentedTest {
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val anchor = ServerReadingAnchor(" p:é é 🌏 ", 3)
    private fun value(version: Long = 3) = DeviceReadingProgress(
        remote = ServerReadingProgress(target.kind, target.recordId, version, anchor, "2026-11-06T00:00:00Z"),
        pending = ServerReadingMutation(version, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor),
        queuedAnchor = anchor.copy(characterOffset = 4), retryAfterUntil = 123456,
    )
    private fun withRoot(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "progress-cas-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { root.deleteRecursively() }
    }
    private fun base(directory: File, scope: ServerReadingTarget = target) = File(directory, StableContentHash.sha256(scope.key) + ".json")
    private fun bytes(directory: File) = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

    @Test fun absenceEmptyRowAndGuardedAtomicCommitRemainDistinctAcrossRestart() = withRoot { root ->
        val directory = File(root, "journals"); val first = FileServerReadingProgressStore(directory)
        val absent = first.captureSnapshot(target)
        assertNull(absent.canonical); assertTrue(absent.aliases.isEmpty()); assertFalse(directory.exists())
        first.write(target, DeviceReadingProgress())
        val empty = first.captureSnapshot(target); val before = bytes(directory)
        assertEquals(DeviceReadingProgress(), empty.canonical)
        assertFalse(first.compareAndWrite(target, absent, value()) {}); assertEquals(before, bytes(directory))
        assertTrue(first.compareAndWrite(target, empty, value()) {})
        val restarted = FileServerReadingProgressStore(File(directory, "."))
        assertEquals(value(), restarted.captureSnapshot(target).canonical)
        assertEquals(value(), restarted.read(target))
    }

    @Test fun aliasChangeRejectsWithoutRecoveryAndSuccessPreservesOriginalAliasBytes() = withRoot { root ->
        val directory = File(root, "journals"); val store = FileServerReadingProgressStore(directory)
        val empty = store.captureSnapshot(target)
        val upper = target.copy(recordId = target.recordId.uppercase(java.util.Locale.ROOT))
        val aliasValue = value(7).copy(remote = value(7).remote!!.copy(recordId = upper.recordId))
        store.write(upper, aliasValue)
        val aliasBefore = base(directory, upper).readBytes()
        assertNull(store.captureSnapshot(target).canonical)
        assertFalse(store.compareAndWrite(target, empty, value()) {})
        assertFalse(base(directory).exists()); assertArrayEquals(aliasBefore, base(directory, upper).readBytes())
        val expected = store.captureSnapshot(target)
        assertTrue(store.compareAndWrite(target, expected, value()) {})
        assertArrayEquals(aliasBefore, base(directory, upper).readBytes())
        assertEquals(aliasValue, store.captureSnapshot(target).aliases.single { it.target == upper }.record)
    }

    @Test fun backupOnlyExpectedReadIsStrictAndGuardFailureDoesNotRecoverIt() = withRoot { root ->
        val directory = File(root, "journals").also { check(it.mkdir()) }
        val backup = File(base(directory).path + ".bak")
        backup.writeBytes(encodeDeviceReadingProgress(target, value()).toString().toByteArray(Charsets.UTF_8))
        val store = FileServerReadingProgressStore(directory); val expected = store.captureSnapshot(target)
        assertEquals(value(), expected.canonical); assertFalse(base(directory).exists())
        val before = bytes(directory)
        assertThrows(IllegalStateException::class.java) { store.compareAndWrite(target, expected, value(4)) { error("Retired scope") } }
        assertEquals(before, bytes(directory)); assertFalse(base(directory).exists())
    }

    @Test fun twoStoreInstancesShareTheSnapshotComparisonUntilOneCommitWins() = withRoot { root ->
        val directory = File(root, "journals"); val first = FileServerReadingProgressStore(directory)
        val second = FileServerReadingProgressStore(File(directory, ".")); val expected = first.captureSnapshot(target)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val one = pool.submit<Boolean> { first.compareAndWrite(target, expected, value()) {
                entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val two = pool.submit<Boolean> { second.compareAndWrite(target, expected, value(4)) {} }
            release.countDown(); assertTrue(one.get(10, TimeUnit.SECONDS)); assertFalse(two.get(10, TimeUnit.SECONDS))
            assertEquals(value(), first.captureSnapshot(target).canonical)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun abortAtActualAtomicFileFinalGuardRestoresTheLogicalSnapshot() = withRoot { root ->
        val directory = File(root, "journals"); val store = FileServerReadingProgressStore(directory)
        store.write(target, value()); val expected = store.captureSnapshot(target)
        val guards = AtomicInteger()
        assertThrows(IllegalStateException::class.java) {
            store.compareAndWrite(target, expected, value(4)) {
                // File store: precompare + prewrite; Atomic IO: prestage + after flush/fd.sync.
                if (guards.incrementAndGet() == 4) error("Scope retired at final abortable boundary")
            }
        }
        assertEquals(4, guards.get())
        assertEquals(expected, store.captureSnapshot(target))
        assertEquals(value(), store.read(target))
    }
}
