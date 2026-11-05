package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production scoped snapshot/CAS and real JVM files, with injected IO rather than Android AtomicFile. */
class ServerReadingProgressSnapshotCasTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val upper = target.copy(recordId = target.recordId.uppercase())
    private val mixed = target.copy(recordId = "AbCdEfAb-CdEf-4AbC-8DeF-AbCdEfAbCdEf")
    private val anchor = ServerReadingAnchor("  P:é e\u0301 🌏  ", 7)
    private val time = "2026-11-06T00:00:00Z"
    private fun record(scope: ServerReadingTarget = target, version: Long = 3, viewId: String = scope.recordId) = DeviceReadingProgress(
        ServerReadingProgress(scope.kind, viewId, version, anchor, time),
        ServerReadingMutation(version, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor),
        anchor.copy(characterOffset = 8), ServerReadingProgress(scope.kind, viewId, version + 1, anchor, time), 123_456,
    )
    private fun file(directory: File, scope: ServerReadingTarget = target) = File(directory, StableContentHash.sha256(scope.key) + ".json")
    private fun seed(directory: File, scope: ServerReadingTarget, value: DeviceReadingProgress, backup: Boolean = false): File =
        File(file(directory, scope).path + if (backup) ".bak" else "").also {
            check(directory.isDirectory || directory.mkdirs())
            it.writeBytes(encodeDeviceReadingProgress(scope, value).toString().toByteArray(Charsets.UTF_8))
        }
    private fun bytes(directory: File) = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
    private fun store(directory: File, io: StagedIo = StagedIo()) = FileServerReadingProgressStore(directory, io)
    private fun denied(block: () -> Unit) { assertThrows(Exception::class.java) { block() } }

    @Test fun missingCaptureDoesNotCreateDirectoryAndIsDifferentFromExistingEmptyRow() {
        val directory = File(temporary.root, "absent"); val io = StagedIo(); val store = store(directory, io)
        val absent = store.captureSnapshot(target)
        assertNull(absent.canonical); assertTrue(absent.aliases.isEmpty()); assertFalse(directory.exists())
        seed(directory, target, DeviceReadingProgress())
        val empty = store.captureSnapshot(target)
        assertEquals(DeviceReadingProgress(), empty.canonical); assertEquals(1, empty.aliases.size)
        assertNotEquals(absent, empty); assertEquals(0, io.legacyReads); assertEquals(0, io.legacyWrites)
    }

    @Test fun absentCanonicalDoesNotHideUppercasePendingHighRemoteOrRawViewSpelling() {
        val directory = temporary.newFolder(); val io = StagedIo(); val expected = record(upper, 19, mixed.recordId)
        seed(directory, upper, expected); val before = bytes(directory)
        val observed = store(directory, io).captureSnapshot(target)
        assertNull(observed.canonical); assertEquals(listOf(ReadingProgressJournalObservation(upper, expected)), observed.aliases)
        assertEquals(mixed.recordId, observed.aliases.single().record.remote!!.recordId)
        assertEquals(expected.pending, observed.aliases.single().record.pending)
        assertEquals(expected.queuedAnchor, observed.aliases.single().record.queuedAnchor)
        assertEquals(expected.retryAfterUntil, observed.aliases.single().record.retryAfterUntil)
        assertEquals(before, bytes(directory)); assertEquals(0, io.legacyReads)
    }

    @Test fun aliasOrderDoesNotChangeSnapshotEqualityOrHashCode() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record()); seed(directory, upper, record(upper, 9)); seed(directory, mixed, record(mixed, 12))
        val first = store.captureSnapshot(target); io.reverseInventory = true
        val second = store.captureSnapshot(target)
        assertEquals(first, second); assertEquals(first.hashCode(), second.hashCode())
        assertEquals(first, ReadingProgressJournalSnapshot(target, first.aliases.reversed()))
    }

    @Test fun snapshotCopiesCallerAndGetterListsBeforeGuardsCanMutateThem() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record()); seed(directory, upper, record(upper, 8))
        val input = store.captureSnapshot(target).aliases.toMutableList()
        val expected = ReadingProgressJournalSnapshot(target, input); val retained = expected.aliases
        input.clear(); assertEquals(2, expected.aliases.size)
        val next = record(version = 4)
        assertTrue(store.compareAndWrite(target, expected, next) {
            runCatching { (retained as MutableList).clear() }
            runCatching { (expected.aliases as MutableList).clear() }
        })
        assertEquals(2, expected.aliases.size); assertEquals(record(), expected.canonical)
        assertEquals(next, store.captureSnapshot(target).canonical)
    }

    @Test fun captureUsesListedBackupWithoutRecoveryAndPreservesBothPhysicalSources() {
        val directory = temporary.newFolder(); val io = StagedIo()
        seed(directory, target, record(version = 1)); seed(directory, target, record(version = 7), backup = true)
        val before = bytes(directory); val observed = store(directory, io).captureSnapshot(target)
        assertEquals(record(version = 7), observed.canonical); assertEquals(before, bytes(directory))
        assertTrue(io.observedNames.single().endsWith(".bak")); assertEquals(0, io.legacyReads)
    }

    @Test fun changedBackupMakesCasMismatchWithoutRecoveryOrStaging() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record(version = 1)); seed(directory, target, record(version = 7), backup = true)
        val expected = store.captureSnapshot(target)
        seed(directory, target, record(version = 8), backup = true); val before = bytes(directory)
        assertFalse(store.compareAndWrite(target, expected, record(version = 9)) {})
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages); assertEquals(0, io.legacyReads)
    }

    @Test fun newlyExistingEmptyCanonicalRowDoesNotMatchPriorAbsence() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val absent = store.captureSnapshot(target); seed(directory, target, DeviceReadingProgress()); val before = bytes(directory)
        assertFalse(store.compareAndWrite(target, absent, record()) {})
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
    }

    @Test fun addingAnAliasBetweenCaptureAndCasCannotBeIgnored() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record()); val expected = store.captureSnapshot(target)
        seed(directory, upper, record(upper, 20)); val before = bytes(directory)
        assertFalse(store.compareAndWrite(target, expected, record(version = 4)) {})
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
    }

    @Test fun deletingAnAliasBetweenCaptureAndCasCannotBeIgnored() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record()); val alias = seed(directory, upper, record(upper, 20))
        val expected = store.captureSnapshot(target); assertTrue(alias.delete()); val before = bytes(directory)
        assertFalse(store.compareAndWrite(target, expected, record(version = 4)) {})
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
    }

    @Test fun everyAliasJournalFieldParticipatesInExpectedComparison() {
        val original = record(upper, 8)
        val variants = listOf(original.copy(remote = original.remote!!.copy(version = 20)),
            original.copy(pending = original.pending!!.copy(mutationId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc")),
            original.copy(queuedAnchor = anchor.copy(characterOffset = 99)),
            original.copy(conflict = original.conflict!!.copy(version = 21)), original.copy(retryAfterUntil = 987_654))
        variants.forEach { changed ->
            val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
            seed(directory, upper, original); val expected = store.captureSnapshot(target)
            seed(directory, upper, changed); val before = bytes(directory)
            assertFalse(store.compareAndWrite(target, expected, record()) {})
            assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
        }
    }

    @Test fun canonicalWritePreservesAllHistoricalAliasFilesAndTheirRawUuidValues() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, upper, record(upper, 8, mixed.recordId)); seed(directory, mixed, record(mixed, 11, upper.recordId))
        val aliasesBefore = bytes(directory); val expected = store.captureSnapshot(target); assertNull(expected.canonical)
        val next = record(version = 12)
        assertTrue(store.compareAndWrite(target, expected, next) {})
        val actual = store.captureSnapshot(target)
        assertEquals(next, actual.canonical); assertEquals(3, actual.aliases.size)
        aliasesBefore.forEach { (name, source) -> assertEquals(source, bytes(directory).getValue(name)) }
        assertEquals(1, io.commits); assertEquals(0, io.legacyWrites)
    }

    @Test fun anotherValidAccountRowDoesNotInvalidateTheScopedTokenOrGetOverwritten() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target)
        val other = target.copy(accountKey = "b".repeat(64), kind = "TRANSLATION")
        val otherFile = seed(directory, other, record(other, 17)); val original = otherFile.readBytes()
        assertTrue(store.compareAndWrite(target, expected, record()) {})
        assertArrayEquals(original, otherFile.readBytes()); assertEquals(record(), store.captureSnapshot(target).canonical)
    }

    @Test fun corruptUnrelatedJournalFailsClosedBeforeWritingAnyCanonicalRow() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target)
        val bad = target.copy(accountKey = "b".repeat(64)); seed(directory, bad, record(bad)).writeText("{broken")
        val before = bytes(directory)
        denied { store.compareAndWrite(target, expected, record()) {} }
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages); assertEquals(0, io.legacyWrites)
    }

    @Test fun duplicateRawPendingCannotDisappearDuringCasObservation() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target); val base = seed(directory, upper, record(upper))
        base.writeText(base.readText().dropLast(1) + ",\"pending\":null}"); val before = bytes(directory)
        denied { store.compareAndWrite(target, expected, record()) {} }
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
    }

    @Test fun invalidUtf8RemainsUnchangedAndIsNotDecodedWithReplacement() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target); file(directory).writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        val before = bytes(directory)
        denied { store.compareAndWrite(target, expected, record()) {} }
        assertEquals(before, bytes(directory)); assertEquals(0, io.stages)
    }

    @Test fun expectedScopeMismatchAndNoncanonicalQueryRejectBeforeAnyGuardOrIo() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io); var guards = 0
        val expected = store.captureSnapshot(target); val reads = io.observationReads
        denied { store.compareAndWrite(target.copy(kind = "TRANSLATION"), expected, record(), { guards++ }) }
        denied { store.captureSnapshot(upper) }
        denied { store.captureSnapshot(target.copy(accountKey = "not-an-account")) }
        assertEquals(0, guards); assertEquals(reads, io.observationReads); assertEquals(0, io.stages)
    }

    @Test fun malformedTypedSnapshotCannotSmuggleDuplicateOrDifferentScopeAliases() {
        val observation = ReadingProgressJournalObservation(target, record())
        denied { ReadingProgressJournalSnapshot(target, listOf(observation, observation)) }
        denied { ReadingProgressJournalSnapshot(target, listOf(observation.copy(target = target.copy(accountKey = "b".repeat(64))))) }
        denied { ReadingProgressJournalSnapshot(target, listOf(observation.copy(record = record().copy(queuedAnchor = anchor, pending = null)))) }
    }

    @Test fun invalidNextValueIsRejectedBeforeGuardsAndBeforeSourceFilesAreTouched() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target); var guards = 0
        val invalid = DeviceReadingProgress(queuedAnchor = anchor)
        denied { store.compareAndWrite(target, expected, invalid) { guards++ } }
        assertEquals(0, guards); assertTrue(bytes(directory).isEmpty()); assertEquals(0, io.stages)
    }

    @Test fun newInterfaceApisAreUnsupportedRatherThanFallingBackToLegacyReadsOrWrites() {
        val calls = AtomicInteger()
        val legacy = object : ServerReadingProgressStore {
            override fun read(document: ServerReadingDocument): DeviceReadingProgress { calls.incrementAndGet(); return DeviceReadingProgress() }
            override fun read(target: ServerReadingTarget): DeviceReadingProgress { calls.incrementAndGet(); return DeviceReadingProgress() }
            override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) { calls.incrementAndGet() }
            override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) { calls.incrementAndGet() }
            override fun pending(accountKey: String): List<ServerReadingTarget> { calls.incrementAndGet(); return emptyList() }
        }
        assertThrows(UnsupportedOperationException::class.java) { legacy.captureSnapshot(target) }
        assertThrows(UnsupportedOperationException::class.java) { legacy.compareAndWrite(target, ReadingProgressJournalSnapshot(target, emptyList()), record()) {} }
        assertEquals(0, calls.get())
    }

    @Test fun unsupportedGuardedIoDoesNotUseUnconditionalLegacyWrite() {
        val directory = File(temporary.root, "unsupported"); val io = LegacyIo(); val store = FileServerReadingProgressStore(directory, io)
        val expected = store.captureSnapshot(target)
        assertThrows(UnsupportedOperationException::class.java) { store.compareAndWrite(target, expected, record()) {} }
        assertEquals(0, io.legacyWrites); assertTrue(bytes(directory).isEmpty()); assertFalse(directory.exists())
    }

    @Test fun unsupportedInventoryOrSelectedSourceReadCannotBecomeAnEmptySnapshot() {
        val directory = temporary.newFolder()
        val noInventory = object : ReadingProgressJournalIo {
            override fun exists(file: File) = false
            override fun read(file: File) = error("No legacy fallback")
            override fun write(file: File, bytes: ByteArray) = error("No write fallback")
        }
        assertThrows(UnsupportedOperationException::class.java) { FileServerReadingProgressStore(directory, noInventory).captureSnapshot(target) }
        seed(directory, target, record())
        val failed = object : LegacyIo() { override fun readObservation(file: File): ByteArray = throw IOException("Selected source unreadable") }
        assertThrows(IOException::class.java) { FileServerReadingProgressStore(directory, failed).captureSnapshot(target) }
        assertEquals(0, failed.legacyReads); assertEquals(0, failed.legacyWrites)
    }

    @Test fun twoStoreInstancesAndDirectoryAliasesHaveExactlyOneCasWinner() {
        val directory = temporary.newFolder(); val io = StagedIo()
        val first = store(directory, io); val second = store(File(directory, "."), io)
        val expected = first.captureSnapshot(target); val start = CountDownLatch(1); val pool = Executors.newFixedThreadPool(2)
        try {
            val left = pool.submit<Boolean> { check(start.await(10, TimeUnit.SECONDS)); first.compareAndWrite(target, expected, record(version = 4)) {} }
            val right = pool.submit<Boolean> { check(start.await(10, TimeUnit.SECONDS)); second.compareAndWrite(target, expected, record(version = 5)) {} }
            start.countDown(); val results = listOf(left.get(10, TimeUnit.SECONDS), right.get(10, TimeUnit.SECONDS))
            assertEquals(1, results.count { it }); assertEquals(1, io.commits)
            assertTrue(first.captureSnapshot(target).canonical!!.remote!!.version in listOf(4L, 5L))
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun stageGuardAndCommitRetainTheDirectoryMonitorAgainstNormalWriters() {
        val directory = temporary.newFolder(); val io = StagedIo(); val first = store(directory, io); val second = store(File(directory, "."), io)
        val expected = first.captureSnapshot(target); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val writerThread = AtomicReference<Thread>(); val pool = Executors.newFixedThreadPool(2)
        io.afterStage = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }
        try {
            val committing = pool.submit<Boolean> { first.compareAndWrite(target, expected, record(version = 4)) {} }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val writing = pool.submit { writerThread.set(Thread.currentThread()); second.write(target, record(version = 7)) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (writerThread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, writerThread.get()?.state); assertEquals(0, io.legacyWrites)
            release.countDown(); assertTrue(committing.get(10, TimeUnit.SECONDS)); writing.get(10, TimeUnit.SECONDS)
            assertEquals(record(version = 7), first.captureSnapshot(target).canonical)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }

    @Test fun lastGuardFailureRollsBackStagedWriteAndPreservesLogicalBackupValue() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        seed(directory, target, record(version = 1)); seed(directory, target, record(version = 7), backup = true)
        val expected = store.captureSnapshot(target); var live = true
        io.afterStage = { live = false }
        assertThrows(IllegalStateException::class.java) { store.compareAndWrite(target, expected, record(version = 8)) { check(live) } }
        assertEquals(expected, store.captureSnapshot(target)); assertEquals(1, io.stages); assertEquals(0, io.commits)
        assertEquals(0, io.legacyWrites); assertFalse(File(directory, "test-stage").exists())
    }

    @Test fun firstGuardFailureDoesNotCreateDirectoryOrStageAnything() {
        val directory = File(temporary.root, "missing"); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target)
        assertThrows(IllegalStateException::class.java) { store.compareAndWrite(target, expected, record()) { error("Revoked") } }
        assertFalse(directory.exists()); assertEquals(0, io.stages); assertEquals(0, io.commits)
    }

    @Test fun retirementAfterDurableCommitDoesNotRunAPostCommitGuardOrReportRollback() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target); var live = true; var guardCalls = 0
        io.afterCommit = { live = false }
        assertTrue(store.compareAndWrite(target, expected, record()) { guardCalls++; check(live) })
        assertFalse(live); assertTrue(guardCalls > 0); assertEquals(1, io.commits)
        assertEquals(record(), store.captureSnapshot(target).canonical)
    }

    @Test fun reentrantLegacyReadsWritesPendingAndNestedCasAreRejectedAcrossDirectoryAliases() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val alias = store(File(directory, "."), io)
        seed(directory, target, record(version = 3)); val expected = store.captureSnapshot(target)
        val forbiddenAttempts = AtomicInteger()
        assertTrue(store.compareAndWrite(target, expected, record(version = 4)) {
            assertThrows(IllegalStateException::class.java) { alias.read(target) }; forbiddenAttempts.incrementAndGet()
            assertThrows(IllegalStateException::class.java) { alias.write(target, record(version = 99)) }; forbiddenAttempts.incrementAndGet()
            assertThrows(IllegalStateException::class.java) { alias.pending(target.accountKey) }; forbiddenAttempts.incrementAndGet()
            assertThrows(IllegalStateException::class.java) { alias.compareAndWrite(target, expected, record(version = 98)) {} }
            forbiddenAttempts.incrementAndGet()
        })
        assertTrue(forbiddenAttempts.get() >= 4); assertEquals(0, io.legacyReads); assertEquals(0, io.legacyWrites)
        assertEquals(1, io.commits); assertEquals(record(version = 4), store.captureSnapshot(target).canonical)
    }

    @Test fun guardFailureClearsSharedCasScopeSoLaterLegacyOperationsStillWork() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val alias = store(File(directory, "."), io); seed(directory, target, record())
        val expected = store.captureSnapshot(target)
        assertThrows(IllegalStateException::class.java) { store.compareAndWrite(target, expected, record(version = 4)) { error("Revoked") } }
        alias.write(target, record(version = 6))
        assertEquals(record(version = 6), alias.read(target)); assertEquals(listOf(target), alias.pending(target.accountKey))
        assertEquals(1, io.legacyWrites); assertTrue(io.legacyReads > 0)
    }

    @Test fun unsafeIntegerDeadlineNextValueIsRefusedBeforeGuardOrWriteWhileSafeBoundaryCommits() {
        val directory = temporary.newFolder(); val io = StagedIo(); val store = store(directory, io)
        val expected = store.captureSnapshot(target); var guards = 0
        denied { store.compareAndWrite(target, expected, record().copy(retryAfterUntil = Long.MAX_VALUE)) { guards++ } }
        assertEquals(0, guards); assertEquals(0, io.stages); assertTrue(bytes(directory).isEmpty())
        val safe = record().copy(retryAfterUntil = 9_007_199_254_740_991)
        assertTrue(store.compareAndWrite(target, expected, safe) {})
        assertEquals(safe, store.captureSnapshot(target).canonical)
    }

    @Test fun unsafeIntegerDeadlineCannotBeSmuggledThroughATypedSnapshotConstructor() {
        val unsafe = ReadingProgressJournalObservation(upper, record(upper).copy(retryAfterUntil = Long.MAX_VALUE))
        denied { ReadingProgressJournalSnapshot(target, listOf(unsafe)) }
    }

    private open class LegacyIo : ReadingProgressJournalIo {
        var reverseInventory = false
        var legacyReads = 0
        var legacyWrites = 0
        var observationReads = 0
        val observedNames = mutableListOf<String>()
        override fun inventory(directory: File): List<File>? = if (!directory.exists()) null else {
            check(directory.isDirectory)
            checkNotNull(directory.listFiles()).toList().let { if (reverseInventory) it.reversed() else it }
        }
        override fun readObservation(file: File): ByteArray { observationReads++; observedNames += file.name; return file.readBytes() }
        override fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
        override fun read(file: File): ByteArray { legacyReads++; return (if (File(file.path + ".bak").exists()) File(file.path + ".bak") else file).readBytes() }
        override fun write(file: File, bytes: ByteArray) { legacyWrites++; file.writeBytes(bytes) }
    }

    /** Implements an injected logical transaction; production Android AtomicFile has separate native tests. */
    private class StagedIo : LegacyIo() {
        var stages = 0
        var commits = 0
        var afterStage: () -> Unit = {}
        var afterCommit: () -> Unit = {}
        override fun writeGuarded(file: File, bytes: ByteArray, guard: () -> Unit) {
            val backup = File(file.path + ".bak")
            val oldBase = file.takeIf { it.exists() }?.readBytes()
            val oldBackup = backup.takeIf { it.exists() }?.readBytes()
            val staging = File(file.parentFile, "test-stage")
            try {
                staging.writeBytes(bytes); stages++; afterStage(); guard()
                file.writeBytes(staging.readBytes()); if (backup.exists()) check(backup.delete())
                commits++
            } catch (error: Throwable) {
                if (oldBase == null) file.delete() else file.writeBytes(oldBase)
                if (oldBackup == null) backup.delete() else backup.writeBytes(oldBackup)
                throw error
            } finally { staging.delete() }
            afterCommit()
        }
    }
}
