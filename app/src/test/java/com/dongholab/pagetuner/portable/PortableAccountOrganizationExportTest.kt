package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.*
import com.dongholab.pagetuner.core.translation.*
import com.dongholab.pagetuner.translation.sync.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PortableAccountOrganizationExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val organization: LibraryOrganization = LibraryOrganization("分類  Folder", listOf("é", "e\u0301", "Tag", "tag", "東京  二", "😀"), true)

    private inner class Fixture {
        val original: ExchangeDocument = PortableDocumentMapper.server(NotesFixture.document().source).documents.single()
        var document: ExchangeDocument = original
        var deleted = false
        val entry: PortableLibraryEntry get() = PortableLibraryEntry("a".repeat(64), 0, document)
        val connection: ServerReadingConnection = NotesFixture.connection { error("No implicit request") }
        var current: ServerReadingConnection? = connection
        var linked: PortableServerBinding? = PortableServerBinding(connection.accountKey, entry.key,
            NotesFixture.recordId, requireNotNull(DocumentIdentityJson.fromDocument(document)))
        var readBinding: (String, PortableLibraryEntry) -> PortableServerBinding? = { _, _ -> linked }
        var local: DeviceLibraryOrganization = DeviceLibraryOrganization()
        var remote: ServerLibraryOrganization = ServerLibraryOrganization("ORIGINAL", NotesFixture.recordId, 5, organization, NotesFixture.time)
        var queries = 0
        var verifications = 0
        var onQuery: suspend () -> Unit = {}
        var onVerify: () -> Unit = {}
        var onJournal: () -> Unit = {}
        val tickets: PortableExportTickets = PortableExportTickets()
        val request: PortableExportRequest = tickets.begin("book.zip", connection)
        fun check(): Unit = tickets.check(request, current)
        fun adapter(): PortableAccountOrganizationExport = PortableAccountOrganizationExport({ check(!deleted); document }, { check(!deleted); document },
            { account, entry -> readBinding(account, entry) }, { target ->
                assertEquals(connection.accountKey, target.accountKey)
                assertEquals(requireNotNull(linked).identity.kind.name, target.kind)
                assertEquals(NotesFixture.recordId, target.recordId)
                onJournal(); local
            }, verify = { account, record, identity ->
                assertSame(connection.client, account.client); assertEquals(NotesFixture.recordId, record)
                assertEquals(requireNotNull(linked).identity, identity); verifications++; onVerify()
            }, query = { account, kind, record ->
                assertSame(connection.client, account.client)
                assertEquals(requireNotNull(linked).identity.kind.name, kind); assertEquals(NotesFixture.recordId, record)
                queries++; onQuery(); remote
            })
        suspend fun prepare(): PreparedAccountOrganization = adapter().prepare(entry, connection, ::check)
        fun stage(prepared: PreparedAccountOrganization): Unit = tickets.prepare(request,
            LibraryExchangeCodec.write(LibraryExchangePackage(NotesFixture.time, listOf(prepared.document))), current, prepared.checkBeforeWrite)
    }
    private suspend fun rejects(block: suspend () -> Unit): Exception {
        try { block() } catch (error: Exception) { return error }
        throw AssertionError("Expected export rejection")
    }

    @Test fun preservesExactValuesLegacyAndSiblingSnapshotsWithoutWritingJournal() = runTest {
        val f = Fixture()
        val glossary = BookGlossarySnapshots(snapshots = listOf("ko", "ja").map { language ->
            BookGlossarySnapshot(BookGlossarySyncIdentity("source", "book", language), BookGlossarySnapshotPresence.PRESENT,
                (1..500).map { BookGlossarySyncEntry("id-$it", " x$it ", " y$it ", " alias ", BookGlossarySyncKind.Term, it % 2 == 0, it % 3 == 0) }) })
        f.document = BookGlossarySnapshotsJson.withSnapshots(f.document.copy(
            organization = ExchangeOrganization(" legacy ", listOf(" wide ", "Keep"), true)), glossary)
        f.document = f.document.copy(extensionsJson = JSONObject(f.document.extensionsJson!!).put("futureSibling", JSONObject().put("value", " exact ")).toString())
        val before = f.document
        val cached = f.remote.copy(version = 1, organization = LibraryOrganization())
        f.local = DeviceLibraryOrganization(remote = cached)
        val prepared = f.prepare(); f.stage(prepared)
        val output = ByteArrayOutputStream()
        f.tickets.write(f.request.id, { f.current }) { output }
        val decoded = LibraryExchangeCodec.read(output.toByteArray()).documents.single()
        assertEquals(LibraryOrganizationSnapshotValue(organization.folder, organization.tags, true), prepared.snapshot.organization)
        assertEquals(prepared.snapshot, LibraryOrganizationSnapshotJson.fromDocument(decoded))
        assertEquals(glossary, BookGlossarySnapshotsJson.fromDocument(decoded))
        assertEquals(before.copy(extensionsJson = decoded.extensionsJson), decoded)
        assertEquals(before, f.document); assertEquals(DeviceLibraryOrganization(remote = cached), f.local)
        assertEquals(1, f.queries); assertEquals(1, f.verifications)
        val raw = JSONObject(decoded.extensionsJson!!).getJSONObject(LibraryOrganizationSnapshotJson.EXTENSION_KEY).toString()
        listOf("accountKey", "expectedVersion", "mutationId", "updatedAt", "password").forEach { assertFalse(raw.contains(it)) }
    }

    @Test fun absentAndExplicitEmptyAreDistinct() = runTest {
        listOf(0L to null, 1L to LibraryOrganization()).forEach { (version, value) ->
            val f = Fixture(); f.remote = f.remote.copy(version = version, organization = value, updatedAt = if (version == 0L) null else NotesFixture.time)
            val prepared = f.prepare()
            assertEquals(if (version == 0L) LibraryOrganizationSnapshotPresence.ABSENT else LibraryOrganizationSnapshotPresence.PRESENT, prepared.snapshot.presence)
            assertEquals(value?.let { LibraryOrganizationSnapshotValue(it.folder, it.tags, it.favorite) }, prepared.snapshot.organization)
        }
    }

    @Test fun translationPreservesOriginalIdentityAndExactLanguageCase() = runTest {
        val f = Fixture(); val content = NotesFixture.document().source.sourceContent!!
        val artifact = TranslationArtifact(content.identity, content.sourceRevision, "en", "kO", "translator", "", "", "",
            content.paragraphs.map { TranslatedParagraph(it.paragraphId, "번역") })
        f.document = PortableDocumentMapper.server(ServerLibraryDocument(
            ServerLibraryEntry(NotesFixture.recordId, "Translation", "kO", 3, artifact.sourceRevision, ServerLibraryKind.Translations),
            artifact.paragraphs.map { it.text }, StoredTranslation(NotesFixture.recordId, artifact, NotesFixture.time))).documents.single()
        f.linked = f.linked!!.copy(identity = requireNotNull(DocumentIdentityJson.fromDocument(f.document)))
        f.remote = f.remote.copy(kind = "TRANSLATION")
        val prepared = f.prepare()
        assertEquals("kO", prepared.snapshot.identity.targetLanguage)
        assertEquals("source", prepared.snapshot.identity.contentProviderId)
        assertEquals(f.linked!!.identity, prepared.snapshot.identity)
    }

    @Test fun invalidBindingAndServerScopeOrVersionSemanticsAreRejected() = runTest {
        val unbound = Fixture(); unbound.linked = null
        rejects { unbound.prepare() }; assertEquals(0, unbound.queries)
        listOf<(ServerLibraryOrganization) -> ServerLibraryOrganization>(
            { it.copy(kind = "TRANSLATION") }, { it.copy(recordId = NotesFixture.otherId) },
            { it.copy(version = 0) }, { it.copy(organization = null) }, { it.copy(updatedAt = null) },
            { it.copy(organization = LibraryOrganization(" untrimmed")) },
        ).forEach { change -> val f = Fixture(); f.remote = change(f.remote); rejects { f.prepare() } }
    }

    @Test fun pendingQueuedConflictBlockBeforeAfterAndSaf() = runTest {
        val base = Fixture(); val mutation = LibraryOrganizationMutation(5, NotesFixture.noteId, organization)
        listOf(DeviceLibraryOrganization(pending = mutation), DeviceLibraryOrganization(queued = organization),
            DeviceLibraryOrganization(conflict = base.remote)).forEach { blocked ->
            val before = Fixture(); before.local = blocked; rejects { before.prepare() }; assertEquals(0, before.queries)
            val after = Fixture(); after.onQuery = { after.local = blocked }; rejects { after.prepare() }
            val atWrite = Fixture(); atWrite.stage(atWrite.prepare()); atWrite.local = blocked
            var opened = false
            rejects { atWrite.tickets.write(atWrite.request.id, { atWrite.current }) { opened = true; ByteArrayOutputStream() } }
            assertFalse(opened)
        }
    }

    @Test fun preGetHighWaterCannotDisappearOrBeMutatedToHideStaleResponse() = runTest {
        listOf(true, false).forEach { divergent ->
            val f = Fixture()
            f.local = DeviceLibraryOrganization(remote = if (divergent) f.remote.copy(organization = LibraryOrganization()) else f.remote.copy(version = 6))
            f.onQuery = { f.local = DeviceLibraryOrganization() }
            rejects { f.prepare() }
        }
        val f = Fixture(); val tags = organization.tags.toMutableList()
        f.local = DeviceLibraryOrganization(remote = f.remote.copy(organization = organization.copy(tags = tags)))
        f.onQuery = { tags.clear(); f.remote = f.remote.copy(organization = LibraryOrganization()) }
        rejects { f.prepare() }
    }

    @Test fun staleJournalAfterGetAndWhileSafWaitsIsRejected() = runTest {
        listOf(true, false).forEach { newer ->
            val f = Fixture(); val changed = if (newer) f.remote.copy(version = 6) else f.remote.copy(organization = LibraryOrganization())
            f.onQuery = { f.local = DeviceLibraryOrganization(remote = changed) }; rejects { f.prepare() }
            val atWrite = Fixture(); atWrite.stage(atWrite.prepare()); atWrite.local = DeviceLibraryOrganization(remote = changed)
            var opened = false
            rejects { atWrite.tickets.write(atWrite.request.id, { atWrite.current }) { opened = true; ByteArrayOutputStream() } }
            assertFalse(opened)
        }
    }

    @Test fun deletedDocumentAndDetachedGetResponseCannotChangePreparedBytes() = runTest {
        val deleted = Fixture(); deleted.onQuery = { deleted.deleted = true }
        rejects { deleted.prepare() }
        val atWrite = Fixture(); atWrite.stage(atWrite.prepare()); atWrite.deleted = true
        var opened = false
        rejects { atWrite.tickets.write(atWrite.request.id, { atWrite.current }) { opened = true; ByteArrayOutputStream() } }
        assertFalse(opened)
        val f = Fixture(); val tags = organization.tags.toMutableList()
        f.remote = f.remote.copy(organization = organization.copy(tags = tags))
        val prepared = f.prepare(); tags.clear()
        prepared.checkBeforeWrite()
        assertEquals(organization.tags, prepared.snapshot.organization!!.tags)
        assertEquals(organization.tags, LibraryOrganizationSnapshotJson.fromDocument(prepared.document)!!.organization!!.tags)
    }

    @Test fun canonicalChangesIncludingNotesAndPositionCannotUseUnchangedIdentity() = runTest {
        listOf<(ExchangeDocument) -> ExchangeDocument>(
            { it.copy(bookTitle = "Changed") }, { it.copy(paragraphs = it.paragraphs.map { p -> p.copy(text = p.text + "!") }) },
            { it.copy(position = ExchangeAnchor("p1", 1)) },
            { it.copy(notes = listOf(ExchangeNote(NotesFixture.noteId, "note", "Memo", "Memo", "First", ExchangeAnchor("p1", 0), NotesFixture.time))) },
        ).forEach { change ->
            val f = Fixture(); f.onQuery = { f.document = change(f.document) }; rejects { f.prepare() }
            val atWrite = Fixture(); atWrite.stage(atWrite.prepare()); atWrite.document = change(atWrite.document)
            var opened = false
            rejects { atWrite.tickets.write(atWrite.request.id, { atWrite.current }) { opened = true; ByteArrayOutputStream() } }
            assertFalse(opened)
        }
        val f = Fixture(); val prepared = f.prepare()
        f.onJournal = { f.document = f.document.copy(bookTitle = "Deleted during journal read") }
        rejects { prepared.checkBeforeWrite() }
    }

    @Test fun sameUuidReconnectionInvalidatesVerificationGetAndSafIncludingOpen() = runTest {
        repeat(4) { phase ->
            val f = Fixture(); val store = PortableServerBindingStore(temporary.newFolder())
            val original: PortableServerBinding = f.linked!!; store.save(original); f.readBinding = store::read
            fun reconnect() { store.remove(original.accountKey, f.entry); store.save(original) }
            when (phase) {
                0 -> { f.onVerify = ::reconnect; rejects { f.prepare() }; assertEquals(0, f.queries) }
                1 -> { f.onQuery = { reconnect() }; rejects { f.prepare() } }
                else -> {
                    f.stage(f.prepare()); val output = ByteArrayOutputStream(); var opened = false
                    if (phase == 2) reconnect()
                    rejects { f.tickets.write(f.request.id, { f.current }) { opened = true; if (phase == 3) reconnect(); output } }
                    assertEquals(phase == 3, opened); assertEquals(0, output.size())
                }
            }
            assertEquals(f.original, f.document); assertEquals(DeviceLibraryOrganization(), f.local)
        }
    }

    @Test fun lateGetCannotSurviveAccountAbaClientReplacementOrCancelledSelection() = runTest {
        repeat(3) { mode ->
            val f = Fixture(); val entered = CompletableDeferred<Unit>(); val response = CompletableDeferred<Unit>()
            f.onQuery = { entered.complete(Unit); response.await() }
            val preparation = async { runCatching { f.prepare() } }; runCurrent(); entered.await()
            when (mode) {
                0 -> { f.tickets.connect(NotesFixture.connection("other") { error("Unexpected") }); f.current = f.connection }
                1 -> f.current = NotesFixture.connection { error("Unexpected") }
                else -> f.tickets.cancel(f.request.id)
            }
            response.complete(Unit)
            assertTrue(preparation.await().exceptionOrNull() is PortableExportExpired)
        }
    }

    @Test fun unsupportedAndOversizeExtensionsAreNeverOverwrittenOrTruncated() = runTest {
        val f = Fixture()
        f.document = f.document.copy(extensionsJson = JSONObject(f.document.extensionsJson!!)
            .put(LibraryOrganizationSnapshotJson.EXTENSION_KEY, JSONObject().put("version", 2)).toString())
        val before = f.document; rejects { f.prepare() }; assertEquals(0, f.queries); assertEquals(before, f.document)
        val large = Fixture()
        large.document = large.document.copy(extensionsJson = JSONObject(large.document.extensionsJson!!).put("retained", "가".repeat(87_200)).toString())
        val raw = large.document
        rejects { large.prepare() }; assertEquals(raw, large.document)
    }

    @Test fun actualHttpAdapterOnlyVerifiesAndGetsNeverWritesOrganization() = runTest {
        val f = Fixture(); val requests = mutableListOf<TranslationStoreHttpRequest>()
        val connection: ServerReadingConnection = NotesFixture.connection { request ->
            requests += request
            val json = when {
                request.url.endsWith("/library-identity/verify") -> JSONObject(request.body!!).put("verified", true)
                request.url.endsWith("/library-organization/ORIGINAL/${NotesFixture.recordId}") && request.method == "GET" -> ServerLibraryOrganizationJson.encode(f.remote)
                else -> error("Unexpected mutation: ${request.method}")
            }
            TranslationStoreHttpResponse(200, body = json.toString())
        }
        val adapter = PortableAccountOrganizationExport({ f.document }, { f.document }, { _, _ -> f.linked }, { f.local })
        val prepared = adapter.prepare(f.entry, connection) {}
        assertEquals(organization.tags, prepared.snapshot.organization!!.tags)
        assertEquals(listOf("POST", "GET"), requests.map { it.method })
    }
}
