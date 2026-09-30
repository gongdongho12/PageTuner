package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.settings.ReaderSettings
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReaderPreferencesTest {
    @Test fun firstConnectionRequiresExplicitChoiceAndServerOverlayPreservesDeviceDefaults() = runTest(timeout = 10.seconds) {
        val store = Store(); val device = Device(); var puts = 0
        val remote = view(1, defaults.copy(fontSize = 30, pageMargin = 0, lineHeightPercent = 240, listMode = "scroll"))
        val client = NotesFixture.connection { if (it.method == "PUT") puts++; response(remote) }
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client)
        val first = sync.state.first { it.choice != null }
        assertFalse(first.enabled); assertNull(first.overlay); assertEquals(0, puts)
        sync.choose(first.session, false, first.choice!!)
        val selected = sync.state.first { it.enabled }
        assertEquals(remote.preferences, selected.overlay); assertEquals(defaults, device.readShared()); assertEquals(0, puts)
        sync.connect(null); val loggedOut = sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        assertNull(loggedOut.overlay); assertEquals(defaults, device.readShared())
    }

    @Test fun emptyAccountDoesNotReceiveDeviceValuesUntilExplicitChoice() = runTest(timeout = 10.seconds) {
        val store = Store(); val device = Device(); val writes = mutableListOf<ReaderPreferencesMutation>()
        val client = NotesFixture.connection { if (it.method == "GET") response(view()) else {
            writes += request(it); ack(it)
        } }
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client); val first = sync.state.first { it.choice != null }
        assertEquals(0L, first.choice!!.remote.version); assertTrue(writes.isEmpty())
        sync.choose(first.session, false, first.choice); runCurrent(); assertFalse(sync.state.value.enabled)
        sync.choose(first.session, true, first.choice)
        sync.state.first { it.enabled && it.phase == ReadingProgressPhase.Synced }
        assertEquals(1, writes.size); assertEquals(0L, writes.single().expectedVersion); assertEquals(defaults, writes.single().preferences)
    }

    @Test fun unsynchronizedEditsStayOnDeviceAndStaleInitialChoiceIsRejected() = runTest(timeout = 10.seconds) {
        val device = Device(); val client = NotesFixture.connection { response(view(1)) }; val store = Store()
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client); val shown = sync.state.first { it.choice != null }
        sync.edit(shown.session, ReaderPreferencesPatch(fontSize = 35, lineHeightPercent = 235))
        sync.state.first { it.choice?.local?.fontSize == 35 }
        sync.choose(shown.session, true, shown.choice!!)
        val rejected = sync.state.first { it.staleChoiceRejected }
        assertFalse(rejected.enabled); assertEquals(35, device.readShared().fontSize)
        assertNull(store.values.getValue(client.accountKey).pending)
    }

    @Test fun lostAckReusesMutationAndRapidPatchesSurviveLogoutAndProcessRestart() = runTest(timeout = 10.seconds) {
        val store = Store(); val device = Device(); val writes = mutableListOf<ReaderPreferencesMutation>()
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var fail = true
        val client = NotesFixture.connection { request -> if (request.method == "GET") response(view(1)) else {
            writes += request(request)
            if (fail) { started.complete(Unit); release.await(); throw SocketTimeoutException() }
            ack(request)
        } }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client); val initial = sync.state.first { it.enabled && it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, ReaderPreferencesPatch(fontSize = 32)); started.await()
        sync.edit(initial.session, ReaderPreferencesPatch(pageMargin = 46))
        sync.edit(initial.session, ReaderPreferencesPatch(lineHeightPercent = 230))
        store.await(client.accountKey) { it.queued?.lineHeightPercent == 230 }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        fail = false
        val restarted = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        restarted.connect(client)
        val done = restarted.state.first { it.phase == ReadingProgressPhase.Synced && it.overlay?.pageMargin == 46 }
        assertEquals(3, writes.size); assertEquals(writes[0], writes[1]); assertNotEquals(writes[1].mutationId, writes[2].mutationId)
        assertEquals(32, done.overlay!!.fontSize); assertEquals(230, done.overlay.lineHeightPercent)
        assertEquals(2L, writes[2].expectedVersion); assertEquals(defaults, device.readShared())
    }

    @Test fun conflictsRequireDisplayedLocalAndRemoteVersionsAndKeepLatestChoice() = runTest(timeout = 10.seconds) {
        val store = Store(); val device = Device(); var gets = 0
        val client = NotesFixture.connection { if (it.method == "GET") {
            gets++; response(view(if (gets == 1) 1 else 3, defaults.copy(fontSize = if (gets == 1) 18 else 30)))
        } else conflict(view(2, defaults.copy(fontSize = 26))) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client); val initial = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(initial.session, ReaderPreferencesPatch(fontSize = 22))
        val conflict = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        sync.edit(initial.session, ReaderPreferencesPatch(pageMargin = 40))
        store.await(client.accountKey) { it.queued?.pageMargin == 40 }
        sync.choose(initial.session, false, conflict.choice!!)
        sync.state.first { it.staleChoiceRejected }; assertEquals(40, sync.state.value.overlay!!.pageMargin)
        advanceTimeBy(30_001); runCurrent()
        val newer = sync.state.first { it.choice?.remote?.version == 3L }
        sync.choose(initial.session, false, conflict.choice)
        runCurrent(); assertEquals(22, sync.state.value.overlay!!.fontSize)
        sync.choose(initial.session, false, newer.choice!!)
        val done = sync.state.first { it.phase == ReadingProgressPhase.Synced && it.choice == null }
        assertEquals(30, done.overlay!!.fontSize); assertNull(store.values.getValue(client.accountKey).pending)
    }

    @Test fun choosingLocalAfterConflictCreatesFreshMutationAgainstDisplayedAccountVersion() = runTest(timeout = 10.seconds) {
        val store = Store(); val device = Device(); val writes = mutableListOf<ReaderPreferencesMutation>()
        val client = NotesFixture.connection { if (it.method == "GET") response(view(1)) else {
            writes += request(it)
            if (writes.size == 1) conflict(view(4, defaults.copy(fontSize = 33))) else ack(it)
        } }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(client); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(first.session, ReaderPreferencesPatch(fontSize = 25))
        val conflict = sync.state.first { it.choice != null }
        sync.choose(first.session, true, conflict.choice!!)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(4L, writes.last().expectedVersion); assertEquals(25, writes.last().preferences.fontSize)
        assertNotEquals(writes.first().mutationId, writes.last().mutationId)
    }

    @Test fun healthyPollingUpdatesOverlayWithoutCreatingMutationsAndIgnoresOldReads() = runTest(timeout = 10.seconds) {
        val store = Store(); var gets = 0; var puts = 0
        val client = NotesFixture.connection { if (it.method == "PUT") puts++
            gets++; response(view(if (gets == 1 || gets == 3) 1 else 2, defaults.copy(fontSize = if (gets == 2) 36 else 18))) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        advanceTimeBy(30_001); runCurrent(); sync.state.first { it.overlay?.fontSize == 36 }
        advanceTimeBy(30_001); runCurrent(); sync.state.first { gets >= 3 && it.phase == ReadingProgressPhase.Synced }
        assertEquals(36, sync.state.value.overlay!!.fontSize); assertEquals(0, puts)
    }

    @Test fun accountSwitchBlocksLateNetworkResultAndStaleSettingsAction() = runTest(timeout = 10.seconds) {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val store = Store()
        val alice = NotesFixture.connection("alice") { withContext(NonCancellable) { entered.complete(Unit); release.await(); response(view(9, defaults.copy(fontSize = 35))) } }
        val bob = NotesFixture.connection("bob") { response(view(2, defaults.copy(fontSize = 20))) }
        store.values[alice.accountKey] = DeviceReaderPreferences(true, view(1))
        store.values[bob.accountKey] = DeviceReaderPreferences(true, view(2, defaults.copy(fontSize = 20)))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(alice); entered.await(); val oldSession = sync.state.value.session
        sync.connect(bob); sync.state.first { it.accountKey == bob.accountKey && it.phase == ReadingProgressPhase.Synced }
        sync.edit(oldSession, ReaderPreferencesPatch(fontSize = 36)); release.complete(Unit); runCurrent()
        assertEquals(20, sync.state.value.overlay!!.fontSize); assertEquals(bob.accountKey, sync.state.value.accountKey)
        assertNull(store.values.getValue(bob.accountKey).pending)
    }

    @Test fun queuedEditFinishesAtomicStorageBeforeSwitchAndNeverMigratesToOtherAccount() = runTest(timeout = 10.seconds) {
        val entered = CompletableDeferred<Unit>(); val release = java.util.concurrent.CountDownLatch(1)
        val store = Store(); val device = Device()
        val alice = NotesFixture.connection("alice") { if (it.method == "GET") response(view(1)) else throw SocketTimeoutException() }
        val bob = NotesFixture.connection("bob") { response(view(2)) }
        store.values[alice.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, device) { testScheduler.currentTime }
        sync.connect(alice); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        store.beforeWrite = { key, value -> if (key == alice.accountKey && value.pending != null) { entered.complete(Unit); release.await(5, java.util.concurrent.TimeUnit.SECONDS) } }
        sync.edit(first.session, ReaderPreferencesPatch(fontSize = 28)); entered.await()
        sync.connect(bob); release.countDown()
        sync.state.first { it.accountKey == bob.accountKey && it.choice != null }
        assertEquals(28, store.values.getValue(alice.accountKey).pending!!.preferences.fontSize)
        assertNull(store.values.getValue(bob.accountKey).pending); assertFalse(sync.state.value.enabled)
        assertEquals(defaults, device.readShared())
    }

    @Test fun rateLimitIsPersistedAndHonoredByManualRetryAndReconnect() = runTest(timeout = 10.seconds) {
        val store = Store(); var calls = 0
        val client = NotesFixture.connection { calls++; if (calls == 1) TranslationStoreHttpResponse(429,
            mapOf("Retry-After" to listOf("120"))) else response(view(1)) }
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        sync.retry(); runCurrent(); assertEquals(1, calls)
        sync.connect(null); sync.state.first { it.phase == ReadingProgressPhase.Inactive }
        sync.connect(client); sync.state.first { it.phase == ReadingProgressPhase.RateLimited }
        advanceTimeBy(119_999); runCurrent(); assertEquals(1, calls)
        advanceTimeBy(2); runCurrent(); sync.state.first { it.choice != null }
        assertEquals(2, calls)
    }

    @Test fun failedStorageDoesNotSendMutationAndRetryRecoversSavedJournal() = runTest(timeout = 10.seconds) {
        val store = Store(); var writes = 0
        val client = NotesFixture.connection { if (it.method == "PUT") { writes++; ack(it) } else response(view(1)) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        store.failWrite = true; sync.edit(first.session, ReaderPreferencesPatch(fontSize = 32))
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }; assertEquals(0, writes)
        assertEquals(18, sync.state.value.overlay!!.fontSize)
        store.failWrite = false; sync.retry(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(sync.state.value.session, ReaderPreferencesPatch(fontSize = 32))
        sync.state.first { it.phase == ReadingProgressPhase.Synced && it.overlay?.fontSize == 32 }
        assertEquals(1, writes)
    }

    @Test fun initialStorageReadFailureCanBeRetriedWithoutResettingAccountData() = runTest(timeout = 10.seconds) {
        val store = Store(); val client = NotesFixture.connection { response(view(5)) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(5))
        store.failRead = true
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        store.failRead = false; sync.retry(); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertTrue(sync.state.value.enabled); assertEquals(5L, store.values.getValue(client.accountKey).remote!!.version)
    }

    @Test fun olderIdempotentAckRetainsLocalIntentAgainstNewerAccountSnapshot() = runTest(timeout = 10.seconds) {
        val store = Store(); val pending = ReaderPreferencesMutation(1, NotesFixture.noteId, defaults.copy(fontSize = 23))
        val client = NotesFixture.connection { if (it.method == "GET") response(view(4, defaults.copy(fontSize = 31))) else ack(it) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(4, defaults.copy(fontSize = 31)), pending,
            queued = defaults.copy(fontSize = 29))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); val conflict = sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(29, conflict.overlay!!.fontSize); assertEquals(4L, conflict.choice!!.remote.version)
        assertEquals(pending, store.values.getValue(client.accountKey).pending)
    }

    @Test fun authorizationFailureRetainsIntentWithoutAutomaticRetryLoop() = runTest(timeout = 10.seconds) {
        var calls = 0; val store = Store()
        val client = NotesFixture.connection { calls++; TranslationStoreHttpResponse(401) }
        val pending = ReaderPreferencesMutation(1, NotesFixture.noteId, defaults.copy(fontSize = 23))
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(1), pending)
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        advanceTimeBy(120_000); runCurrent(); assertEquals(1, calls)
        assertEquals(pending, store.values.getValue(client.accountKey).pending)
    }

    @Test fun exhaustedAccountVersionRefusesUnrepresentableMutationWithoutDamagingSettings() = runTest(timeout = 10.seconds) {
        var writes = 0; val store = Store()
        val client = NotesFixture.connection { if (it.method == "PUT") writes++; response(view(MaxReadingVersion)) }
        store.values[client.accountKey] = DeviceReaderPreferences(true, view(MaxReadingVersion))
        val sync = ServerReaderPreferencesSync(backgroundScope, store, Device()) { testScheduler.currentTime }
        sync.connect(client); val first = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        sync.edit(first.session, ReaderPreferencesPatch(fontSize = 25))
        sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        assertEquals(0, writes); assertEquals(defaults, sync.state.value.overlay)
        assertNull(store.values.getValue(client.accountKey).pending)
    }

    private class Device : ReaderPreferencesDevice {
        override val sharedChanges = MutableStateFlow(defaults)
        override suspend fun readShared() = sharedChanges.value
        override suspend fun updateShared(patch: ReaderPreferencesPatch) { sharedChanges.value = patch.apply(sharedChanges.value) }
    }
    private class Store : ServerReaderPreferencesStore {
        val values = ConcurrentHashMap<String, DeviceReaderPreferences>()
        private val changes = Channel<Unit>(Channel.UNLIMITED)
        var failRead = false; var failWrite = false
        var beforeWrite: ((String, DeviceReaderPreferences) -> Unit)? = null
        override fun read(accountKey: String) = if (failRead) error("Read unavailable") else values[accountKey] ?: DeviceReaderPreferences()
        override fun write(accountKey: String, value: DeviceReaderPreferences) {
            if (failWrite) error("Disk unavailable")
            beforeWrite?.invoke(accountKey, value)
            values[accountKey] = decodeDeviceReaderPreferences(JSONObject(encodeDeviceReaderPreferences(value, accountKey).toString()), accountKey)
            changes.trySend(Unit)
        }
        suspend fun await(key: String, predicate: (DeviceReaderPreferences) -> Boolean) {
            while (values[key]?.let(predicate) != true) changes.receive()
        }
    }
    private companion object {
        val defaults = ReaderSettings().sharedPreferences()
        fun view(version: Long = 0, preferences: SharedReaderPreferences = defaults) =
            ServerReaderPreferences(version, preferences.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
        fun response(view: ServerReaderPreferences) = TranslationStoreHttpResponse(200, body = ServerReaderPreferencesJson.encode(view).toString())
        fun request(request: TranslationStoreHttpRequest) = ServerReaderPreferencesJson.mutation(JSONObject(request.body!!))
        fun ack(request: TranslationStoreHttpRequest) = request(request).let { response(view(it.expectedVersion + 1, it.preferences)) }
        fun conflict(view: ServerReaderPreferences) = TranslationStoreHttpResponse(409, body = JSONObject().put("code", "READER_PREFERENCES_CONFLICT")
            .put("current", ServerReaderPreferencesJson.encode(view)).toString())
    }
}
