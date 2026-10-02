package com.dongholab.pagetuner.translation.sync

import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLibraryOrganizationTest {
    @Test fun emptyReadNeverUploadsDeviceDefaultsAndExplicitEmptySaveRetainsVersion() = runTest(timeout = 10.seconds) {
        val store = Store(); val writes = mutableListOf<LibraryOrganizationMutation>()
        var remote = view()
        val account = NotesFixture.connection { if (it.method == "PUT") { writes += request(it); remote = view(writes.last().expectedVersion + 1, writes.last().organization) }; response(remote) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account)
        val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertNull(first.base.local); assertEquals(0, writes.size)
        sync.edit(first.session, LibraryOrganization(), first.base)
        val done = sync.state.first { it.base.remote?.version == 1L && it.phase == ReadingProgressPhase.Synced }
        assertEquals(LibraryOrganization(), done.base.local); assertEquals(0L, writes.single().expectedVersion)
    }

    @Test fun uncertainAckKeepsMutationAndQueuedEditAfterCloseLogoutAndRestart() = runTest(timeout = 10.seconds) {
        val store = Store(); val writes = mutableListOf<LibraryOrganizationMutation>(); var fail = true
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var remote = view(1)
        val account = NotesFixture.connection { if (it.method == "GET") response(remote) else {
            writes += request(it)
            if (fail) { entered.complete(Unit); release.await(); throw SocketTimeoutException() }
            remote = view(writes.last().expectedVersion + 1, writes.last().organization); response(remote)
        } }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, sample.copy(folder = "First"), initial.base); entered.await()
        val accepted = sync.state.first { it.base.local?.folder == "First" }
        sync.edit(accepted.session, accepted.base.local!!.copy(tags = listOf("Later")), accepted.base)
        store.await(target(account)) { it.queued != null }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.close(); sync.state.first { it.target == null }; sync.connect(null)
        sync.state.first { it.accountKey == null }; fail = false
        val restarted = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        restarted.connect(account)
        store.await(target(account)) { it.pending == null && it.local?.tags == listOf("Later") }
        assertEquals(3, writes.size); assertEquals(writes[0], writes[1]); assertNotEquals(writes[1].mutationId, writes[2].mutationId)
        assertEquals(2L, writes.last().expectedVersion); assertNull(restarted.state.value.target)
    }

    @Test fun closedEditorStillRetriesConnectionAndPendingRecordsIndependently() = runTest(timeout = 10.seconds) {
        val store = Store(); var fail = true; var writes = 0
        val account = NotesFixture.connection { if (it.method == "PUT") { writes++; if (fail) throw SocketTimeoutException(); ack(it) } else response(view(1)) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val state = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(state.session, sample.copy(favorite = true), state.base)
        sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.close(); sync.state.first { it.target == null }; fail = false
        advanceTimeBy(15_001); runCurrent()
        store.await(target(account)) { it.pending == null && it.local?.favorite == true }
        assertEquals(2, writes)
    }

    @Test fun staleFormRetainsOriginalCasVersionAfterHealthyRemotePoll() = runTest(timeout = 10.seconds) {
        val store = Store(); var remote = view(1); val writes = mutableListOf<LibraryOrganizationMutation>()
        val account = NotesFixture.connection { if (it.method == "GET") response(remote) else { writes += request(it); conflict(remote) } }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val form = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        remote = view(2, sample.copy(folder = "Other device"))
        advanceTimeBy(30_001); runCurrent(); sync.state.first { it.base.remote?.version == 2L }
        sync.edit(form.session, sample.copy(folder = "My draft"), form.base)
        val conflicted = sync.state.first { it.choice != null }
        assertEquals(1L, writes.single().expectedVersion); assertEquals("My draft", conflicted.choice!!.local.folder)
        assertEquals("Other device", conflicted.choice.remote.organization!!.folder)
    }

    @Test fun staleLocalDraftAndStaleConflictChoiceCannotEraseMoreRecentIntent() = runTest(timeout = 10.seconds) {
        val store = Store(); var remote = view(1)
        val account = NotesFixture.connection { if (it.method == "GET") response(remote) else conflict(view(2, sample.copy(folder = "Remote"))) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, sample.copy(folder = "First"), initial.base)
        val first = sync.state.first { it.choice != null }
        sync.edit(first.session, first.base.local!!.copy(folder = "Second"), first.base)
        val latest = sync.state.first { it.base.local?.folder == "Second" }
        sync.edit(initial.session, sample.copy(folder = "Stale form"), initial.base)
        sync.state.first { it.staleActionRejected }; assertEquals("Second", sync.state.value.base.local!!.folder)
        sync.choose(latest.session, false, first.choice!!); runCurrent(); assertEquals("Second", sync.state.value.base.local!!.folder)
        remote = view(3, sample.copy(folder = "Remote later")); advanceTimeBy(30_001); runCurrent()
        val newer = sync.state.first { it.choice?.remote?.version == 3L }
        sync.choose(newer.session, false, latest.choice!!); runCurrent(); assertEquals("Second", sync.state.value.base.local!!.folder)
        sync.choose(newer.session, false, newer.choice!!)
        val done = sync.state.first { it.phase == ReadingProgressPhase.Synced && it.choice == null }
        assertEquals("Remote later", done.base.local!!.folder); assertNull(store.records.getValue(target(account).key).pending)
    }

    @Test fun explicitLocalChoiceRebasesWithFreshMutationAndClearUsesCas() = runTest(timeout = 10.seconds) {
        val store = Store(); var remote = view(1); val writes = mutableListOf<LibraryOrganizationMutation>()
        val account = NotesFixture.connection { if (it.method == "GET") response(remote) else {
            writes += request(it)
            if (writes.size == 1) { remote = view(4); conflict(remote) }
            else { remote = view(writes.last().expectedVersion + 1, writes.last().organization); response(remote) }
        } }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, LibraryOrganization(), initial.base)
        val conflicted = sync.state.first { it.choice != null }
        sync.choose(conflicted.session, true, conflicted.choice!!)
        val done = sync.state.first { it.phase == ReadingProgressPhase.Synced && it.base.remote?.version == 5L }
        assertEquals(LibraryOrganization(), done.base.local); assertEquals(4L, writes.last().expectedVersion)
        assertNotEquals(writes.first().mutationId, writes.last().mutationId)
    }

    @Test fun accountSwitchAndOldEditorActionsCannotCrossAccountBoundary() = runTest(timeout = 10.seconds) {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val alice = NotesFixture.connection("alice") { withContext(NonCancellable) { entered.complete(Unit); release.await(); response(view(9)) } }
        val bob = NotesFixture.connection("bob") { response(view(2, sample.copy(folder = "Bob"))) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(alice), alice); entered.await(); val old = sync.state.value
        sync.open(target(bob), bob); sync.state.first { it.accountKey == bob.accountKey && it.phase == ReadingProgressPhase.Synced }
        sync.edit(old.session, sample, old.base); release.complete(Unit); runCurrent()
        assertEquals("Bob", sync.state.value.base.local!!.folder); assertFalse(store.records.containsKey(target(alice).key))
        assertNull(store.records.getValue(target(bob).key).pending)
    }

    @Test fun rateLimitPersistsThroughReconnectionAndManualRetry() = runTest(timeout = 10.seconds) {
        val store = Store(); var calls = 0
        val account = NotesFixture.connection { calls++; if (calls == 1) TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("120"))) else response(view(1)) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        sync.retry(); runCurrent(); assertEquals(1, calls)
        sync.connect(null); sync.state.first { it.accountKey == null }
        sync.open(target(account), account); sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        advanceTimeBy(119_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(2); runCurrent(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(2, calls)
    }

    @Test fun deviceFailureNeverSendsUnjournaledIntentAndRetryRecoversPreviousValues() = runTest(timeout = 10.seconds) {
        val store = Store(); var writes = 0
        val account = NotesFixture.connection { if (it.method == "PUT") { writes++; ack(it) } else response(view(1)) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        store.fail = true; sync.edit(initial.session, sample.copy(folder = "Unstored"), initial.base)
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }; assertEquals(0, writes)
        assertEquals(sample, store.records.getValue(target(account).key).local)
        store.fail = false; sync.retry(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(sample, sync.state.value.base.local)
    }

    @Test fun olderIdempotentAckCannotRollbackNewerRemoteSnapshotOrLoseQueuedIntent() = runTest(timeout = 10.seconds) {
        val store = Store(); val account = NotesFixture.connection { ack(it) }
        val pending = LibraryOrganizationMutation(1, NotesFixture.noteId, sample)
        store.records[target(account).key] = DeviceLibraryOrganization(view(4), pending, sample.copy(folder = "Latest"))
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account)
        store.await(target(account)) { it.conflict != null }
        val record = store.records.getValue(target(account).key)
        assertEquals(4L, record.remote!!.version); assertEquals(pending, record.pending); assertEquals("Latest", record.local!!.folder)
    }

    @Test fun authorizationAndVersionExhaustionAreTerminalWithoutLosingIntent() = runTest(timeout = 10.seconds) {
        val store = Store(); var calls = 0
        val account = NotesFixture.connection { calls++; TranslationStoreHttpResponse(401) }
        val pending = LibraryOrganizationMutation(1, NotesFixture.noteId, sample)
        store.records[target(account).key] = DeviceLibraryOrganization(view(1), pending)
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        sync.close(); sync.state.first { it.target == null }
        advanceTimeBy(120_000); runCurrent(); assertEquals(1, calls); assertEquals(pending, store.records.getValue(target(account).key).pending)
        val full = NotesFixture.connection("full") { response(view(MaxReadingVersion)) }
        sync.open(target(full), full); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, LibraryOrganization(), initial.base)
        sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        assertNull(store.records.getValue(target(full).key).pending); assertEquals(sample, sync.state.value.base.local)
    }

    private class Store : ServerLibraryOrganizationStore {
        val records = ConcurrentHashMap<String, DeviceLibraryOrganization>()
        private val changes = Channel<Unit>(Channel.UNLIMITED)
        var fail = false
        override fun read(target: ServerReadingTarget) = records[target.key] ?: DeviceLibraryOrganization()
        override fun targets(accountKey: String) = records.keys.filter { it.startsWith("$accountKey:") }.map { it.split(':').let { p -> ServerReadingTarget(p[0], p[1], p[2]) } }
        override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) {
            if (fail) error("Device unavailable")
            records[target.key] = decodeLibraryOrganizationJournal(JSONObject(encodeLibraryOrganizationJournal(target, value).toString()), target)
            changes.trySend(Unit)
        }
        suspend fun await(target: ServerReadingTarget, predicate: (DeviceLibraryOrganization) -> Boolean) {
            while (records[target.key]?.let(predicate) != true) changes.receive()
        }
    }
    private companion object {
        val sample = LibraryOrganization("Books", listOf("Novel", "novel"), false)
        fun target(account: ServerReadingConnection) = ServerReadingTarget(account.accountKey, "ORIGINAL", NotesFixture.noteId)
        fun view(version: Long = 0, organization: LibraryOrganization = sample) = ServerLibraryOrganization("ORIGINAL", NotesFixture.noteId,
            version, organization.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
        fun request(request: TranslationStoreHttpRequest) = ServerLibraryOrganizationJson.mutation(JSONObject(request.body!!))
        fun response(value: ServerLibraryOrganization) = TranslationStoreHttpResponse(200, body = ServerLibraryOrganizationJson.encode(value).toString())
        fun ack(request: TranslationStoreHttpRequest) = request(request).let { response(view(it.expectedVersion + 1, it.organization)) }
        fun conflict(value: ServerLibraryOrganization) = TranslationStoreHttpResponse(409, body = JSONObject().put("code", "LIBRARY_ORGANIZATION_CONFLICT")
            .put("current", ServerLibraryOrganizationJson.encode(value)).toString())
    }
}
