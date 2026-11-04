package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real JVM ZIP/binding files and real lifetime tickets; injected journal/HTTP, not Android SAF or sockets. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortableAccountReadingProgressExportTest {
    @get:Rule val temporary = TemporaryFolder()

    private class Journal : ServerReadingProgressStore {
        var rows = emptyList<ReadingProgressJournalObservation>()
        var observations = 0
        var legacyCalls = 0
        var beforeObserve: () -> Unit = {}
        private fun forbidden(): Nothing { legacyCalls++; error("Legacy progress access forbidden") }
        override fun read(document: ServerReadingDocument): DeviceReadingProgress = forbidden()
        override fun write(document: ServerReadingDocument, value: DeviceReadingProgress): Unit = forbidden()
        override fun read(target: ServerReadingTarget): DeviceReadingProgress = forbidden()
        override fun write(target: ServerReadingTarget, value: DeviceReadingProgress): Unit = forbidden()
        override fun pending(accountKey: String): List<ServerReadingTarget> = forbidden()
        override fun observeAliases(target: ServerReadingTarget): List<ReadingProgressJournalObservation> {
            observations++; beforeObserve(); return rows.toList()
        }
    }

    private inner class Fixture(texts: List<String> = listOf("First 😀 paragraph", "Second paragraph", "Third paragraph"),
        transform: (ExchangeDocument) -> ExchangeDocument = { it }) {
        val account = NotesFixture.connection { error("Only injected verify and GET allowed") }
        val directory = temporary.newFolder()
        val library = PortableLibraryStore(directory)
        val bindingDirectory = temporary.newFolder()
        val bindings = PortableServerBindingStore(bindingDirectory)
        val access = PortableTextBindingAccess(library, bindings)
        val document = transform(PortableDocumentMapper.server(NotesFixture.document(texts = texts).source).documents.single().let {
            it.copy(position = ExchangeAnchor("p1", 0),
                notes = listOf(ExchangeNote("legacy-note", "note", "原  文", " 메모 é ", "First", ExchangeAnchor("p1", 0), NotesFixture.time)),
                organization = ExchangeOrganization(" Legacy ", listOf("é", "é", "Tag", "tag"), true),
                extensionsJson = JSONObject(it.extensionsJson!!).put("unknownSibling", JSONObject("""{"unicode":" é 中  文 ","values":[1,2]}""")).toString())
        })
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(document))
        val entry = library.importArchive(LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(document)))).entries.single()
        val saved = bindings.save(PortableServerBinding(account.accountKey, entry.key, NotesFixture.recordId, identity))
        val authority = PortableTextAccountAuthority(account)
        val lease = authority.operation(entry.key).apply { activate() }
        val ticket = lease.issue(account, entry.key)
        val target = ServerReadingTarget(account.accountKey, identity.kind.name, NotesFixture.recordId)
        val journal = Journal()
        var reads = 0
        var verifies = 0
        var gets = 0
        var remote = remote()
        var beforeRead: suspend () -> Unit = {}
        var beforeVerify: suspend () -> Unit = {}
        var beforeQuery: suspend () -> Unit = {}
        fun remote(version: Long = 3, paragraph: String = "p1", offset: Int = 8) = ServerReadingProgress(
            target.kind, target.recordId, version, ServerReadingAnchor(paragraph, offset).takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
        fun adapter(dispatcher: CoroutineDispatcher, scope: PortableTextAccountAuthority = authority,
            capturedTicket: PortableTextOperationTicket = ticket, selected: PortableLibraryEntry = entry) = PortableAccountReadingProgressExport(
            selected, scope, capturedTicket,
            currentDocument = { locator -> reads++; beforeRead(); library.read(locator).documents[locator.documentIndex] },
            bindings = access, storage = journal,
            verify = { connection, record, proof -> verifies++; beforeVerify(); assertSame(account.client, connection.client)
                assertEquals(target.recordId, record); assertEquals(identity, proof) },
            query = { connection, kind, record -> gets++; beforeQuery(); assertSame(account.client, connection.client)
                assertEquals(target.kind, kind); assertEquals(target.recordId, record); remote }, io = dispatcher)
        fun bytes() = File(directory, "${entry.packageId}.zip").readBytes()
    }

    private fun rejected(block: () -> Unit) = assertTrue(runCatching(block).isFailure)

    @Test fun latestExactAnchorPreservesCanonicalLegacyDataAndWritesNoJournalOrZip() = runTest {
        val f = Fixture(); val original = f.bytes(); val bindingBytes = f.bindingDirectory.listFiles()!!.single().readBytes()
        val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
        prepared.checkBeforeWrite()
        assertEquals(ReadingProgressSnapshot(identity = f.identity, presence = ReadingProgressSnapshotPresence.PRESENT,
            anchor = ReadingProgressSnapshotAnchor("p1", 8)), prepared.snapshot)
        assertEquals(prepared.snapshot, ReadingProgressSnapshotJson.fromDocument(prepared.document))
        assertEquals(f.document.position, prepared.document.position); assertEquals(f.document.notes, prepared.document.notes)
        assertEquals(f.document.organization, prepared.document.organization); assertEquals(f.document.paragraphs, prepared.document.paragraphs)
        assertEquals(f.identity, DocumentIdentityJson.fromDocument(prepared.document))
        assertEquals(JSONObject(f.document.extensionsJson!!).getJSONObject("unknownSibling").toString(),
            JSONObject(prepared.document.extensionsJson!!).getJSONObject("unknownSibling").toString())
        assertArrayEquals(original, f.bytes()); assertArrayEquals(bindingBytes, f.bindingDirectory.listFiles()!!.single().readBytes())
        assertEquals(1, f.verifies); assertEquals(1, f.gets); assertEquals(0, f.journal.legacyCalls); assertTrue(f.journal.rows.isEmpty())
    }

    @Test fun absentAndEndOrEmptyParagraphAnchorsRemainExactWithoutMovingLegacyPosition() = runTest {
        for ((version, paragraph, offset) in listOf(Triple(0L, "p1", 0), Triple(2L, "p1", "First 😀 paragraph".length), Triple(3L, "p2", 0))) {
            val f = Fixture(listOf("First 😀 paragraph", "", "Third")); f.remote = f.remote(version, paragraph, offset)
            val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare(); prepared.checkBeforeWrite()
            assertEquals(if (version == 0L) ReadingProgressSnapshotPresence.ABSENT else ReadingProgressSnapshotPresence.PRESENT, prepared.snapshot.presence)
            assertEquals(if (version == 0L) null else ReadingProgressSnapshotAnchor(paragraph, offset), prepared.snapshot.anchor)
            assertEquals(ExchangeAnchor("p1", 0), prepared.document.position)
        }
    }

    @Test fun unknownParagraphPastEndAndSplitSurrogateNeverProduceAnExport() = runTest {
        for ((paragraph, offset) in listOf("missing" to 0, "p1" to 100, "p1" to 7)) {
            val f = Fixture(); f.remote = f.remote(paragraph = paragraph, offset = offset)
            assertTrue(runCatching { f.adapter(StandardTestDispatcher(testScheduler)).prepare() }.isFailure)
            assertNull(ReadingProgressSnapshotJson.fromDocument(f.library.read(f.entry).documents.single()))
            assertEquals(0, f.journal.legacyCalls)
        }
    }

    @Test fun currentDocumentWaitsForReaderSaveAndCachedSelectionBodyIsIgnored() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var first = true
        f.beforeRead = { if (first) { first = false; entered.complete(Unit); release.await() } }
        val cached = f.entry.copy(document = f.entry.document.copy(bookTitle = "Stale cache"))
        val task = async { f.adapter(StandardTestDispatcher(testScheduler), selected = cached).prepare() }
        entered.await(); assertEquals(0, f.gets); assertEquals(0, f.verifies)
        f.library.update(f.entry) { it.copy(bookTitle = "Reader saved title", position = ExchangeAnchor("p2", 2)) }
        release.complete(Unit)
        val prepared = task.await(); assertEquals("Reader saved title", prepared.document.bookTitle)
        assertEquals(ExchangeAnchor("p2", 2), prepared.document.position); prepared.checkBeforeWrite()
    }

    @Test fun canonicalMetadataChangeAndDeletionAfterPrepareRejectFinalGuardWithoutBindingCleanup() = runTest {
        for (deleted in listOf(false, true)) {
            val f = Fixture(); val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
            val bindingBytes = f.bindingDirectory.listFiles()!!.single().readBytes()
            if (deleted) assertTrue(File(f.directory, "${f.entry.packageId}.zip").delete())
            else f.library.update(f.entry) { it.copy(chapterTitle = "Changed", position = ExchangeAnchor("p2", 1)) }
            rejected(prepared.checkBeforeWrite)
            assertArrayEquals(bindingBytes, f.bindingDirectory.listFiles()!!.single().readBytes())
            assertEquals(1, f.gets)
        }
    }

    @Test fun sameUuidUnlinkReconnectAfterPrepareChangesRawGenerationAndIsRejected() = runTest {
        val f = Fixture(); val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
        f.bindings.remove(f.account.accountKey, f.entry)
        val rebound = f.bindings.save(f.saved)
        assertEquals(f.saved.recordId, rebound.recordId); assertNotEquals(f.saved.nonce, rebound.nonce)
        val newBytes = f.bindingDirectory.listFiles()!!.single().readBytes()
        rejected(prepared.checkBeforeWrite)
        assertArrayEquals(newBytes, f.bindingDirectory.listFiles()!!.single().readBytes())
    }

    @Test fun metadataOrRawNonceChangeDuringHeldGetDiscardsLateResponse() = runTest {
        for (nonceChanged in listOf(false, true)) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeQuery = { entered.complete(Unit); release.await() }
            val task = async { runCatching { f.adapter(StandardTestDispatcher(testScheduler)).prepare() } }
            entered.await()
            if (nonceChanged) { f.bindings.remove(f.account.accountKey, f.entry); f.bindings.save(f.saved) }
            else f.library.update(f.entry) { it.copy(bookTitle = "Changed during GET") }
            release.complete(Unit); assertTrue(task.await().isFailure)
            assertEquals(1, f.gets); assertEquals(0, f.journal.legacyCalls)
        }
    }

    @Test fun authorityAndSelectionRetirementDuringHeldGetCannotBorrowNewSameAccountOwner() = runTest {
        for (accountClosed in listOf(false, true)) {
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.beforeQuery = { entered.complete(Unit); release.await() }
            val adapter = f.adapter(StandardTestDispatcher(testScheduler))
            val task = async { runCatching { adapter.prepare() } }; entered.await()
            if (accountClosed) f.authority.close() else f.lease.close()
            val replacement = PortableTextAccountAuthority(f.account)
            val replacementLease = replacement.operation(f.entry.key).apply { activate() }
            replacementLease.issue(f.account, f.entry.key).check()
            release.complete(Unit); assertTrue(task.await().isFailure)
            rejected { runBlocking { adapter.prepare() } }
            replacement.close()
        }
    }

    @Test fun mismatchedClientAuthorityAndTicketAreRejectedBeforeAnyRequest() = runTest {
        val f = Fixture(); val replacementClient = NotesFixture.connection { error("Wrong client must not request") }
        assertEquals(f.account.accountKey, replacementClient.accountKey); assertNotSame(f.account.client, replacementClient.client)
        val wrong = PortableTextAccountAuthority(replacementClient)
        assertTrue(runCatching { f.adapter(StandardTestDispatcher(testScheduler), scope = wrong).prepare() }.isFailure)
        assertEquals(0, f.reads); assertEquals(0, f.gets); assertEquals(0, f.verifies)
    }

    @Test fun twoActiveAuthoritiesWithTheSameConnectionCannotShareAnOperationTicket() = runTest {
        val f = Fixture(); val other = PortableTextAccountAuthority(f.account)
        other.operation(f.entry.key).apply { activate() }.issue(f.account, f.entry.key).check()
        assertTrue(runCatching { f.adapter(StandardTestDispatcher(testScheduler), scope = other).prepare() }.isFailure)
        assertEquals(0, f.verifies); assertEquals(0, f.gets)
        f.ticket.check() // Rejecting the pair must not revoke the independent valid owner.
    }
    @Test fun pendingQueuedAndConflictRejectWithoutFlushOrReplacingExistingRows() = runTest {
        for (mode in 0..2) {
            val f = Fixture(); val mutation = ServerReadingMutation(3, "22222222-2222-4222-8222-222222222222", ServerReadingAnchor("p1", 8))
            val record = DeviceReadingProgress(f.remote, mutation,
                queuedAnchor = ServerReadingAnchor("p2", 0).takeIf { mode == 1 },
                conflict = f.remote(4).takeIf { mode == 2 }, retryAfterUntil = 999999)
            f.journal.rows = listOf(ReadingProgressJournalObservation(f.target, record))
            assertTrue(runCatching { f.adapter(StandardTestDispatcher(testScheduler)).prepare() }.isFailure)
            assertEquals(record, f.journal.rows.single().record); assertEquals(0, f.gets); assertEquals(0, f.journal.legacyCalls)
        }
    }

    @Test fun laterHighJournalOrPendingRefusesPreparedBytesAtFinalGuard() = runTest {
        for (pending in listOf(false, true)) {
            val f = Fixture(); val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
            f.journal.rows = listOf(ReadingProgressJournalObservation(f.target, if (pending)
                DeviceReadingProgress(f.remote, ServerReadingMutation(3, "22222222-2222-4222-8222-222222222222", ServerReadingAnchor("p2", 0)))
                else DeviceReadingProgress(f.remote(4))))
            val saved = f.journal.rows.toList(); rejected(prepared.checkBeforeWrite)
            assertEquals(saved, f.journal.rows); assertEquals(0, f.journal.legacyCalls)
        }
    }

    @Test fun unsupportedOrCorruptOwnSnapshotIsNotOverwrittenAndMakesNoRequest() = runTest {
        for (json in listOf("""{"version":999}""", "null")) {
            val f = Fixture(transform = { source -> source.copy(extensionsJson = JSONObject(source.extensionsJson!!)
                .put("readingProgressSnapshot", if (json == "null") JSONObject.NULL else JSONObject(json)).toString()) })
            val original = f.bytes()
            assertTrue(runCatching { f.adapter(StandardTestDispatcher(testScheduler)).prepare() }.isFailure)
            assertEquals(0, f.gets); assertEquals(0, f.verifies); assertArrayEquals(original, f.bytes())
        }
    }

    @Test fun returnedDocumentListMutationDoesNotRewritePrivateCanonicalInput() = runTest {
        val f = Fixture(); val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
        (prepared.document.paragraphs as MutableList).clear()
        (prepared.document.organization.tags as MutableList).clear()
        // Output mutation cannot change what the retained final guard compares with the real ZIP.
        prepared.checkBeforeWrite()
        assertEquals(f.document.paragraphs, f.library.read(f.entry).documents.single().paragraphs)
        assertEquals(f.document.organization.tags, f.library.read(f.entry).documents.single().organization.tags)
    }

    @Test fun adapterCloseAndCoroutineCancellationExpirePreparedOrLateWork() = runTest {
        val f = Fixture(); val adapter = f.adapter(StandardTestDispatcher(testScheduler)); val prepared = adapter.prepare()
        adapter.close(); rejected(prepared.checkBeforeWrite)
        val other = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        other.beforeQuery = { withContext(NonCancellable) { entered.complete(Unit); release.await() } }
        val pending = other.adapter(StandardTestDispatcher(testScheduler)); val task = launch { pending.prepare() }
        entered.await(); task.cancel(); release.complete(Unit); task.join()
        rejected { runBlocking { pending.prepare() } }; assertEquals(0, other.journal.legacyCalls)
    }

    @Test fun defaultProductionPathOnlyVerifiesIdentityAndGetsProgress() = runTest {
        val f = Fixture(); val requests = mutableListOf<TranslationStoreHttpRequest>()
        val account = NotesFixture.connection { request ->
            requests += request
            if (request.url.endsWith("/library-identity/verify")) {
                assertEquals("POST", request.method)
                val input = JSONObject(request.body!!)
                TranslationStoreHttpResponse(200, body = JSONObject().put("kind", input.get("kind"))
                    .put("recordId", input.get("recordId")).put("verified", true).put("identity", input.get("identity")).toString())
            } else {
                assertEquals("GET", request.method); assertTrue(request.url.endsWith("/reading-progress/ORIGINAL/${NotesFixture.recordId}"))
                TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(f.remote).toString())
            }
        }
        val authority = PortableTextAccountAuthority(account)
        val lease = authority.operation(f.entry.key).apply { activate() }; val ticket = lease.issue(account, f.entry.key)
        val adapter = PortableAccountReadingProgressExport(f.entry, authority, ticket,
            { selected -> f.library.read(selected).documents[selected.documentIndex] }, f.access, f.journal, io = StandardTestDispatcher(testScheduler))
        val prepared = adapter.prepare(); prepared.checkBeforeWrite()
        assertEquals(listOf("POST", "GET"), requests.map { it.method }); assertEquals(0, f.journal.legacyCalls)
    }

    @Test fun finalGuardKeepsBindingWriterBlockedUntilJournalInspectionReturns() = runTest {
        val f = Fixture(); val prepared = f.adapter(StandardTestDispatcher(testScheduler)).prepare()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val writerStarted = CountDownLatch(1)
        f.journal.beforeObserve = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val checking = pool.submit { prepared.checkBeforeWrite() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val writing = pool.submit { writerStarted.countDown(); f.bindings.remove(f.account.accountKey, f.entry) }
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS)); assertFalse(writing.isDone)
            release.countDown(); checking.get(5, TimeUnit.SECONDS); writing.get(5, TimeUnit.SECONDS)
            rejected(prepared.checkBeforeWrite)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
