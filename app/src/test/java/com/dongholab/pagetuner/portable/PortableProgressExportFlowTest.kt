package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.translation.glossary.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Production session/adapter/composer/tickets with JVM ZIP/binding and injected APIs, not Compose/SAF. */
class PortableProgressExportFlowTest {
    @get:Rule val temporary = TemporaryFolder()
    private inner class Fixture {
        val connection = NotesFixture.connection { error("No external HTTP") }
        val library = PortableLibraryStore(temporary.newFolder())
        val bindings = PortableServerBindingStore(temporary.newFolder())
        val access = PortableTextBindingAccess(library, bindings)
        val base = PortableDocumentMapper.server(NotesFixture.document().source).documents.single().let { source ->
            val id = requireNotNull(DocumentIdentityJson.fromDocument(source))
            BookGlossarySnapshotsJson.withSnapshots(source.copy(position = ExchangeAnchor("p1", 0),
                notes = listOf(ExchangeNote("legacy", "note", " 原 文 ", " メモ é ", "First", ExchangeAnchor("p1", 0), NotesFixture.time)),
                extensionsJson = JSONObject(source.extensionsJson!!).put("sibling", " 中  文 ").toString()),
                BookGlossarySnapshots(snapshots = listOf(BookGlossarySnapshot(BookGlossarySyncIdentity(id.contentProviderId, id.bookId, "ja"),
                    BookGlossarySnapshotPresence.ABSENT, null))))
        }
        val entry = library.importArchive(LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(base)))).entries.single()
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(base))
        val binding = bindings.save(PortableServerBinding(connection.accountKey, entry.key, NotesFixture.recordId, identity))
        val owner = PortableTextAccountAuthority(connection)
        val session = PortableAccountExportSession(owner, entry.key).apply { activate() }
        val grant = session.issue()
        val tickets = PortableExportTickets()
        var beforeQuery: suspend () -> Unit = {}
        var beforeObserve: () -> Unit = {}
        var gets = 0
        val store = object : ServerReadingProgressStore {
            override fun read(document: ServerReadingDocument): DeviceReadingProgress = error("No legacy read")
            override fun write(document: ServerReadingDocument, value: DeviceReadingProgress): Unit = error("No write")
            override fun read(target: ServerReadingTarget): DeviceReadingProgress = error("No legacy target read")
            override fun write(target: ServerReadingTarget, value: DeviceReadingProgress): Unit = error("No target write")
            override fun pending(accountKey: String): List<ServerReadingTarget> = error("No pending scan")
            override fun observeAliases(target: ServerReadingTarget): List<ReadingProgressJournalObservation> { beforeObserve(); return emptyList() }
        }
        suspend fun current(selected: PortableLibraryEntry) = library.read(selected).documents[selected.documentIndex]
        fun adapter() = PortableAccountReadingProgressExport(entry, owner, grant.ticket, ::current, access, store,
            verify = { _, _, proof -> assertEquals(identity, proof) }, query = { _, kind, record ->
                gets++; beforeQuery(); ServerReadingProgress(kind, record, 3, ServerReadingAnchor("p1", 8), NotesFixture.time)
            }, checkOperation = { grant.checkCurrent() })
    }

    @Test fun realCommittedGrantExportsCombined500EntriesAndExactProgressWithoutChangingLocalNotesOrPosition() = runBlocking<Unit> {
        val f = Fixture()
        val values = (0 until 500).map { BookGlossaryEntry("id:$it", " Source $it ", " 訳 $it ", " 表示 $it ",
            GlossaryTermKind.entries[it % 3], it % 2 == 0, it % 3 == 0) }
        val glossary = PortableAccountGlossaryExport(f::current, { f.library.read(it).documents[it.documentIndex] },
            { account, selected -> f.access.capture(account, selected).binding }, { DeviceBookGlossary() }, verify = { _, _, _ -> },
            query = { _, target -> ServerBookGlossary(target, 2, BookGlossaryValue(values), NotesFixture.time) })
            .prepare(f.entry, "ko", f.connection, f.grant::checkCurrent)
        val organization = PortableAccountOrganizationExport(f::current, { f.library.read(it).documents[it.documentIndex] },
            { account, selected -> f.access.capture(account, selected).binding }, { DeviceLibraryOrganization() }, verify = { _, _, _ -> },
            query = { _, kind, record -> ServerLibraryOrganization(kind, record, 2,
                LibraryOrganization("Folder 中", listOf("é", "é", "Tag", "tag"), true), NotesFixture.time) })
            .prepare(f.entry, f.connection, f.grant::checkCurrent)
        val progress = f.adapter().prepare()
        val prepared = combineAccountMetadataExports(glossary, organization, progress, f.grant::checkCurrent)
        val request = f.tickets.begin("combined.zip", f.connection)
        f.tickets.prepare(request, LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document))),
            f.connection, prepared.checkBeforeWrite)
        val output = ByteArrayOutputStream()
        withContext(Dispatchers.IO) { writePortableExport(f.tickets, request.id, { f.connection }) { output } }
        val result = LibraryExchangeCodec.read(output.toByteArray()).documents.single()
        assertEquals(progress.snapshot, ReadingProgressSnapshotJson.fromDocument(result))
        assertEquals(organization.snapshot, LibraryOrganizationSnapshotJson.fromDocument(result))
        val scopes = BookGlossarySnapshotsJson.fromDocument(result)!!.snapshots
        assertEquals("ja", scopes.first().identity.targetLanguage); assertEquals(values.map { it.syncEntry() }, scopes.last().entries)
        assertEquals(500, scopes.last().entries!!.size); assertEquals(f.base.position, result.position); assertEquals(f.base.notes, result.notes)
        assertEquals(f.identity, DocumentIdentityJson.fromDocument(result)); assertEquals(" 中  文 ", JSONObject(result.extensionsJson!!).getString("sibling"))
        assertEquals(f.base, f.library.read(f.entry).documents.single()); assertEquals(1, f.gets)
    }

    @Test fun waitingPickerCannotBorrowANewSessionWithTheSameOptionsOrOpenAfterClose() = runBlocking<Unit> {
        val f = Fixture(); val p = f.adapter().prepare(); val request = f.tickets.begin("waiting.zip", f.connection)
        f.tickets.prepare(request, LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(p.document))), f.connection, p.checkBeforeWrite)
        f.session.close()
        val next = PortableAccountExportSession(f.owner, f.entry.key).apply { activate() }.issue(); next.checkCurrent()
        val opened = AtomicBoolean(false)
        val failure = withContext(Dispatchers.IO) { runCatching { writePortableExport(f.tickets, request.id, { f.connection }) { opened.set(true); ByteArrayOutputStream() } } }
        assertTrue(failure.isFailure); assertFalse(opened.get()); next.checkCurrent()
    }

    @Test fun heldGetAfterCommittedSessionDisposalDoesNotReturnPreparedBytes() = runBlocking<Unit> {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.beforeQuery = { entered.complete(Unit); release.await() }
        val result = async(Dispatchers.IO) { runCatching { f.adapter().prepare() } }
        entered.await(); f.session.close(); release.complete(Unit)
        assertTrue(result.await().isFailure); assertEquals(f.base, f.library.read(f.entry).documents.single())
    }

    @Test fun finalJournalGuardSeesSessionRevocationBeforeCloseCanAcquireTheHeldGate() = runBlocking<Unit> {
        val f = Fixture(); val prepared = f.adapter().prepare()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val closing = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        f.beforeObserve = { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)) }
        try {
            val checking = pool.submit<Result<Unit>> { runCatching { prepared.checkBeforeWrite() } }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val closed = pool.submit { closing.countDown(); f.session.close() }
            assertTrue(closing.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (runCatching { f.grant.checkCurrent() }.isSuccess && System.nanoTime() < deadline) Thread.yield()
            assertTrue(runCatching { f.grant.checkCurrent() }.isFailure)
            assertFalse(closed.isDone) // Gate remains held by the final journal validation.
            release.countDown(); assertTrue(checking.get(10, TimeUnit.SECONDS).isFailure); closed.get(10, TimeUnit.SECONDS)
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)) }
    }
}
