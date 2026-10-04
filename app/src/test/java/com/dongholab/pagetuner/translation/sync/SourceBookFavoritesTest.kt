package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SourceBookFavoritesTest {
    private val account = "a".repeat(64)
    private val identity = SourceFavoriteIdentity("wtr-lab", "https://wtr-lab.com/novel/42/book")
    private val desired = SourceFavoriteDesired(identity, false, SourceBookFavoriteMetadata("Book", listOf("Author"), "en", "https://wtr-lab.com/en/novel/42/book"))
    private fun item(version: Long = 1, value: SourceFavoriteDesired = desired, revision: Long = version) =
        SourceBookFavorite(value.identity, version, revision, value.deleted, value.book, NotesFixture.time.takeIf { version > 0 })
    private class Store : SourceBookFavoritesStore {
        val values = java.util.concurrent.ConcurrentHashMap<String, DeviceSourceFavorites>()
        var fail = false
        override fun read(accountKey: String) = values[accountKey] ?: DeviceSourceFavorites()
        override fun write(accountKey: String, value: DeviceSourceFavorites) { if (fail) error("disk full"); values[accountKey] = value }
    }
    private class Remote : SourceBookFavoritesRemote {
        var items = emptyList<SourceBookFavorite>()
        val writes = mutableListOf<SourceFavoriteMutation>()
        var push: suspend (SourceFavoriteMutation) -> SourceBookFavorite = { error("unexpected PUT") }
        var pull: (suspend (Long, Int, Long?) -> SourceFavoritesPage)? = null
        override suspend fun sourceBookFavorites(afterRevision: Long, limit: Int, untilRevision: Long?) = pull?.invoke(afterRevision, limit, untilRevision)
            ?: SourceFavoritesPage(items.filter { it.changeRevision > afterRevision }, maxOf(afterRevision, items.maxOfOrNull { it.changeRevision } ?: 0),
                maxOf(afterRevision, items.maxOfOrNull { it.changeRevision } ?: 0), false)
        override suspend fun saveSourceBookFavorite(mutation: SourceFavoriteMutation): SourceBookFavorite { writes += mutation; return push(mutation) }
    }

    @Test fun loginNeverUploadsDeviceFavoritesAndAdoptionRequiresExplicitSelection() = runTest(timeout = 10.seconds) {
        val store = Store(); val api = Remote(); api.push = { item(it.expectedVersion + 1, it.desired) }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); val initial = sync.state.first { it.loaded }
        assertTrue(api.writes.isEmpty()); assertTrue(initial.rows.isEmpty())
        sync.change(initial.session, desired)
        val done = sync.state.first { it.rows.size == 1 && it.pendingKeys.isEmpty() }
        assertEquals(desired, done.rows.single().desired); assertEquals(0L, api.writes.single().expectedVersion)
        assertEquals(0L, store.read(account).afterRevision) // A PUT must never advance the feed cursor.
    }

    @Test fun reconnectPreservesUncertainMutationAndQueuedDeleteWithoutLosingOtherDeviceChanges() = runTest(timeout = 10.seconds) {
        val store = Store(); val api = Remote(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.push = { entered.complete(Unit); release.await(); throw TranslationStoreException(TranslationStoreFailure.TIMEOUT) }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); val first = sync.state.first { it.loaded }; sync.change(first.session, desired); entered.await()
        val local = sync.state.first { it.rows.isNotEmpty() }
        sync.change(local.session, desired.copy(deleted = true, book = null), local.rows.single())
        sync.state.first { it.rows.isEmpty() && it.pendingKeys.isNotEmpty() }
        release.complete(Unit); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null, null); sync.state.first { it.accountKey == null }
        val other = desired.copy(identity = SourceFavoriteIdentity("novelbuddy", "other"))
        api.items = listOf(item(1, other, 1))
        api.push = { item(it.expectedVersion + 1, it.desired, it.expectedVersion + 2) }
        val restarted = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        restarted.connect(account, api)
        val done = restarted.state.first { it.loaded && it.pendingKeys.isEmpty() }
        assertEquals(listOf(other), done.rows.map { it.desired })
        assertEquals(3, api.writes.size); assertEquals(api.writes[0], api.writes[1]); assertTrue(api.writes.last().desired.deleted)
        assertEquals(1L, store.read(account).afterRevision); assertEquals(2L, store.read(account).items.getValue(identity.key).version)
    }

    @Test fun adoptionOverExistingAccountBookAndTombstoneRequiresVersionedConflictChoice() = runTest(timeout = 10.seconds) {
        val store = Store(); val api = Remote(); val deleted = item(4, desired.copy(deleted = true, book = null))
        api.items = listOf(deleted); api.push = { item(it.expectedVersion + 1, it.desired) }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); val first = sync.state.first { it.loaded }; sync.change(first.session, desired)
        val conflict = sync.state.first { it.conflicts.isNotEmpty() }
        assertTrue(api.writes.isEmpty()); assertEquals(deleted, conflict.conflicts.single().remote)
        sync.resolve(conflict.session, true, conflict.conflicts.single())
        sync.state.first { it.pendingKeys.isEmpty() && it.rows.isNotEmpty() }
        assertEquals(4L, api.writes.single().expectedVersion)
    }

    @Test fun staleAccountActionsAndLateNetworkResultsCannotLeakAcrossAccounts() = runTest(timeout = 10.seconds) {
        val store = Store(); val api = Remote(); val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        api.pull = { _, _, _ -> withContext(NonCancellable) { started.complete(Unit); release.await() }; SourceFavoritesPage(listOf(item()), 1, 1, false) }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); started.await(); val session = sync.state.value.session
        sync.connect("b".repeat(64), Remote()); sync.state.first { it.accountKey == "b".repeat(64) && it.loaded }
        release.complete(Unit); sync.change(session, desired); runCurrent()
        assertTrue(sync.state.value.rows.isEmpty()); assertTrue(store.read("b".repeat(64)).pending.isEmpty())
    }

    @Test fun staleConflictChoiceCannotEraseQueuedEdit() = runTest(timeout = 10.seconds) {
        val api = Remote(); api.items = listOf(item()); val sync = SourceBookFavoritesSync(backgroundScope, Store()) { testScheduler.currentTime }
        sync.connect(account, api); val first = sync.state.first { it.loaded }
        val local = desired.copy(book = desired.book!!.copy(title = "New")); sync.change(first.session, local)
        val before = sync.state.first { it.conflicts.isNotEmpty() }
        sync.change(before.session, local.copy(deleted = true, book = null), before.rows.single())
        sync.state.first { it.rows.isEmpty() }
        sync.resolve(before.session, false, before.conflicts.single())
        val latest = sync.state.first { it.staleActionRejected }
        assertTrue(latest.conflicts.single().local.deleted); assertTrue(api.writes.isEmpty())
    }

    @Test fun failedJournalWriteDoesNotSendAndRetryReloadsAtomicSnapshot() = runTest(timeout = 10.seconds) {
        val api = Remote(); val store = Store(); val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); val first = sync.state.first { it.loaded }; store.fail = true
        sync.change(first.session, desired); sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        assertTrue(api.writes.isEmpty()); assertTrue(store.read(account).pending.isEmpty())
        store.fail = false; sync.retry(); val reloaded = sync.state.first { it.loaded && it.phase == ReadingProgressPhase.Synced }
        assertTrue(reloaded.rows.isEmpty())
    }

    @Test fun feedUsesStableWatermarkAndMergesHistoricalRevisionsWithoutRollingBackNewerAck() = runTest(timeout = 10.seconds) {
        val api = Remote(); val store = Store()
        store.values[account] = DeviceSourceFavorites(items = mapOf(identity.key to item(3, revision = 4)))
        val requests = mutableListOf<Pair<Long, Long?>>()
        api.pull = { after, _, until ->
            requests += after to until
            if (after == 0L) SourceFavoritesPage(listOf(item(1)), 1, 4, true)
            else SourceFavoritesPage(listOf(item(2, revision = 2), item(3, revision = 4)), 4, 4, false)
        }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api); val loaded = sync.state.first { it.loaded }
        assertEquals(listOf(0L to null, 1L to 4L), requests); assertEquals(3L, loaded.rows.single().version)
        assertEquals(4L, store.read(account).afterRevision)
    }

    @Test fun initializedAccountRemainsEditableAfterOfflineRestart() = runTest(timeout = 10.seconds) {
        val store = Store(); store.values[account] = DeviceSourceFavorites(items = mapOf(identity.key to item()), afterRevision = 1, initialized = true)
        val api = Remote(); api.pull = { _, _, _ -> throw TranslationStoreException(TranslationStoreFailure.NETWORK) }
        val sync = SourceBookFavoritesSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account, api)
        val cached = sync.state.first { it.phase == ReadingProgressPhase.Offline }
        assertTrue(cached.loaded); assertEquals(1, cached.rows.size)
        sync.change(cached.session, desired.copy(deleted = true, book = null), cached.rows.single())
        // Atomic storage uses Dispatchers.IO. While it completes, runTest may advance the
        // retry timer, briefly publishing Pending before the failed pull restores Offline.
        val queued = sync.state.first { it.rows.isEmpty() && it.pendingKeys.isNotEmpty() && it.phase == ReadingProgressPhase.Offline }
        assertEquals(ReadingProgressPhase.Offline, queued.phase)
        assertTrue(store.read(account).pending.getValue(identity.key).desired.deleted); assertTrue(api.writes.isEmpty())
    }

    @Test fun journalRoundTripsExactIdsPendingTombstonesAndRejectsWrongAccount() {
        val mutation = SourceFavoriteMutation(desired, 1, NotesFixture.noteId)
        val value = DeviceSourceFavorites(mapOf(identity.key to item()), mapOf(identity.key to mutation),
            mapOf(identity.key to desired.copy(deleted = true, book = null)), mapOf(identity.key to item(2)), 2, 15000)
        val json = encodeSourceFavoritesJournal(account, value)
        assertEquals(value, decodeSourceFavoritesJournal(account, JSONObject(json.toString())))
        assertThrows(IllegalArgumentException::class.java) { decodeSourceFavoritesJournal("b".repeat(64), json) }
        assertThrows(IllegalArgumentException::class.java) { decodeSourceFavoritesJournal(account, JSONObject(json.toString()).put("pending", JSONArray())) }
        assertNotEquals(SourceFavoriteIdentity("a:b", "c").key, SourceFavoriteIdentity("a", "b:c").key)
    }

    @Test fun accountBookRouteIdentitySeparatesProvidersWithoutChangingOriginalIds() {
        val first = requireNotNull(desired.remoteBook())
        val otherProvider = desired.copy(identity = desired.identity.copy(providerId = "other-provider"))
        val second = requireNotNull(otherProvider.remoteBook())
        assertNotEquals(first.identity, second.identity)
        assertEquals(desired.identity.bookId, first.sourceBookId)
        assertEquals(desired.identity.providerId, first.sourceProviderId)
        assertEquals(desired, first.sourceFavorite())
        assertEquals(otherProvider, second.sourceFavorite())
    }
}
