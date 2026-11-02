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

/** Production observation/locking with injected file IO, not Android AtomicFile or device execution. */
class ServerReadingProgressObservationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val timestamp = "2026-11-02T00:00:00Z"
    private val anchor = ServerReadingAnchor("  P:é e\u0301 🌏  ", 7)
    private val mutationId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

    private fun record(scope: ServerReadingTarget = target, version: Long = 3,
        viewId: String = scope.recordId): DeviceReadingProgress {
        val remote = ServerReadingProgress(scope.kind, viewId, version, anchor, timestamp)
        return DeviceReadingProgress(remote, ServerReadingMutation(version, mutationId, anchor),
            ServerReadingAnchor("Queued  e\u0301", 8), remote.copy(version = version + 1), 456_789)
    }

    @Test fun verifiedMissingDirectoryIsEmptyButAnExistingEmptyJournalIsObserved() {
        val directory = File(temporary.root, "missing")
        val io = FileIo(); val store = FileServerReadingProgressStore(directory, io)
        assertTrue(store.observeAliases(target).isEmpty())
        assertFalse(directory.exists()); assertEquals(0, io.reads); assertEquals(0, io.writes)
        check(directory.mkdir())
        save(directory, target, DeviceReadingProgress())
        val before = snapshot(directory)
        val observed = store.observeAliases(target)
        assertEquals(1, observed.size); assertEquals(target, observed.single().target)
        assertEquals(DeviceReadingProgress(), observed.single().record)
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }

    @Test fun uppercaseAndMixedCaseAliasesRetainRawScopeAndAllJournalFields() {
        val directory = temporary.newFolder(); val io = FileIo()
        val upper = target.copy(recordId = target.recordId.uppercase())
        val mixed = target.copy(recordId = "AbCdEfAb-CdEf-4AbC-8DeF-AbCdEfAbCdEf")
        val expected = linkedMapOf(target to record(), upper to record(upper, 8, mixed.recordId),
            mixed to record(mixed, 11, upper.recordId))
        expected.forEach { (scope, value) -> save(directory, scope, value) }
        val before = snapshot(directory)
        val observations = FileServerReadingProgressStore(directory, io).observeAliases(target)
        assertEquals(expected, observations.associate { it.target to it.record })
        assertTrue(observations.any { it.target.recordId == upper.recordId && it.record.conflict!!.version == 9L })
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
        assertEquals(expected.keys.map { it.key }.toSet(), observations.map { it.target.key }.toSet())
    }

    @Test fun anUppercasePendingAliasIsVisibleWhenLegacyLowercaseReadAndPendingMissIt() {
        val directory = temporary.newFolder(); val io = FileIo()
        val upper = target.copy(recordId = target.recordId.uppercase()); val value = record(upper, 19)
        save(directory, upper, value)
        val before = snapshot(directory); val store = FileServerReadingProgressStore(directory, io)
        assertEquals(DeviceReadingProgress(), store.read(target))
        assertTrue(store.pending(target.accountKey).isEmpty())
        val observed = store.observeAliases(target).single()
        assertEquals(upper, observed.target); assertEquals(value, observed.record)
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }

    @Test fun validOtherAccountKindAndRecordRowsDoNotEnterTheRequestedScope() {
        val directory = temporary.newFolder(); val io = FileIo(); val value = record()
        save(directory, target, value)
        listOf(target.copy(accountKey = "b".repeat(64)), target.copy(kind = "TRANSLATION"),
            target.copy(recordId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc")).forEach {
            save(directory, it, record(it))
        }
        val before = snapshot(directory)
        val observed = FileServerReadingProgressStore(directory, io).observeAliases(target)
        assertEquals(1, observed.size); assertEquals(target, observed.single().target)
        assertEquals(value, observed.single().record)
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }

    @Test fun baseAndBackupNamesAreDeduplicatedAndBackupOnlyRowsAreNotLost() {
        val directory = temporary.newFolder(); val io = FileIo()
        val upper = target.copy(recordId = target.recordId.uppercase())
        save(directory, target, record())
        File(journal(directory).path + ".bak").writeBytes(journal(directory).readBytes())
        save(directory, upper, record(upper, 6), backup = true)
        val before = snapshot(directory)
        val values = FileServerReadingProgressStore(directory, io).observeAliases(target)
        assertEquals(2, values.size); assertEquals(2, io.reads)
        assertEquals(setOf(target, upper), values.map { it.target }.toSet())
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }

    @Test fun listedBackupWinsOverAValidBaseRegardlessOfInventoryOrderWithoutRecovery() {
        for (reverse in listOf(false, true)) {
            val directory = temporary.newFolder(); val io = FileIo()
            val baseValue = DeviceReadingProgress(remote = record().remote)
            val backupValue = record(version = 8)
            save(directory, target, baseValue); save(directory, target, backupValue, backup = true)
            io.inventoryTransform = { files -> files.sortedBy { it.name }.let { if (reverse) it.reversed() else it } }
            val before = snapshot(directory)
            val observed = FileServerReadingProgressStore(directory, io).observeAliases(target).single()
            assertEquals(backupValue, observed.record)
            assertEquals(listOf(journal(directory).name + ".bak"), io.observedNames)
            assertEquals(0, io.legacyReads); assertEquals(0, io.writes)
            assertEquals(before, snapshot(directory))
        }
    }

    @Test fun preferredBackupReadFailureDoesNotFallbackToTheValidBaseOrRecoverIt() {
        val directory = temporary.newFolder(); val io = FileIo()
        save(directory, target, DeviceReadingProgress(remote = record().remote))
        save(directory, target, record(version = 8), backup = true)
        val before = snapshot(directory)
        io.beforeObservationRead = { check(it.name.endsWith(".bak")); throw IOException("preferred backup unavailable") }
        val store = FileServerReadingProgressStore(directory, io)
        assertThrows(IOException::class.java) { store.observeAliases(target) }
        assertEquals(before, snapshot(directory)); assertEquals(0, io.legacyReads); assertEquals(0, io.writes)
        io.beforeObservationRead = {}
        assertEquals(record(version = 8), store.observeAliases(target).single().record)
        assertEquals(before, snapshot(directory)); assertEquals(0, io.legacyReads); assertEquals(0, io.writes)
    }

    @Test fun optionalLegacyRetryAbsenceAndNonjournalFilesArePreserved() {
        val directory = temporary.newFolder(); val io = FileIo()
        val old = encodeDeviceReadingProgress(target, record()).also { it.remove("retryAfterUntil") }
        journal(directory).writeText(old.toString()); File(directory, "README.txt").writeText("keep")
        val before = snapshot(directory)
        val observed = FileServerReadingProgressStore(directory, io).observeAliases(target).single()
        assertEquals(record().copy(retryAfterUntil = null), observed.record)
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }

    @Test fun invalidRequestedScopeFailsBeforeInventoryOrRecordIo() {
        val directory = temporary.newFolder(); val io = FileIo()
        val store = FileServerReadingProgressStore(directory, io)
        listOf(target.copy(accountKey = "bad"), target.copy(kind = "original"),
            target.copy(recordId = target.recordId.uppercase()), target.copy(recordId = "1-1-1-1-1")).forEach {
            assertThrows(Exception::class.java) { store.observeAliases(it) }
        }
        assertEquals(0, io.inventories); assertEquals(0, io.reads); assertEquals(0, io.writes)
    }

    @Test fun unsupportedStoresAndUnsupportedOrFailedInventoriesNeverFallbackToEmpty() {
        val legacy = object : ServerReadingProgressStore {
            override fun read(document: ServerReadingDocument) = DeviceReadingProgress()
            override fun read(target: ServerReadingTarget) = DeviceReadingProgress()
            override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = error("unexpected write")
            override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) = error("unexpected write")
            override fun pending(accountKey: String) = emptyList<ServerReadingTarget>()
        }
        assertThrows(UnsupportedOperationException::class.java) { legacy.observeAliases(target) }
        val directory = temporary.newFolder()
        val unsupportedIo = object : ReadingProgressJournalIo {
            override fun exists(file: File) = false
            override fun read(file: File) = error("unexpected read")
            override fun write(file: File, bytes: ByteArray) = error("unexpected write")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            FileServerReadingProgressStore(directory, unsupportedIo).observeAliases(target)
        }
        save(directory, target, record())
        val inventoryOnlyIo = object : ReadingProgressJournalIo {
            override fun inventory(directory: File) = checkNotNull(directory.listFiles()).toList()
            override fun exists(file: File) = error("unexpected legacy exists")
            override fun read(file: File) = error("unexpected legacy read")
            override fun write(file: File, bytes: ByteArray) = error("unexpected write")
        }
        assertThrows(UnsupportedOperationException::class.java) {
            FileServerReadingProgressStore(directory, inventoryOnlyIo).observeAliases(target)
        }
        val io = FileIo(); io.beforeInventory = { throw IOException("indeterminate inventory") }
        assertThrows(IOException::class.java) { FileServerReadingProgressStore(directory, io).observeAliases(target) }
        assertEquals(0, io.reads); assertEquals(0, io.writes)
    }

    @Test fun existingNondirectoryAndJournalNamedDirectoriesAreNotReportedAsAbsence() {
        val path = temporary.newFile("not-directory")
        assertThrows(Exception::class.java) { FileServerReadingProgressStore(path, FileIo()).observeAliases(target) }
        val directory = temporary.newFolder(); check(journal(directory).mkdir())
        assertThrows(Exception::class.java) { FileServerReadingProgressStore(directory, FileIo()).observeAliases(target) }
        assertTrue(journal(directory).isDirectory)
    }

    @Test fun unsupportedRootOrNestedFieldsRejectTheEntireObservationWithoutChangingBytes() {
        val corruptions: List<(JSONObject) -> Unit> = listOf(
            { it.put("future", true) }, { it.remove("remote") }, { it.put("format", 2) },
            { it.getJSONObject("remote").put("future", "keep") },
            { it.getJSONObject("pending").put("future", "keep") },
            { it.getJSONObject("queuedAnchor").put("page", 1) },
            { it.getJSONObject("conflict").getJSONObject("anchor").put("extra", true) },
            { it.put("remote", "not-an-object") }, { it.put("retryAfterUntil", true) },
            { it.getJSONObject("remote").getJSONObject("anchor").put("paragraphId", " ") },
        )
        for (change in corruptions) {
            val directory = temporary.newFolder(); val io = FileIo()
            val json = encodeDeviceReadingProgress(target, record()).also(change)
            journal(directory).writeText(json.toString()); assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun rawDecimalFieldsCannotBeCoercedIntoIntegersByValidationProjection() {
        val plain = encodeDeviceReadingProgress(target, record()).toString()
        for ((before, after) in listOf("\"format\":1" to "\"format\":1.0",
            "\"version\":3" to "\"version\":3.0", "\"expectedVersion\":3" to "\"expectedVersion\":3.0",
            "\"characterOffset\":7" to "\"characterOffset\":7.0", "\"retryAfterUntil\":456789" to "\"retryAfterUntil\":456789.0")) {
            val directory = temporary.newFolder(); val io = FileIo()
            val raw = plain.replace(before, after); assertNotEquals(plain, raw)
            journal(directory).writeText(raw); assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun duplicateOrEscapedEquivalentKeysCannotHideAnEarlierPendingIntent() {
        val plain = encodeDeviceReadingProgress(target, record()).toString()
        val bodies = listOf(plain.dropLast(1) + ",\"pending\":null}",
            plain.dropLast(1) + ",\"pend\\u0069ng\":null}",
            plain.replaceFirst("\"kind\":\"ORIGINAL\"", "\"kind\":\"ORIGINAL\",\"kind\":\"ORIGINAL\""))
        for (raw in bodies) {
            val directory = temporary.newFolder(); val io = FileIo()
            journal(directory).writeText(raw); assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun lenientGrammarTrailingTokensDepthAndExponentNumbersAreRefusedAsRawInput() {
        val plain = encodeDeviceReadingProgress(target, record()).toString()
        val bodies = listOf(plain.replaceFirst("\"format\"", "format"),
            plain.replaceFirst("\"format\"", "'format'"), "/*comment*/" + plain,
            plain.dropLast(1) + ",}", plain + "{}", plain + " trailing",
            plain.replaceFirst("\"format\":1", "\"format\":1e0"),
            plain.replaceFirst("\"version\":3", "\"version\":3e0"),
            "{\"deep\":".repeat(300) + "null" + "}".repeat(300))
        for (raw in bodies) {
            val directory = temporary.newFolder(); val io = FileIo()
            journal(directory).writeText(raw); assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun corruptOversizedInvalidUtf8OrUnrelatedCorruptRowsCannotYieldPartialResults() {
        for (bad in listOf("not JSON".toByteArray(), ByteArray(65_537) { ' '.code.toByte() }, byteArrayOf(0xc3.toByte(), 0x28))) {
            val directory = temporary.newFolder(); val io = FileIo()
            save(directory, target, record())
            val unrelated = target.copy(accountKey = "b".repeat(64))
            journal(directory, unrelated).writeBytes(bad)
            assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun wrongHashKeyScopeAndNoncanonicalUuidSpellingsAreNotIgnored() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("key", target.copy(recordId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc").key) },
            { it.put("key", "${target.accountKey}:ORIGINAL:1-1-1-1-1") },
            { it.put("key", "${target.accountKey.uppercase()}:ORIGINAL:${target.recordId}") },
            { it.getJSONObject("remote").put("kind", "TRANSLATION") },
            { it.getJSONObject("remote").put("recordId", "cccccccc-cccc-4ccc-8ccc-cccccccccccc") },
        )
        for (mutate in mutations) {
            val directory = temporary.newFolder(); val io = FileIo()
            journal(directory).writeText(encodeDeviceReadingProgress(target, record()).also(mutate).toString())
            assertRejectedUnchanged(directory, io)
        }
        val directory = temporary.newFolder(); val io = FileIo()
        File(directory, "f".repeat(64) + ".json").writeText(encodeDeviceReadingProgress(target, record()).toString())
        assertRejectedUnchanged(directory, io)
    }

    @Test fun suspiciousFilenameCasingAndStagingFilesAreRefusedWithoutCleanup() {
        val name = journal(temporary.root).name
        for (filename in listOf("bad.json", name.uppercase(), name.removeSuffix(".json") + ".JSON",
            "$name.BAK", "$name.new", "$name.NEW", "$name.old", "$name.bak.new", "bad.JSON.bak", "bad.json.new")) {
            val directory = temporary.newFolder(); val io = FileIo()
            File(directory, filename).writeText(encodeDeviceReadingProgress(target, record()).toString())
            assertRejectedUnchanged(directory, io)
        }
    }

    @Test fun correctlyHashedLegacyKeysStillRequireFullUuidShapeAndValidAccountAndKind() {
        val canonicalShort = target.copy(recordId = "00000001-0001-0001-0001-000000000001")
        val short = canonicalShort.copy(recordId = "1-1-1-1-1")
        val directory = temporary.newFolder(); val io = FileIo()
        save(directory, short, record(short))
        val before = snapshot(directory)
        assertThrows(Exception::class.java) {
            FileServerReadingProgressStore(directory, io).observeAliases(canonicalShort)
        }
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
        for (bad in listOf(target.copy(accountKey = target.accountKey.uppercase()), target.copy(kind = "original"))) {
            val other = temporary.newFolder(); val otherIo = FileIo()
            save(other, bad, record(bad))
            assertRejectedUnchanged(other, otherIo)
        }
    }

    @Test fun returnedListsAndValuesDoNotBorrowLaterCallerOrStoreMutations() {
        val directory = temporary.newFolder(); val io = FileIo(); val value = record()
        save(directory, target, value); val store = FileServerReadingProgressStore(directory, io)
        val first = store.observeAliases(target); val old = first.single()
        runCatching { (first as MutableList<ReadingProgressJournalObservation>).clear() }
        val second = store.observeAliases(target)
        assertEquals(value, second.single().record); assertEquals(0, io.writes)
        store.write(target, value.copy(retryAfterUntil = 1))
        assertEquals(value, old.record); assertEquals(1L, store.observeAliases(target).single().record.retryAfterUntil)
        assertEquals(1, io.writes)
    }

    @Test fun aWriterOwnsTheMonitorBeforeObservationInventoryAndRead() {
        val directory = temporary.newFolder(); val io = FileIo(); val value = record()
        val writer = FileServerReadingProgressStore(directory, io)
        val observer = FileServerReadingProgressStore(File(directory, "."), io)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = AtomicReference<Thread>(); val executor = Executors.newFixedThreadPool(2)
        io.beforeWrite = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        try {
            val writing = executor.submit { writer.write(target, value) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val reading = executor.submit<List<ReadingProgressJournalObservation>> { thread.set(Thread.currentThread()); observer.observeAliases(target) }
            awaitBlocked(thread); assertFalse(reading.isDone)
            assertEquals(0, io.inventories); assertEquals(0, io.reads)
            release.countDown(); writing.get(5, TimeUnit.SECONDS)
            assertEquals(value, reading.get(5, TimeUnit.SECONDS).single().record)
            assertEquals(1, io.writes)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun observationIoKeepsTheMonitorUntilAllRowsHaveBeenRead() {
        val directory = temporary.newFolder(); val io = FileIo(); val value = record()
        val upper = target.copy(recordId = target.recordId.uppercase())
        save(directory, target, value); save(directory, upper, record(upper, 8))
        val observer = FileServerReadingProgressStore(directory, io)
        val writer = FileServerReadingProgressStore(File(directory, "."), io)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val thread = AtomicReference<Thread>(); val executor = Executors.newFixedThreadPool(2)
        io.beforeRead = { if (io.reads == 0) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        try {
            val reading = executor.submit<List<ReadingProgressJournalObservation>> { observer.observeAliases(target) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val writing = executor.submit { thread.set(Thread.currentThread()); writer.write(target, value.copy(retryAfterUntil = 1)) }
            awaitBlocked(thread); assertFalse(writing.isDone); assertEquals(0, io.writes)
            release.countDown()
            val rows = reading.get(5, TimeUnit.SECONDS)
            assertEquals(2, rows.size); assertEquals(value, rows.single { it.target == target }.record)
            writing.get(5, TimeUnit.SECONDS); assertEquals(1, io.writes)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun failedLaterRowOrInventoryReleasesTheMonitorAndNeverReturnsTheEarlierSubset() {
        val directory = temporary.newFolder(); val io = FileIo()
        val upper = target.copy(recordId = target.recordId.uppercase())
        save(directory, target, record()); save(directory, upper, record(upper, 9))
        val store = FileServerReadingProgressStore(directory, io); val before = snapshot(directory)
        io.beforeRead = { if (io.reads == 1) throw IOException("later row failed") }
        assertThrows(IOException::class.java) { store.observeAliases(target) }
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
        io.beforeRead = {}; io.beforeInventory = { throw IOException("inventory failed") }
        assertThrows(IOException::class.java) { store.observeAliases(target) }
        io.beforeInventory = {}
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertEquals(2, executor.submit<List<ReadingProgressJournalObservation>> { store.observeAliases(target) }.get(5, TimeUnit.SECONDS).size)
            assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private fun journal(directory: File, scope: ServerReadingTarget = target) =
        File(directory, StableContentHash.sha256(scope.key) + ".json")
    private fun save(directory: File, scope: ServerReadingTarget, value: DeviceReadingProgress, backup: Boolean = false) {
        val base = journal(directory, scope)
        (if (backup) File(base.path + ".bak") else base).writeText(encodeDeviceReadingProgress(scope, value).toString())
    }
    private fun snapshot(directory: File): Map<String, List<Byte>> = directory.listFiles().orEmpty()
        .associate { it.name to it.readBytes().toList() }
    private fun assertRejectedUnchanged(directory: File, io: FileIo) {
        val before = snapshot(directory)
        assertThrows(Exception::class.java) { FileServerReadingProgressStore(directory, io).observeAliases(target) }
        assertEquals(before, snapshot(directory)); assertEquals(0, io.writes)
    }
    private fun awaitBlocked(thread: AtomicReference<Thread>) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.get()?.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.sleep(1)
        assertEquals("Contender did not wait on the production directory monitor", Thread.State.BLOCKED, thread.get()?.state)
    }
    private class FileIo : ReadingProgressJournalIo {
        @Volatile var beforeInventory: () -> Unit = {}
        @Volatile var beforeRead: () -> Unit = {}
        @Volatile var beforeObservationRead: (File) -> Unit = {}
        @Volatile var beforeWrite: () -> Unit = {}
        var inventoryTransform: (List<File>) -> List<File> = { it }
        var inventories = 0
        var reads = 0
        var legacyReads = 0
        var writes = 0
        val observedNames = mutableListOf<String>()
        override fun inventory(directory: File): List<File>? {
            beforeInventory(); inventories++
            if (!directory.exists()) return null
            check(directory.isDirectory)
            return inventoryTransform(checkNotNull(directory.listFiles()).toList())
        }
        override fun exists(file: File) = file.exists() || File(file.path + ".bak").exists()
        override fun read(file: File): ByteArray {
            beforeRead(); reads++; legacyReads++
            return (if (file.exists()) file else File(file.path + ".bak")).readBytes()
        }
        override fun readObservation(file: File): ByteArray {
            beforeRead(); beforeObservationRead(file); reads++; observedNames += file.name
            return file.readBytes()
        }
        override fun write(file: File, bytes: ByteArray) { beforeWrite(); file.writeBytes(bytes); writes++ }
    }
}
