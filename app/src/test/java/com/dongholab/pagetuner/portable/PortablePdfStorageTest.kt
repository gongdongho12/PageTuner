package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PortablePdfStorageTest {
    @get:Rule val temporary = TemporaryFolder()
    private val recordId = "14b55c4b-4f23-455b-ab6e-460a2a8614a2"
    private fun archive() = requireNotNull(javaClass.getResourceAsStream("/library-exchange-v1/portable-v1.zip")).use { it.readBytes() }

    private inner class Fixture(scope: CoroutineScope, dispatcher: CoroutineDispatcher) {
        val root = temporary.newFolder()
        val library = PortableLibraryStore(File(root, "library"))
        val entry = library.importArchive(archive()).entries.single { it.document.assets.any { a -> a.role == "pdf" } }
        val archiveFile get() = File(File(root, "library"), "${entry.packageId}.zip")
        val prepared get() = library.preparePdfContent(entry)
        val bindings = PortablePdfBindingStore(library, File(root, "bindings"))
        val uploads = mutableListOf<PdfContentUpload>()
        var reads = 0
        var opened = 0
        var onUpload: suspend () -> Unit = {}
        var onGet: suspend () -> Unit = {}
        var onPrepare: suspend () -> Unit = {}
        var onOpen: suspend () -> Unit = {}
        var remote = prepared
        val client = object : PdfContentClient {
            override suspend fun upload(request: PdfContentUpload): PdfContentReceipt {
                uploads += request; onUpload()
                return PdfContentReceipt(recordId, "2026-10-05T00:00:00Z", PdfContentValidation.validate(request).proof)
            }
            override suspend fun get(recordId: String): PdfContentRecord {
                reads++; onGet(); return PdfContentRecord(recordId, "2026-10-05T00:00:00Z", remote.content, remote.proof)
            }
            override fun close() {}
        }
        val account = PdfStorageConnection("a".repeat(64), "https://pdf.example", client)
        var current: PdfStorageConnection? = account
        val controller = PortablePdfStorage(scope, { onPrepare(); library.preparePdfContent(it) }, bindings,
            { requested, copy, guard ->
                assertEquals(entry.key, requested.key)
                assertEquals(prepared.proof, PdfContentDocuments.fromPackage(copy, requested.documentIndex).proof)
                onOpen(); guard(); opened++
            }, dispatcher)
        fun snapshot() = bindings.inspect(account.accountKey, account.origin, entry, prepared.proof)
        suspend fun select() { controller.select(entry, current) { current }; done(); assertTrue(controller.state.value.prepared) }
        suspend fun done() = controller.state.first { !it.busy }
        suspend fun check() { controller.updateRecord(recordId); controller.check { current }; done() }
        suspend fun bind() { check(); assertTrue(controller.state.value.checked); controller.bind { current }; done() }
    }

    @Test fun previewUploadCompareAndBindingAreSeparateAndVerifiedOpenUsesOnlyLocalArchive() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val original = f.archiveFile.readBytes()
        f.select(); assertEquals(0, f.reads); assertTrue(f.uploads.isEmpty()); assertNull(f.snapshot().binding)
        f.controller.upload { f.current }; f.done()
        assertEquals(1, f.uploads.size); assertEquals(recordId, f.controller.state.value.receiptId)
        assertNull(f.snapshot().binding); assertEquals(0, f.reads)
        f.check(); assertNull(f.snapshot().binding); assertEquals(1, f.reads)
        f.controller.bind { f.current }; f.controller.bind { f.current }; f.done()
        assertEquals(2, f.reads); assertEquals(PortablePdfBinding(recordId, f.prepared.proof), f.snapshot().binding)
        f.controller.bind { f.current }; runCurrent(); assertEquals(2, f.reads)
        f.controller.open { f.current }; f.done(); assertEquals(1, f.opened); assertEquals(3, f.reads)
        assertArrayEquals(original, f.archiveFile.readBytes())
        val exported = LibraryExchangeCodec.read(f.library.export(f.entry))
        assertFalse(exported.documents.single().extensionsJson.orEmpty().contains(recordId))
        assertFalse(File(f.root, "portable_server_bindings").exists())
    }

    @Test fun unknownUploadOutcomeRetriesSameIdAndExactContentOnlyOnUserAction() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select()
        f.onUpload = { throw PdfContentClientException(PdfContentClientFailure.NETWORK) }
        f.controller.upload { f.current }; f.done(); runCurrent()
        assertEquals(1, f.uploads.size); assertTrue(f.controller.state.value.attempted); assertNull(f.snapshot().binding)
        f.onUpload = {}; f.controller.upload { f.current }; f.done()
        assertEquals(2, f.uploads.size); assertEquals(f.uploads[0], f.uploads[1])
        f.controller.upload { f.current }; runCurrent(); assertEquals(2, f.uploads.size)
    }

    @Test fun callbacksFromPreviousPanelCannotUseNewlyPreparedOrCheckedSelection() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select()
        val oldSession = f.controller.state.value.session
        f.select(); f.check()
        assertNotEquals(oldSession, f.controller.state.value.session)
        f.controller.updateRecord(UUID.randomUUID().toString(), oldSession)
        f.controller.upload(oldSession) { f.current }
        f.controller.check(oldSession) { f.current }
        f.controller.bind(oldSession) { f.current }
        f.controller.close(oldSession)
        runCurrent()
        assertEquals(recordId, f.controller.state.value.recordId)
        assertTrue(f.controller.state.value.checked); assertEquals(1, f.reads)
        assertTrue(f.uploads.isEmpty()); assertNull(f.snapshot().binding)
        f.controller.bind { f.current }; f.done()
        f.controller.unbind(oldSession) { f.current }; f.controller.open(oldSession) { f.current }; runCurrent()
        assertEquals(recordId, f.snapshot().binding!!.recordId); assertEquals(0, f.opened)
    }

    @Test fun localChangesBeforeRetryOrFinalConfirmationNeverUploadDifferentContentOrBind() = runTest(timeout = 10.seconds) {
        for (retry in listOf(false, true)) {
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select()
            if (retry) {
                f.onUpload = { throw PdfContentClientException(PdfContentClientFailure.TIMEOUT) }
                f.controller.upload { f.current }; f.done()
            } else f.check()
            f.library.update(f.entry) { it.copy(language = "ja") }
            if (retry) f.controller.upload { f.current } else f.controller.bind { f.current }
            assertNotNull(f.done().error); assertNull(f.snapshot().binding)
            assertEquals(if (retry) 1 else 0, f.uploads.size)
        }
    }

    @Test fun accountAtoBtoAReplacementAndSelectionCloseDiscardLateUploadResponses() = runTest(timeout = 10.seconds) {
        repeat(4) { mode ->
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select()
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.onUpload = { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            f.controller.upload { f.current }; started.await()
            when (mode) {
                0 -> { val b = PdfStorageConnection("b".repeat(64), f.account.origin, f.client)
                    f.current = b; f.controller.connect(b); f.current = f.account; f.controller.connect(f.account) }
                1 -> { f.current = PdfStorageConnection(f.account.accountKey, f.account.origin, f.client); f.controller.connect(f.current) }
                2 -> f.controller.close()
                else -> { f.account.close(); f.current = null }
            }
            release.complete(Unit); runCurrent()
            assertNull(f.controller.state.value.receiptId); assertNull(f.snapshot().binding); assertEquals(1, f.uploads.size)
        }
    }

    @Test fun serverMismatchCorruptionAndMissingCopyRejectFinalBindAndVerifiedOpen() = runTest(timeout = 10.seconds) {
        repeat(3) { mode ->
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select(); f.check()
            when (mode) {
                0 -> f.remote = PdfContentValidation.validateContent(f.remote.content.copy(language = "ja"))
                1 -> f.remote = f.remote.copy(content = f.remote.content.copy(language = "ja"))
                else -> f.onGet = { throw PdfContentClientException(PdfContentClientFailure.NOT_FOUND) }
            }
            f.controller.bind { f.current }; assertNotNull(f.done().error); assertNull(f.snapshot().binding)
        }
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select(); f.bind()
        f.onGet = { throw PdfContentClientException(PdfContentClientFailure.UNAVAILABLE) }
        f.controller.open { f.current }; assertNotNull(f.done().error); assertEquals(0, f.opened)
        assertNotNull(f.snapshot().binding) // No implicit unlink or fallback on failure.
    }

    @Test fun readerWriteAwaitAndFinalStoreReadBothProtectCurrentBytes() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select(); f.check()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.onPrepare = { started.complete(Unit); release.await() }
        f.controller.bind { f.current }; started.await()
        f.library.update(f.entry) { it.copy(language = "fr") }
        release.complete(Unit); assertNotNull(f.done().error); assertNull(f.snapshot().binding)
    }

    @Test fun concurrentBindingAndUnbindRebindAbaPreventOldConfirmation() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select(); f.check()
        val other = PortablePdfBindingStore(PortableLibraryStore(File(f.root, "library")), File(f.root, "bindings"))
        val before = f.snapshot()
        val bound = other.commit(f.account.accountKey, f.account.origin, f.entry, f.prepared.proof, before, recordId) {}
        val unbound = other.commit(f.account.accountKey, f.account.origin, f.entry, f.prepared.proof, bound, null) {}
        assertNull(unbound.binding); assertNotEquals(before.nonce, unbound.nonce)
        f.controller.bind { f.current }; assertNotNull(f.done().error)
        assertEquals(unbound, f.snapshot())
    }

    @Test fun closingOrChangingBindingWhileDecoderRunsCannotOpenAndNeverGrantsTextAuthorization() = runTest(timeout = 10.seconds) {
        repeat(3) { mode ->
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); f.select(); f.bind()
            f.onOpen = {
                when (mode) {
                    0 -> f.controller.close()
                    1 -> f.bindings.commit(f.account.accountKey, f.account.origin, f.entry, f.prepared.proof, f.snapshot(), null) {}
                    else -> f.library.update(f.entry) { it.copy(language = "zh") }
                }
            }
            f.controller.open { f.current }; f.done(); runCurrent(); assertEquals(0, f.opened)
        }
    }

    @Test fun durableBindingIsAccountOriginAndLocalKeyScopedAndNotExported() {
        val library = PortableLibraryStore(temporary.newFolder())
        val entries = library.importArchive(archive()).entries
        val entry = entries.single { it.document.assets.any { a -> a.role == "pdf" } }
        val proof = library.preparePdfContent(entry).proof
        val directory = temporary.newFolder(); val bindings = PortablePdfBindingStore(library, directory)
        val absent = bindings.inspect("a".repeat(64), "https://one.example", entry, proof)
        val saved = bindings.commit("a".repeat(64), "https://one.example", entry, proof, absent, recordId) {}
        assertEquals(saved, PortablePdfBindingStore(library, directory).inspect("a".repeat(64), "https://one.example", entry, proof))
        assertNull(bindings.inspect("b".repeat(64), "https://one.example", entry, proof).binding)
        assertNull(bindings.inspect("a".repeat(64), "https://two.example", entry, proof).binding)
        assertFalse(String(library.export(entry), Charsets.ISO_8859_1).contains(recordId))
        directory.listFiles()!!.single().writeText("{corrupt}")
        assertThrows(Exception::class.java) { bindings.inspect("a".repeat(64), "https://one.example", entry, proof) }
    }

    @Test fun finalGuardFailureAndStaleProofLeaveBindingAndArchiveUntouched() {
        val libraryDirectory = temporary.newFolder()
        val library = PortableLibraryStore(libraryDirectory); val entry = library.importArchive(archive()).entries.single { it.document.assets.any { a -> a.role == "pdf" } }
        val archiveFile = File(libraryDirectory, "${entry.packageId}.zip")
        val directory = temporary.newFolder(); val bindings = PortablePdfBindingStore(library, directory)
        val proof = library.preparePdfContent(entry).proof; val account = "a".repeat(64); val origin = "https://one.example"
        val absent = bindings.inspect(account, origin, entry, proof); var checks = 0
        assertThrows(CancellationException::class.java) {
            bindings.commit(account, origin, entry, proof, absent, recordId) { if (++checks == 2) throw CancellationException() }
        }
        assertEquals(absent, bindings.inspect(account, origin, entry, proof)); assertTrue(directory.listFiles().orEmpty().isEmpty())
        library.update(entry) { it.copy(language = "ja") }; val bytes = archiveFile.readBytes()
        assertThrows(IllegalArgumentException::class.java) { bindings.commit(account, origin, entry, proof, absent, recordId) {} }
        assertArrayEquals(bytes, archiveFile.readBytes())
    }

    @Test fun binaryMetadataRejectsTruncationUnknownVersionTrailingInvalidUtf8AndOversizedFrames() {
        val library = PortableLibraryStore(temporary.newFolder()); val entry = library.importArchive(archive()).entries.single { it.document.assets.any { a -> a.role == "pdf" } }
        val directory = temporary.newFolder(); val bindings = PortablePdfBindingStore(library, directory)
        val proof = library.preparePdfContent(entry).proof; val account = "a".repeat(64); val origin = "https://one.example"
        bindings.commit(account, origin, entry, proof, bindings.inspect(account, origin, entry, proof), recordId) {}
        val file = directory.listFiles()!!.single(); val original = file.readBytes()
        val invalid = listOf(original.copyOf(original.size - 1), original + byteArrayOf(0),
            original.copyOf().apply { this[7] = 2 },
            original.copyOf().apply { this[12] = 0xff.toByte() },
            original.copyOf().apply { this[8] = 0x7f; this[9] = 0x7f })
        invalid.forEach { bytes -> file.writeBytes(bytes); assertThrows(Exception::class.java) { bindings.inspect(account, origin, entry, proof) } }
        file.writeBytes(original); assertEquals(recordId, bindings.inspect(account, origin, entry, proof).binding!!.recordId)
    }

    @Test fun unsupportedLargePdfLeavesZipUntouchedAndDoesNotMakeRequests() = runTest(timeout = 10.seconds) {
        val packageValue = LibraryExchangeCodec.read(archive())
        val document = packageValue.documents.single { it.assets.any { a -> a.role == "pdf" } }
        val huge = ByteArray(PdfContentValidation.MAX_PAYLOAD_BYTES + 1); "%PDF-".toByteArray().copyInto(huge)
        val asset = ExchangeAsset(huge, "application/pdf")
        val oversizedDocument = document.copy(assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
        val input = LibraryExchangeCodec.write(packageValue.copy(documents = listOf(oversizedDocument), assets = listOf(asset)))
        val libraryDirectory = temporary.newFolder()
        val library = PortableLibraryStore(libraryDirectory); val entry = library.importArchive(input).entries.single()
        val archiveFile = File(libraryDirectory, "${entry.packageId}.zip")
        val before = archiveFile.readBytes()
        val bindings = PortablePdfBindingStore(library, temporary.newFolder())
        val client = object : PdfContentClient {
            override suspend fun upload(request: PdfContentUpload): PdfContentReceipt = error("No upload")
            override suspend fun get(recordId: String): PdfContentRecord = error("No query")
            override fun close() {}
        }
        val connection = PdfStorageConnection("a".repeat(64), "https://one.example", client)
        val controller = PortablePdfStorage(backgroundScope, { library.preparePdfContent(it) }, bindings, { _, _, _ -> error("No open") }, StandardTestDispatcher(testScheduler))
        controller.select(entry, connection) { connection }
        val result = controller.state.first { !it.busy }
        assertFalse(result.prepared); assertNotNull(result.error); assertArrayEquals(before, archiveFile.readBytes())
    }
}
