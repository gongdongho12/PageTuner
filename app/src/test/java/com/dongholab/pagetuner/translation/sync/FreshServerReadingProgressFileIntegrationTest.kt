package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production observer + HTTP decoder with JVM raw file IO and injected transport, not Android or sockets. */
class FreshServerReadingProgressFileIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val anchor = ServerReadingAnchor(" p:é e\u0301 🌏 ", 3)
    private val timestamp = "2026-11-02T00:00:00Z"
    private fun remote(target: ServerReadingTarget = this.target, version: Long = 6) =
        ServerReadingProgress(target.kind, target.recordId, version, anchor, timestamp)
    private fun file(directory: File, target: ServerReadingTarget = this.target) =
        File(directory, StableContentHash.sha256(target.key) + ".json")
    private fun seed(directory: File, target: ServerReadingTarget, value: DeviceReadingProgress, backup: Boolean = false) =
        File(file(directory, target).path + if (backup) ".bak" else "").also {
            it.writeText(encodeDeviceReadingProgress(target, value).toString(), Charsets.UTF_8)
        }
    private fun bytes(directory: File) = directory.listFiles()!!.associate { it.name to it.readBytes().toList() }
    private fun connection(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        ServerReadingConnection(target.accountKey, HttpTranslationStore("https://reader.example",
            TranslationStoreBasicAuth("reader", "fixture-only"), TranslationStoreHttpTransport(handler)))

    @Test fun bothRecordKindsObserveRawAliasesAndOnlyGetWithoutChangingJournals() = runBlocking {
        for (kind in listOf("ORIGINAL", "TRANSLATION")) {
            val scope = target.copy(kind = kind); val upper = scope.copy(recordId = scope.recordId.uppercase())
            val directory = temporary.newFolder(); val io = RawIo()
            seed(directory, scope, DeviceReadingProgress(remote = remote(scope, 2)))
            seed(directory, upper, DeviceReadingProgress(remote = remote(upper, 6)))
            val original = bytes(directory); val calls = mutableListOf<TranslationStoreHttpRequest>()
            val expected = remote(scope, 6)
            val connection = connection { request ->
                calls += request
                assertEquals("GET", request.method)
                assertTrue(request.url.endsWith("/reading-progress/$kind/${scope.recordId}"))
                TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(expected).toString())
            }
            val store = FileServerReadingProgressStore(directory, io)
            val reader = FreshServerReadingProgressReader(store, scope, connection, current = {})
            val value = reader.read()
            assertEquals(expected, value)
            reader.checkReady(value)
            withContext(Dispatchers.IO) { reader.checkBeforeWrite(value) }
            assertEquals(listOf("GET"), calls.map { it.method })
            assertEquals(original, bytes(directory)); assertTrue(io.observationReads > 0)
        }
    }

    @Test fun backupPendingObservationSurvivesRejectionAndLaterFixtureDeletion() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo()
        seed(directory, target, DeviceReadingProgress(remote = remote(version = 1)))
        val backup = seed(directory, target, DeviceReadingProgress(remote = remote(version = 8),
            pending = ServerReadingMutation(8, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor)), backup = true)
        val original = bytes(directory); var gets = 0
        val connection = connection { request ->
            gets++; assertEquals("GET", request.method)
            TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote(version = 7)).toString())
        }
        val reader = FreshServerReadingProgressReader(FileServerReadingProgressStore(directory, io), target, connection, current = {})
        val pending = assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.read() } }
        assertEquals(FreshReadingProgressFailure.PENDING, pending.failure)
        assertEquals(0, gets); assertEquals(original, bytes(directory))
        // A test-fixture change represents explicit resolution outside this readonly reader.
        assertTrue(backup.delete())
        val lower = assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.read() } }
        assertEquals(FreshReadingProgressFailure.INVALID_RESPONSE, lower.failure)
        assertEquals(1, gets); assertEquals(original.getValue(file(directory).name), file(directory).readBytes().toList())
    }

    @Test fun aHigherRealAliasRefusesAnEarlierPreparedViewAtBothReadinessBoundaries() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); var gets = 0
        seed(directory, target, DeviceReadingProgress(remote = remote(version = 3)))
        val connection = connection { request -> gets++; assertEquals("GET", request.method)
            TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote(version = 3)).toString()) }
        val reader = FreshServerReadingProgressReader(FileServerReadingProgressStore(directory, io), target, connection, current = {})
        val value = reader.read()
        val upper = target.copy(recordId = target.recordId.uppercase())
        seed(directory, upper, DeviceReadingProgress(remote = remote(upper, 4)))
        val afterFixture = bytes(directory)
        assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.checkReady(value) } }
        withContext(Dispatchers.IO) {
            assertThrows(FreshReadingProgressException::class.java) { reader.checkBeforeWrite(value) }
        }
        assertEquals(1, gets); assertEquals(afterFixture, bytes(directory))
    }

    @Test fun duplicatePendingRawFilePoisonsTheReaderBeforeAnyGetOrRecovery() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); var gets = 0
        val base = seed(directory, target, DeviceReadingProgress(remote = remote(),
            pending = ServerReadingMutation(6, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor)))
        val valid = base.readText(Charsets.UTF_8)
        base.writeText(valid.dropLast(1) + ",\"pending\":null}", Charsets.UTF_8)
        val original = bytes(directory)
        val connection = connection { gets++; TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote()).toString()) }
        val reader = FreshServerReadingProgressReader(FileServerReadingProgressStore(directory, io), target, connection, current = {})
        val failure = assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.read() } }
        assertEquals(FreshReadingProgressFailure.STORAGE, failure.failure)
        assertEquals(original, bytes(directory)); assertEquals(0, gets)
        base.writeText(valid, Charsets.UTF_8)
        assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.read() } }
        assertEquals(0, gets)
    }

    @Test fun injectedHttpAwaitDoesNotKeepTheProductionDirectoryMonitor() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo()
        seed(directory, target, DeviceReadingProgress(remote = remote()))
        val store = FileServerReadingProgressStore(directory, io)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val connection = connection { request ->
                assertEquals("GET", request.method)
                assertEquals(1, executor.submit<List<ReadingProgressJournalObservation>> { store.observeAliases(target) }.get(5, TimeUnit.SECONDS).size)
                TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote()).toString())
            }
            assertEquals(remote(), FreshServerReadingProgressReader(store, target, connection, current = {}).read())
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun productionInvalidResponseIsTerminalEvenIfLaterTransportWouldBeValid() = runBlocking {
        val directory = temporary.newFolder(); val io = RawIo(); var invalid = true; var gets = 0
        val connection = connection { request -> gets++; assertEquals("GET", request.method)
            TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote()).also {
                if (invalid) it.put("unrecognized", true)
            }.toString()) }
        val reader = FreshServerReadingProgressReader(FileServerReadingProgressStore(directory, io), target, connection, current = {})
        val failure = assertThrows(TranslationStoreException::class.java) { runBlocking { reader.read() } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, failure.failure)
        invalid = false
        assertThrows(FreshReadingProgressException::class.java) { runBlocking { reader.read() } }
        assertEquals(1, gets); assertTrue(directory.listFiles()!!.isEmpty())
    }

    private class RawIo : ReadingProgressJournalIo {
        var observationReads = 0
        override fun inventory(directory: File): List<File>? = if (!directory.exists()) null else directory.listFiles()!!.toList()
        override fun readObservation(file: File): ByteArray { observationReads++; return file.readBytes() }
        override fun exists(file: File): Boolean = error("Legacy existence lookup must not be used")
        override fun read(file: File): ByteArray = error("Legacy read/recovery must not be used")
        override fun write(file: File, bytes: ByteArray): Unit = error("Reader must not write")
    }
}
