package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.*
import com.dongholab.pagetuner.translation.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PortableGlossaryAdoptionTest {
    private class Fixture(val scope: CoroutineScope, io: CoroutineDispatcher,
        val presence: BookGlossarySnapshotPresence = BookGlossarySnapshotPresence.PRESENT) {
        val account: ServerReadingConnection = NotesFixture.connection { error("No automatic HTTP") }
        var current: ServerReadingConnection? = account
        val identity = BookGlossarySyncIdentity("source", "book", "ko")
        val entries = listOf(BookGlossarySyncEntry("opaque-1", " Source ", " 번역 ", " 별칭 ", BookGlossarySyncKind.Character, true, false))
        val snapshot = BookGlossarySnapshot(identity, presence, entries.takeIf { presence == BookGlossarySnapshotPresence.PRESENT })
        var document: ExchangeDocument = BookGlossarySnapshotsJson.withSnapshots(
            PortableDocumentMapper.server(NotesFixture.document().source).documents.single(), BookGlossarySnapshots(snapshots = listOf(snapshot)))
        val entry: PortableLibraryEntry get() = PortableLibraryEntry("a".repeat(64), 0, document)
        var linked: PortableServerBinding? = PortableServerBinding(account.accountKey, entry.key, NotesFixture.recordId,
            requireNotNull(DocumentIdentityJson.fromDocument(document)))
        var local = DeviceBookGlossary()
        var remote = ServerBookGlossary(identity, 0, BookGlossaryValue(null), null)
        var queries = 0
        var verifies = 0
        var commits = 0
        var onQuery: suspend () -> Unit = {}
        var onCommit: () -> Unit = {}
        val adoption = PortableGlossaryAdoption(scope, { document }, { document }, { _, _ -> linked }, { local },
            verify = { _, _, _ -> verifies++ }, query = { _, requested -> assertEquals(identity, requested); queries++; onQuery(); remote }, io = io)
        suspend fun prepare(): PortableGlossaryComparison {
            adoption.select(entry, account, { current })
            adoption.state.first { !it.busy }
            adoption.compare(0) { current }
            return requireNotNull(adoption.state.first { !it.busy }.comparison)
        }
        fun confirm(id: String) = adoption.confirm(id, { current }) { comparison, connection, guard ->
            guard(); assertSame(account.client, connection.client); assertEquals(snapshot, comparison.snapshot)
            commits++; onCommit(); Result.success(Unit)
        }
    }

    @Test fun readonlyComparisonThenExplicitRequeryAndSingleConfirmationPreservesEveryField() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        val original = f.document; val shown = f.prepare()
        assertEquals(0, f.commits); assertEquals(1, f.queries); assertEquals(DeviceBookGlossary(), f.local)
        assertEquals(f.snapshot, shown.snapshot); assertEquals(f.remote, shown.remote)
        f.confirm(shown.id); f.confirm(shown.id)
        f.adoption.state.first { it.committed }
        f.confirm(shown.id); runCurrent()
        assertEquals(1, f.commits); assertEquals(2, f.queries); assertEquals(2, f.verifies)
        assertEquals(original, f.document); assertEquals(DeviceBookGlossary(), f.local)
    }

    @Test fun absentIsInformationOnlyAndDeletedAndPresentEmptyStayDifferent() = runTest(timeout = 10.seconds) {
        val absent = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), BookGlossarySnapshotPresence.ABSENT)
        val shown = absent.prepare(); absent.confirm(shown.id); runCurrent()
        assertEquals(0, absent.commits); assertEquals(1, absent.queries); assertFalse(absent.adoption.state.value.committed)
        val deleted = Fixture(backgroundScope, StandardTestDispatcher(testScheduler), BookGlossarySnapshotPresence.DELETED)
        val deletion = deleted.prepare(); assertNull(deletion.snapshot.entries); deleted.confirm(deletion.id)
        deleted.adoption.state.first { it.committed }; assertEquals(1, deleted.commits)
        val empty = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        empty.document = BookGlossarySnapshotsJson.withSnapshots(empty.document,
            BookGlossarySnapshots(snapshots = listOf(empty.snapshot.copy(entries = emptyList()))))
        val comparison = empty.prepare(); assertEquals(BookGlossarySnapshotPresence.PRESENT, comparison.snapshot.presence)
        assertEquals(emptyList<BookGlossarySyncEntry>(), comparison.snapshot.entries)
    }

    @Test fun serverOrDeviceChangeBeforeConfirmRequiresAnotherComparison() = runTest(timeout = 10.seconds) {
        repeat(3) { mode ->
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); val shown = f.prepare()
            when (mode) {
                0 -> f.remote = f.remote.copy(version = 1, value = BookGlossaryValue(emptyList()), updatedAt = NotesFixture.time)
                1 -> f.local = DeviceBookGlossary(pending = BookGlossaryMutation(0, NotesFixture.noteId, BookGlossaryValue(null)))
                else -> f.local = f.local.copy(selected = true)
            }
            f.confirm(shown.id)
            val rejected = f.adoption.state.first { !it.busy }
            assertNotNull(rejected.error); assertNull(rejected.comparison); assertEquals(0, f.commits)
        }
    }

    @Test fun pendingArrivingDuringFinalQueryPreventsAdoption() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); val shown = f.prepare()
        f.onQuery = { f.local = DeviceBookGlossary(pending = BookGlossaryMutation(0, NotesFixture.noteId, BookGlossaryValue(null))) }
        f.confirm(shown.id)
        f.adoption.state.first { !it.busy }
        assertEquals(0, f.commits); assertNotNull(f.adoption.state.value.error)
    }

    @Test fun accountAtoBtoAAndClientReplacementInvalidateDelayedResponseAndOldConfirmation() = runTest(timeout = 10.seconds) {
        repeat(2) { mode ->
            val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); val shown = f.prepare()
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            f.onQuery = { started.complete(Unit); withContext(NonCancellable) { release.await() } }
            f.confirm(shown.id); runCurrent(); started.await()
            if (mode == 0) {
                val other = NotesFixture.connection("other") { error("No HTTP") }
                f.current = other; f.adoption.connect(other)
                f.current = f.account; f.adoption.connect(f.account)
            } else {
                f.current = NotesFixture.connection { error("No HTTP") }; f.adoption.connect(f.current)
            }
            release.complete(Unit); runCurrent()
            f.confirm(shown.id); runCurrent()
            assertEquals(0, f.commits); assertNull(f.adoption.state.value.comparison)
        }
    }

    @Test fun unlinkRelinkAndSnapshotReplacementExpireReviewedIntent() = runTest(timeout = 10.seconds) {
        val rebound = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); val shown = rebound.prepare()
        val originalBinding = rebound.linked
        // PortableLibraryViewModel invalidates this generation on both removeBinding and saveBinding.
        rebound.adoption.close(); rebound.linked = null
        rebound.adoption.close(); rebound.linked = originalBinding
        rebound.confirm(shown.id); runCurrent(); assertEquals(0, rebound.commits)
        val changed = Fixture(backgroundScope, StandardTestDispatcher(testScheduler)); val old = changed.prepare()
        changed.document = BookGlossarySnapshotsJson.withSnapshots(changed.document,
            BookGlossarySnapshots(snapshots = listOf(changed.snapshot.copy(entries = emptyList()))))
        changed.confirm(old.id); changed.adoption.state.first { !it.busy }
        assertEquals(0, changed.commits); assertNotNull(changed.adoption.state.value.error)
    }

    @Test fun mismatchedOriginalIdentityAndUnknownSnapshotsFailWithoutAnyQuery() = runTest(timeout = 10.seconds) {
        val f = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        f.document = BookGlossarySnapshotsJson.withSnapshots(f.document, BookGlossarySnapshots(snapshots =
            listOf(f.snapshot.copy(identity = f.identity.copy(bookId = "another-book")))))
        f.adoption.select(f.entry, f.account) { f.current }; f.adoption.state.first { !it.busy }
        f.adoption.compare(0) { f.current }; f.adoption.state.first { !it.busy }
        assertNotNull(f.adoption.state.value.error); assertEquals(0, f.queries)
        val unknown = Fixture(backgroundScope, StandardTestDispatcher(testScheduler))
        unknown.document = unknown.document.copy(extensionsJson = org.json.JSONObject(unknown.document.extensionsJson!!)
            .put(BookGlossarySnapshotsJson.EXTENSION_KEY, org.json.JSONObject().put("version", 2).put("snapshots", org.json.JSONArray())).toString())
        unknown.adoption.select(unknown.entry, unknown.account) { unknown.current }
        unknown.adoption.state.first { !it.busy }
        assertNotNull(unknown.adoption.state.value.error); assertEquals(0, unknown.queries)
    }
}
