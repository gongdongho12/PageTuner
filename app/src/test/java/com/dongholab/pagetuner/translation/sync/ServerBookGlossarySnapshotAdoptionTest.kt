package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ServerBookGlossarySnapshotAdoptionTest {
    private val identity = BookGlossarySyncIdentity("source", "book", "ko")
    private fun remote(version: Long = 0, value: BookGlossaryValue = BookGlossaryValue(null)) =
        ServerBookGlossary(identity, version, value, NotesFixture.time.takeIf { version > 0 })
    private fun snapshot(presence: BookGlossarySnapshotPresence, entries: List<BookGlossarySyncEntry>? = null) =
        BookGlossarySnapshot(identity, presence, entries)
    private class Store : ServerBookGlossaryStore {
        val records = ConcurrentHashMap<String, DeviceBookGlossary>()
        var beforeWrite: () -> Unit = {}
        override fun read(target: BookGlossaryTarget) = records[target.key] ?: DeviceBookGlossary()
        override fun write(target: BookGlossaryTarget, value: DeviceBookGlossary) {
            beforeWrite(); records[target.key] = decodeBookGlossaryJournal(encodeBookGlossaryJournal(target, value), target)
        }
        override fun targets(accountKey: String) = emptyList<BookGlossaryTarget>()
    }
    private fun mutation(request: TranslationStoreHttpRequest) = ServerBookGlossaryJson.mutation(JSONObject(request.body!!), identity)

    @Test fun absentCannotMutateButDeletedAbsentCreatesVersionOneTombstoneAndEmptyStaysPresent() = runTest(timeout = 10.seconds) {
        val store = Store(); val sent = mutableListOf<BookGlossaryMutation>()
        val account = NotesFixture.connection { request ->
            val change = mutation(request); sent += change
            NotesFixture.response(ServerBookGlossaryJson.encode(remote(change.expectedVersion + 1, change.value)))
        }
        val target = BookGlossaryTarget(account.accountKey, identity)
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }; sync.connect(account)
        val absent = sync.adoptSnapshot("absent", target, account, DeviceBookGlossary(), remote(), snapshot(BookGlossarySnapshotPresence.ABSENT)) {}
        assertTrue(absent.isFailure); assertTrue(sent.isEmpty()); assertTrue(store.records.isEmpty())
        assertTrue(sync.adoptSnapshot("deleted", target, account, DeviceBookGlossary(), remote(), snapshot(BookGlossarySnapshotPresence.DELETED)) {}.isSuccess)
        sync.state.first { it.base.remote?.version == 1L && it.pendingDocuments == 0 }
        assertNull(sent.single().value.entries); assertEquals(0L, sent.single().expectedVersion)
        val device = store.read(target)
        assertTrue(sync.adoptSnapshot("empty", target, account, device, device.remote!!,
            snapshot(BookGlossarySnapshotPresence.PRESENT, emptyList())) {}.isSuccess)
        sync.state.first { it.base.remote?.version == 2L && it.pendingDocuments == 0 }
        assertEquals(emptyList<com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry>(), sent.last().value.entries)
    }

    @Test fun fiveHundredEntriesAreExactAndDuplicateConfirmationCannotCreateSecondIntent() = runTest(timeout = 10.seconds) {
        val entries = (1..500).map { BookGlossarySyncEntry("opaque-$it", " Source $it ", " 번역 $it ", " Alias ",
            BookGlossarySyncKind.entries[it % 3], it % 2 == 0, it % 3 != 0) }
        val store = Store(); val sent = mutableListOf<BookGlossaryMutation>(); val release = CompletableDeferred<Unit>()
        val account = NotesFixture.connection { request ->
            val change = mutation(request); sent += change; release.await()
            NotesFixture.response(ServerBookGlossaryJson.encode(remote(1, change.value)))
        }
        val target = BookGlossaryTarget(account.accountKey, identity)
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }; sync.connect(account)
        val selected = snapshot(BookGlossarySnapshotPresence.PRESENT, entries)
        assertTrue(sync.adoptSnapshot("once", target, account, DeviceBookGlossary(), remote(), selected) {}.isSuccess)
        assertTrue(sync.adoptSnapshot("once", target, account, DeviceBookGlossary(), remote(), selected) {}.isFailure)
        val pending = store.read(target).pending!!
        assertEquals(entries, pending.value.entries!!.map { it.syncEntry() })
        assertNull(store.read(target).queued)
        release.complete(Unit)
        sync.state.first { it.phase == ReadingProgressPhase.Synced && it.base.remote?.version == 1L }
        assertEquals(1, sent.size); assertEquals(entries, sent.single().value.entries!!.map { it.syncEntry() })
    }

    @Test fun changedDevicePendingConflictAndStaleHostGuardRejectBeforeStorageWrite() = runTest(timeout = 10.seconds) {
        val store = Store(); val account = NotesFixture.connection { error("No HTTP") }
        val target = BookGlossaryTarget(account.accountKey, identity)
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }; sync.connect(account)
        sync.state.first { it.accountKey == account.accountKey }
        val selected = snapshot(BookGlossarySnapshotPresence.DELETED)
        val pending = BookGlossaryMutation(0, NotesFixture.noteId, BookGlossaryValue(null))
        listOf(DeviceBookGlossary(pending = pending), DeviceBookGlossary(pending = pending, queued = BookGlossaryValue(emptyList())),
            DeviceBookGlossary(pending = pending, conflict = remote(1))).forEachIndexed { index, changed ->
            store.records[target.key] = changed
            assertTrue(sync.adoptSnapshot("changed-$index", target, account, DeviceBookGlossary(), remote(), selected) {}.isFailure)
            assertEquals(changed, store.read(target))
        }
        store.records.clear()
        assertTrue(sync.adoptSnapshot("guard", target, account, DeviceBookGlossary(), remote(), selected) { error("Account switched") }.isFailure)
        assertTrue(store.records.isEmpty())
    }

    @Test fun confirmedIntentSurvivesNetworkRetryAndCasConflictRemainsReachable() = runTest(timeout = 10.seconds) {
        val store = Store(); val sent = mutableListOf<BookGlossaryMutation>()
        val account = NotesFixture.connection { request ->
            val change = mutation(request); sent += change
            if (sent.size == 1) throw SocketTimeoutException()
            TranslationStoreHttpResponse(409, body = JSONObject().put("code", "BOOK_GLOSSARY_CONFLICT")
                .put("current", ServerBookGlossaryJson.encode(remote(2, BookGlossaryValue(emptyList())))).toString())
        }
        val target = BookGlossaryTarget(account.accountKey, identity)
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }; sync.connect(account)
        assertTrue(sync.adoptSnapshot("retry", target, account, DeviceBookGlossary(), remote(), snapshot(BookGlossarySnapshotPresence.DELETED)) {}.isSuccess)
        sync.state.first { it.phase == ReadingProgressPhase.Offline }
        advanceTimeBy(15_001); runCurrent()
        val conflict = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(sent[0], sent[1]); assertNull(conflict.choice!!.local.entries)
        assertEquals(2L, conflict.choice.remote.version); assertEquals(target, conflict.target)
        assertEquals(sent.first(), store.read(target).pending)
    }

    @Test fun accountChangeDuringDurableWriteKeepsIntentWithoutStartingOldAccountNetwork() = runTest(timeout = 10.seconds) {
        val store = Store(); var current = true; var calls = 0
        val account = NotesFixture.connection { calls++; error("Old account network") }
        val target = BookGlossaryTarget(account.accountKey, identity)
        val sync = ServerBookGlossarySync(backgroundScope, store) { testScheduler.currentTime }; sync.connect(account)
        store.beforeWrite = { current = false }
        val result = sync.adoptSnapshot("logout", target, account, DeviceBookGlossary(), remote(), snapshot(BookGlossarySnapshotPresence.DELETED)) { check(current) }
        assertTrue(result.isSuccess); assertNotNull(store.read(target).pending); runCurrent(); assertEquals(0, calls)
    }
}
