package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual JVM journal files/production observer/decoder, injected IO and transport; not AtomicFile or sockets. */
class ServerReadingProgressAdoptionFileIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val anchor = ServerReadingAnchor(" p:é e\u0301 🌏 ", 3)
    private fun remote(target: ServerReadingTarget = this.target, version: Long = 0) = ServerReadingProgress(
        target.kind, target.recordId, version, if (version == 0L) null else anchor,
        if (version == 0L) null else "2026-11-06T00:00:00Z")
    private fun file(directory: File, target: ServerReadingTarget = this.target) =
        File(directory, StableContentHash.sha256(target.key) + ".json")
    private fun seed(directory: File, scope: ServerReadingTarget, value: DeviceReadingProgress, backup: Boolean = false): File {
        check(directory.isDirectory || directory.mkdirs())
        return File(file(directory, scope).path + if (backup) ".bak" else "").also {
            it.writeText(encodeDeviceReadingProgress(scope, value).toString(), Charsets.UTF_8)
        }
    }
    private fun bytes(directory: File) = directory.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
    private fun connection(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) = ServerReadingConnection(
        target.accountKey, HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "fixture-only"),
            TranslationStoreHttpTransport(handler)))
    private fun response(value: ServerReadingProgress) = TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(value).toString())

    @Test fun absentFilesCompareWithoutWritingThenCommitExactAnchorForBothKinds() = runBlocking {
        for (kind in listOf("ORIGINAL", "TRANSLATION")) {
            val scope = target.copy(kind = kind); val directory = File(temporary.newFolder(), "journals")
            val io = RawIo(); val store = FileServerReadingProgressStore(directory, io)
            val methods = mutableListOf<String>()
            val account = connection { request ->
                methods += request.method
                assertTrue(request.url.endsWith("/reading-progress/$kind/${scope.recordId}"))
                response(remote(scope))
            }
            val actor = ServerReadingProgressAdoption(store, scope, account, {}, {})
            val shown = actor.compare()
            assertNull(shown.record.canonical); assertFalse(directory.exists()); assertEquals(0, io.writes)
            assertTrue(actor.adopt(shown, anchor))
            val journal = store.captureSnapshot(scope).canonical!!
            assertEquals(remote(scope), journal.remote); assertEquals(anchor, journal.pending!!.anchor)
            assertEquals(0L, journal.pending.expectedVersion); assertEquals(1, io.writes)
            assertEquals(listOf("GET", "GET"), methods)
            assertEquals(journal, FileServerReadingProgressStore(File(directory, "."), RawIo()).captureSnapshot(scope).canonical)
        }
    }

    @Test fun cleanBackupIsNotRecoveredAndFinalGuardCloseDoesNotOverwriteIt() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); val store = FileServerReadingProgressStore(directory, io)
        val original = DeviceReadingProgress(remote(version = 3), retryAfterUntil = 120_000)
        seed(directory, target, original, backup = true); val before = bytes(directory)
        val actor = ServerReadingProgressAdoption(store, target, connection { response(remote(version = 3)) }, {}, {})
        val shown = actor.compare()
        assertEquals(before, bytes(directory)); assertFalse(file(directory).exists())
        io.beforeFinalGuard = actor::close
        assertTrue(runCatching { actor.adopt(shown, anchor) }.isFailure)
        assertEquals(before, bytes(directory)); assertEquals(original, store.captureSnapshot(target).canonical)
        assertEquals(0, io.writes)
    }

    @Test fun rawAliasDirtyScopeIsPreservedAndNeverQueriesOrCreatesCanonical() = runBlocking {
        val directory = temporary.newFolder(); val upper = target.copy(recordId = target.recordId.uppercase())
        val value = DeviceReadingProgress(remote(upper, 8), ServerReadingMutation(8,
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor), anchor, remote(upper, 9), 120_000)
        seed(directory, upper, value); val before = bytes(directory); var gets = 0
        val actor = ServerReadingProgressAdoption(FileServerReadingProgressStore(directory, RawIo()), target,
            connection { gets++; response(remote(version = 10)) }, {}, {})
        assertTrue(runCatching { actor.compare() }.isFailure)
        assertEquals(0, gets); assertEquals(before, bytes(directory)); assertFalse(file(directory).exists())
    }

    @Test fun journalAdvanceDuringGetRejectsOlderServerValueWithoutTouchingFiles() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); var gets = 0
        seed(directory, target, DeviceReadingProgress(remote(version = 1)))
        val higher = DeviceReadingProgress(remote(version = 9), retryAfterUntil = 120_000)
        val actor = ServerReadingProgressAdoption(FileServerReadingProgressStore(directory, io), target,
            connection { gets++; seed(directory, target, higher); response(remote(version = 2)) }, {}, {})
        assertTrue(runCatching { actor.compare() }.isFailure)
        val afterFixture = bytes(directory)
        assertTrue(runCatching { actor.compare() }.isFailure)
        assertEquals(afterFixture, bytes(directory)); assertEquals(0, io.writes)
        assertEquals(1, gets)
    }

    @Test fun duplicateRawJsonIsTerminalEvenAfterFixtureRepair() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); var gets = 0
        val base = seed(directory, target, DeviceReadingProgress(remote(version = 1)))
        val valid = base.readText(Charsets.UTF_8)
        base.writeText(valid.dropLast(1) + ",\"pending\":null}", Charsets.UTF_8)
        val before = bytes(directory)
        val actor = ServerReadingProgressAdoption(FileServerReadingProgressStore(directory, io), target,
            connection { gets++; response(remote(version = 1)) }, {}, {})
        assertTrue(runCatching { actor.compare() }.isFailure); assertEquals(before, bytes(directory))
        base.writeText(valid, Charsets.UTF_8)
        assertTrue(runCatching { actor.compare() }.isFailure); assertEquals(0, gets); assertEquals(0, io.writes)
    }

    @Test fun httpAwaitDoesNotHoldTheProductionJournalMonitor() = runBlocking {
        val directory = temporary.newFolder(); val store = FileServerReadingProgressStore(directory, RawIo())
        seed(directory, target, DeviceReadingProgress(remote(version = 1)))
        val executor = Executors.newSingleThreadExecutor()
        try {
            val actor = ServerReadingProgressAdoption(store, target, connection { request ->
                assertEquals("GET", request.method)
                assertNotNull(executor.submit<ReadingProgressJournalSnapshot> { store.captureSnapshot(target) }.get(5, TimeUnit.SECONDS).canonical)
                response(remote(version = 1))
            }, {}, {})
            actor.compare()
            Unit
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    private class RawIo : ReadingProgressJournalIo {
        var writes = 0
        var beforeFinalGuard: () -> Unit = {}
        override fun inventory(directory: File): List<File>? = if (!directory.exists()) null else directory.listFiles()!!.toList()
        override fun readObservation(file: File) = file.readBytes()
        override fun exists(file: File): Boolean = error("No legacy existence fallback")
        override fun read(file: File): ByteArray = error("No legacy recovery fallback")
        override fun write(file: File, bytes: ByteArray): Unit = error("No unconditional write")
        override fun writeGuarded(file: File, bytes: ByteArray, guard: () -> Unit) {
            guard(); beforeFinalGuard(); guard()
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs())
            file.writeBytes(bytes); File(file.path + ".bak").let { if (it.exists()) check(it.delete()) }
            writes++
        }
    }
}
