package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.*
import com.dongholab.pagetuner.core.translation.*
import com.dongholab.pagetuner.translation.glossary.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortableAccountGlossaryExportTest {
    private val entries: List<BookGlossaryEntry> = listOf(
        BookGlossaryEntry("original:二", " Alice ", " 앨리스 ", " 아리 ", GlossaryTermKind.Character, true, false),
        BookGlossaryEntry("place/一", "River", " 강 ", " ", GlossaryTermKind.Place, false, true),
        BookGlossaryEntry("term-3", "River", "하천", "", GlossaryTermKind.Term, true, true),
    )
    private inner class Fixture {
        val original: ExchangeDocument = PortableDocumentMapper.server(NotesFixture.document().source).documents.single()
        var document: ExchangeDocument = original
        val entry: PortableLibraryEntry get() = PortableLibraryEntry("a".repeat(64), 0, document)
        val connection: ServerReadingConnection = NotesFixture.connection { error("No implicit request") }
        var current: ServerReadingConnection? = connection
        var linked: PortableServerBinding? = PortableServerBinding(connection.accountKey, entry.key,
            NotesFixture.recordId, requireNotNull(DocumentIdentityJson.fromDocument(document)))
        val identity: BookGlossarySyncIdentity = BookGlossarySyncIdentity("source", "book", "ko")
        var local: DeviceBookGlossary = DeviceBookGlossary()
        var remote: ServerBookGlossary = ServerBookGlossary(identity, 5, BookGlossaryValue(entries), NotesFixture.time)
        var queries = 0
        var verifications = 0
        var onVerify: () -> Unit = {}
        var onQuery: suspend () -> Unit = {}
        val tickets: PortableExportTickets = PortableExportTickets()
        val request: PortableExportRequest = tickets.begin("book.zip", connection)
        fun check(): Unit = tickets.check(request, current)
        fun adapter(): PortableAccountGlossaryExport = PortableAccountGlossaryExport({ document }, { document }, { _, _ -> linked }, { local },
            verify = { account, record, proof ->
                assertSame(connection.client, account.client); assertEquals(NotesFixture.recordId, record)
                assertEquals(linked?.identity, proof); verifications++; onVerify()
            }, query = { account, scope ->
                assertSame(connection.client, account.client); assertEquals(identity, scope)
                queries++; onQuery(); remote
            })
        suspend fun prepare(language: String = "ko"): PreparedAccountGlossary = adapter().prepare(entry, language, connection, ::check)
        fun stage(prepared: PreparedAccountGlossary) {
            tickets.prepare(request, LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document))),
                current, prepared.checkBeforeWrite)
        }
    }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected export rejection")
    }

    @Test fun freshReadPreservesAllMetadataAndOtherScopesWithoutEditingOriginalOrJournal() = runTest {
        val f = Fixture()
        val old = BookGlossarySnapshot(f.identity, BookGlossarySnapshotPresence.DELETED, null)
        val other = old.copy(identity = f.identity.copy(targetLanguage = "ja"), presence = BookGlossarySnapshotPresence.ABSENT)
        f.document = BookGlossarySnapshotsJson.withSnapshots(f.document, BookGlossarySnapshots(snapshots = listOf(other, old)))
        val before = f.document
        val cached = f.remote.copy(version = 1, value = BookGlossaryValue(emptyList()))
        f.local = DeviceBookGlossary(remote = cached)
        val prepared = f.prepare()
        f.stage(prepared)
        val output = ByteArrayOutputStream()
        f.tickets.write(f.request.id, { f.current }) { output }
        val decoded = LibraryExchangeCodec.read(output.toByteArray()).documents.single()
        val snapshots = BookGlossarySnapshotsJson.fromDocument(decoded)!!.snapshots
        assertEquals(other, snapshots[0])
        assertEquals(entries.map { it.syncEntry() }, snapshots[1].entries)
        assertEquals(BookGlossarySnapshotPresence.PRESENT, snapshots[1].presence)
        assertEquals(before.copy(extensionsJson = decoded.extensionsJson), decoded)
        assertEquals(DocumentIdentityJson.fromDocument(before), DocumentIdentityJson.fromDocument(decoded))
        assertEquals(before, f.document)
        assertEquals(DeviceBookGlossary(remote = cached), f.local)
        assertEquals(1, f.queries); assertEquals(1, f.verifications)
        val raw = JSONObject(decoded.extensionsJson!!).getJSONObject(BookGlossarySnapshotsJson.EXTENSION_KEY).toString()
        listOf("accountKey", "expectedVersion", "mutationId", "updatedAt", "password").forEach { assertFalse(raw.contains(it)) }
    }

    @Test fun absentDeletedAndPresentEmptyRemainDistinct() = runTest {
        listOf(0L to BookGlossaryValue(null), 1L to BookGlossaryValue(null), 2L to BookGlossaryValue(emptyList()))
            .zip(BookGlossarySnapshotPresence.entries).forEach { (value, presence) ->
                val f = Fixture()
                f.remote = f.remote.copy(version = value.first, value = value.second, updatedAt = if (value.first == 0L) null else NotesFixture.time)
                val prepared = f.prepare()
                assertEquals(presence, prepared.snapshot.presence)
                assertEquals(value.second.entries?.map { it.syncEntry() }, prepared.snapshot.entries)
            }
    }

    @Test fun exactLanguageAndVerifiedBindingAreRequiredBeforeQuery() = runTest {
        listOf("KO", " ko", "auto", "ko ").forEach { language ->
            val f = Fixture(); rejects { f.prepare(language) }; assertEquals(0, f.queries)
        }
        val unbound = Fixture(); unbound.linked = null
        rejects { unbound.prepare() }; assertEquals(0, unbound.queries)
        val mismatch = Fixture(); mismatch.document = mismatch.document.copy(paragraphs = mismatch.document.paragraphs.map { it.copy(text = "changed") })
        rejects { mismatch.prepare() }; assertEquals(0, mismatch.queries)
        val changed = Fixture(); changed.onVerify = { changed.linked = null }
        rejects { changed.prepare() }; assertEquals(0, changed.queries)
    }

    @Test fun translationsUseExactArtifactLanguageAndOriginalProviderInsteadOfTranslator() = runTest {
        val f = Fixture()
        val content = NotesFixture.document().source.sourceContent!!
        val artifact = TranslationArtifact(content.identity, content.sourceRevision, "en", "ko", "translator", "", "", "",
            content.paragraphs.map { TranslatedParagraph(it.paragraphId, "번역") })
        f.document = PortableDocumentMapper.server(ServerLibraryDocument(
            ServerLibraryEntry(NotesFixture.recordId, "Translation", "ko", 3, artifact.sourceRevision, ServerLibraryKind.Translations),
            artifact.paragraphs.map { it.text }, StoredTranslation(NotesFixture.recordId, artifact, NotesFixture.time))).documents.single()
        f.linked = f.linked!!.copy(identity = requireNotNull(DocumentIdentityJson.fromDocument(f.document)))
        rejects { f.prepare("ja") }; assertEquals(0, f.queries)
        val prepared = f.prepare()
        assertEquals(f.identity, prepared.snapshot.identity)
        assertEquals("source", prepared.snapshot.identity.providerId)
    }

    @Test fun pendingQueuedAndConflictBlockBeforeAndAfterFreshReadAndBeforeWrite() = runTest {
        val base = Fixture()
        val mutation = BookGlossaryMutation(5, NotesFixture.noteId, BookGlossaryValue(entries))
        val blocked = listOf(DeviceBookGlossary(pending = mutation), DeviceBookGlossary(queued = mutation.value),
            DeviceBookGlossary(conflict = base.remote))
        blocked.forEach { journal ->
            val before = Fixture(); before.local = journal
            rejects { before.prepare() }; assertEquals(0, before.queries)
            val after = Fixture(); after.onQuery = { after.local = journal }
            rejects { after.prepare() }
            val atWrite = Fixture(); atWrite.stage(atWrite.prepare()); atWrite.local = journal
            var opened = false
            rejects { atWrite.tickets.write(atWrite.request.id, { atWrite.current }) { opened = true; ByteArrayOutputStream() } }
            assertFalse(opened)
        }
    }

    @Test fun newerOrDivergentJournalRejectsStaleFreshResponseAndSafWrite() = runTest {
        listOf(true, false).forEach { newer ->
            val f = Fixture()
            val changed = if (newer) f.remote.copy(version = 6) else f.remote.copy(value = BookGlossaryValue(emptyList()))
            f.onQuery = { f.local = DeviceBookGlossary(remote = changed) }
            rejects { f.prepare() }
            val later = Fixture(); later.stage(later.prepare()); later.local = DeviceBookGlossary(remote = changed)
            var opened = false
            rejects { later.tickets.write(later.request.id, { later.current }) { opened = true; ByteArrayOutputStream() } }
            assertFalse(opened)
        }
    }

    @Test fun lateResponseCannotSurviveAccountClientOrGenerationSwitch() = runTest {
        repeat(3) { mode ->
            val f = Fixture(); val response = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            f.onQuery = { entered.complete(Unit); response.await() }
            val export = async { runCatching { f.prepare() } }
            runCurrent(); entered.await()
            when (mode) {
                0 -> f.current = NotesFixture.connection("other") { error("Unexpected") }
                1 -> f.current = NotesFixture.connection { error("Unexpected") }
                else -> { f.tickets.connect(null); f.current = f.connection }
            }
            response.complete(Unit)
            assertTrue(export.await().exceptionOrNull() is PortableExportExpired)
        }
    }

    @Test fun bindingRemovalWhileSafWaitsOrDuringStreamOpenCannotWriteBytes() = runTest {
        val f = Fixture(); f.stage(f.prepare()); f.linked = null
        var opened = false
        rejects { f.tickets.write(f.request.id, { f.current }) { opened = true; ByteArrayOutputStream() } }
        assertFalse(opened)
        val duringOpen = Fixture(); duringOpen.stage(duringOpen.prepare()); val output = ByteArrayOutputStream()
        rejects { duringOpen.tickets.write(duringOpen.request.id, { duringOpen.current }) { duringOpen.linked = null; output } }
        assertEquals(0, output.size())
    }

    @Test fun unsupportedExtensionAndOversizeCannotDiscardOriginalSnapshots() = runTest {
        val unsupported = Fixture()
        unsupported.document = unsupported.document.copy(extensionsJson = JSONObject(unsupported.document.extensionsJson!!)
            .put(BookGlossarySnapshotsJson.EXTENSION_KEY, JSONObject().put("version", 2).put("snapshots", org.json.JSONArray())).toString())
        val before = unsupported.document
        rejects { unsupported.prepare() }; assertEquals(0, unsupported.queries); assertEquals(before, unsupported.document)
        val huge = Fixture(); huge.remote = huge.remote.copy(value = BookGlossaryValue((1..500).map {
            BookGlossaryEntry("entry-$it", "가".repeat(200), "나".repeat(200), "다".repeat(200)) }))
        val error = rejects { huge.prepare() }
        assertTrue(error.message.orEmpty().contains("256 KiB")); assertEquals(huge.original, huge.document)
    }

    @Test fun actualHttpAdapterOnlyVerifiesIdentityAndQueriesGlossaryNeverWritesIt() = runTest {
        val f = Fixture(); val requests = mutableListOf<TranslationStoreHttpRequest>()
        val connection = NotesFixture.connection { request ->
            requests += request
            val json = when {
                request.url.endsWith("/library-identity/verify") -> JSONObject(request.body!!).put("verified", true)
                request.url.endsWith("/book-glossary/query") -> ServerBookGlossaryJson.encode(f.remote)
                else -> error("Unexpected glossary mutation: ${request.method}")
            }
            TranslationStoreHttpResponse(200, body = json.toString())
        }
        val adapter = PortableAccountGlossaryExport({ f.document }, { f.document }, { _, _ -> f.linked }, { f.local })
        val prepared = adapter.prepare(f.entry, "ko", connection) {}
        assertEquals(entries.map { it.syncEntry() }, prepared.snapshot.entries)
        assertEquals(listOf("POST", "POST"), requests.map { it.method })
        assertTrue(requests[0].url.endsWith("/library-identity/verify"))
        assertTrue(requests[1].url.endsWith("/book-glossary/query"))
    }
}
