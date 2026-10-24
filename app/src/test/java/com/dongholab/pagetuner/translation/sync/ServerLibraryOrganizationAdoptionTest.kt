package com.dongholab.pagetuner.translation.sync

import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLibraryOrganizationAdoptionTest {
    private val account = NotesFixture.connection { error("An adoption command must not PUT or open normal sync") }
    private val target = ServerReadingTarget(account.accountKey, "ORIGINAL", NotesFixture.recordId)
    private val sample = LibraryOrganization("읽을  책 é", listOf("é", "é", "Tag", "tag", "中  文"), true)
    private fun remote(version: Long = 0, value: LibraryOrganization = sample) = ServerLibraryOrganization(
        target.kind, target.recordId, version, value.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })

    private class Store : ServerLibraryOrganizationStore {
        var record: DeviceLibraryOrganization? = null
        var commits = 0
        var beforeCommit: () -> Unit = {}
        var afterCommit: () -> Unit = {}
        override fun read(target: ServerReadingTarget) = record ?: DeviceLibraryOrganization()
        override fun readNullable(target: ServerReadingTarget) = record
        override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) { error("Unconditional write forbidden") }
        override fun targets(accountKey: String) = emptyList<ServerReadingTarget>()
        @Synchronized override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
            value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
            if (record != expected) return false
            beforeCommit(); guard()
            record = decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, value), target)
            commits++; afterCommit(); return true
        }
    }

    private fun actor(store: Store, dispatcher: CoroutineDispatcher, guard: () -> Unit = {},
        canonical: suspend () -> Unit = {}, query: suspend () -> ServerLibraryOrganization = { remote() }) =
        ServerLibraryOrganizationAdoption(store, target, account, guard, canonical, query, dispatcher)

    @Test fun comparisonIsGetOnlyAndExplicitEmptyCreatesFreshVersionZeroIntent() = runTest {
        val store = Store(); var gets = 0
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = { gets++; remote() })
        val shown = actor.compare()
        assertNull(shown.record); assertNull(store.record); assertEquals(1, gets)
        assertTrue(actor.adopt(shown, LibraryOrganization()))
        val pending = store.record!!.pending!!
        assertEquals(0L, pending.expectedVersion); assertEquals(LibraryOrganization(), pending.organization)
        assertEquals(pending.mutationId, java.util.UUID.fromString(pending.mutationId).toString())
        assertEquals(2, gets); assertEquals(1, store.commits)
        assertTrue(runCatching { actor.adopt(shown, sample) }.isFailure)
    }

    @Test fun sameValueStillCreatesNewIntentAndPreservesRetryDeadlineAndExactUnicode() = runTest {
        val store = Store(); store.record = DeviceLibraryOrganization(remote(3), retryAfterUntil = 999_999)
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = { remote(3) })
        val shown = actor.compare()
        assertTrue(actor.adopt(shown, sample))
        assertEquals(sample, store.record!!.pending!!.organization)
        assertEquals(3L, store.record!!.pending!!.expectedVersion)
        assertEquals(999_999L, store.record!!.retryAfterUntil)
    }

    @Test fun twoActorsComparingMissingJournalHaveOneWinner() = runTest {
        val store = Store(); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = actor(store, dispatcher); val second = actor(store, dispatcher)
        val a = first.compare(); val b = second.compare()
        val results = awaitAll(async { runCatching { first.adopt(a, sample) }.getOrDefault(false) },
            async { runCatching { second.adopt(b, LibraryOrganization()) }.getOrDefault(false) })
        assertEquals(1, results.count { it }); assertEquals(1, store.commits)
    }

    @Test fun missingVersusExistingEmptyAndChangedDeadlineAreNotEquivalent() = runTest {
        for (replacement in listOf(DeviceLibraryOrganization(), DeviceLibraryOrganization(retryAfterUntil = 19))) {
            val store = Store(); val actor = actor(store, StandardTestDispatcher(testScheduler))
            val shown = actor.compare(); store.record = replacement
            assertTrue(runCatching { actor.adopt(shown, sample) }.isFailure)
            assertEquals(replacement, store.record); assertEquals(0, store.commits)
        }
    }

    @Test fun pendingQueuedAndConflictArePreservedWithoutNetwork() = runTest {
        val mutation = LibraryOrganizationMutation(0, NotesFixture.noteId, sample)
        for (record in listOf(DeviceLibraryOrganization(pending = mutation),
            DeviceLibraryOrganization(pending = mutation, queued = LibraryOrganization()),
            DeviceLibraryOrganization(pending = mutation, conflict = remote(1)))) {
            val store = Store(); store.record = record
            val actor = actor(store, StandardTestDispatcher(testScheduler), query = { error("No GET for dirty journal") })
            assertTrue(runCatching { actor.compare() }.isFailure)
            assertEquals(record, store.record); assertEquals(0, store.commits)
        }
    }

    @Test fun higherServerResponseRequiresNewComparisonAndStillDoesNotWrite() = runTest {
        val store = Store(); var value = remote(1)
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        val shown = actor.compare(); value = remote(2)
        assertTrue(runCatching { actor.adopt(shown, sample) }.isFailure)
        assertNull(store.record)
        assertTrue(actor.adopt(actor.compare(), sample))
        assertEquals(2L, store.record!!.pending!!.expectedVersion)
    }

    @Test fun lowerAndSameVersionChangedContentOrTimePermanentlyExpireActor() = runTest {
        for (bad in listOf(remote(1), remote(2, LibraryOrganization()), remote(2).copy(updatedAt = "2026-09-17T00:00:00Z"))) {
            val store = Store(); var value = remote(2)
            val actor = actor(store, StandardTestDispatcher(testScheduler), query = { value })
            val shown = actor.compare(); value = bad
            assertTrue(runCatching { actor.adopt(shown, sample) }.isFailure)
            value = remote(3)
            assertTrue(runCatching { actor.compare() }.isFailure)
            assertNull(store.record)
        }
    }

    @Test fun retainedJournalHighWaterSurvivesMissingRemoteAndAllowsFreshGetAboveOlderJournal() = runTest {
        val store = Store(); store.record = DeviceLibraryOrganization(remote(2))
        var value = remote(3)
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        actor.compare() // A stale journal can legitimately lag the new GET.
        store.record = null; value = remote(1)
        assertTrue(runCatching { actor.compare() }.isFailure)
        assertNull(store.record)
    }

    @Test fun closeWhileGetIsHeldDiscardsEvenNonCancellableLateResponse() = runTest {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); remote() }
        })
        val waiting = async { runCatching { actor.compare() } }
        entered.await(); actor.close(); release.complete(Unit)
        assertTrue(waiting.await().isFailure); assertNull(store.record)
        assertTrue(runCatching { actor.compare() }.isFailure)
    }

    @Test fun snapshotListsAreDetachedBeforeHeldGetAndDisplayCannotAlterCommand() = runTest {
        val store = Store(); val tags = sample.tags.toMutableList()
        store.record = DeviceLibraryOrganization(remote(1, sample.copy(tags = tags)))
        var hold = false; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = {
            if (hold) { entered.complete(Unit); release.await() }; remote(1)
        })
        val shown = actor.compare()
        (shown.remote.organization!!.tags as MutableList).clear()
        val input = sample.tags.toMutableList(); hold = true
        val saving = async { actor.adopt(shown, sample.copy(tags = input)) }
        entered.await(); input.clear(); release.complete(Unit)
        assertTrue(saving.await()); assertEquals(sample.tags, store.record!!.pending!!.organization.tags)
        tags.clear(); assertEquals(sample.tags, store.record!!.remote!!.organization!!.tags)
    }

    @Test fun finalCanonicalAndLastAbortableGuardCanRejectWithoutErasingJournal() = runTest {
        for (failure in listOf("canonical", "guard")) {
            val store = Store(); var current = true; var canonical = true
            val actor = actor(store, StandardTestDispatcher(testScheduler), guard = { check(current) }, canonical = { check(canonical) })
            val shown = actor.compare()
            if (failure == "canonical") canonical = false else store.beforeCommit = { current = false }
            assertTrue(runCatching { actor.adopt(shown, sample) }.isFailure)
            assertNull(store.record); assertEquals(0, store.commits)
        }
    }

    @Test fun postCommitCloseAndDispatcherCancellationDoNotReportRollback() = runTest {
        val store = Store(); val actor = actor(store, StandardTestDispatcher(testScheduler))
        val shown = actor.compare()
        val result = CompletableDeferred<Boolean>()
        lateinit var saving: Job
        store.afterCommit = { actor.close(); saving.cancel() }
        saving = launch { result.complete(actor.adopt(shown, sample)) }
        assertTrue(result.await()); saving.join()
        assertNotNull(store.record!!.pending); assertEquals(1, store.commits)
    }

    @Test fun closeInsideLastGuardAndCancellationBeforeCommitRejectIntent() = runTest {
        for (cancel in listOf(false, true)) {
            val store = Store(); var atCommit = false
            lateinit var actor: ServerLibraryOrganizationAdoption
            lateinit var saving: Deferred<Boolean>
            actor = actor(store, StandardTestDispatcher(testScheduler), guard = {
                if (atCommit) { if (cancel) saving.cancel() else actor.close() }
            })
            val shown = actor.compare(); store.beforeCommit = { atCommit = true }
            saving = async { runCatching { actor.adopt(shown, sample) }.getOrDefault(false) }
            assertFalse(runCatching { saving.await() }.getOrDefault(false))
            assertNull(store.record); assertEquals(0, store.commits)
        }
    }

    @Test fun maxVersionAndInvalidScopeCannotCommit() = runTest {
        val store = Store(); var value = remote(MaxReadingVersion)
        val actor = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        assertTrue(runCatching { actor.adopt(actor.compare(), sample) }.isFailure)
        value = remote().copy(recordId = NotesFixture.otherId)
        assertTrue(runCatching { actor.compare() }.isFailure)
        value = remote()
        assertTrue(runCatching { actor.compare() }.isFailure); assertNull(store.record)
    }

    @Test fun realHttpAdapterUsesOnlyDirectGetAndNetworkFailureDoesNotPoison() = runTest {
        val store = Store(); var calls = 0; var fail = true
        val connection = NotesFixture.connection { request ->
            assertEquals("GET", request.method); calls++
            if (fail) throw SocketTimeoutException()
            NotesFixture.response(ServerLibraryOrganizationJson.encode(remote()))
        }
        val actor = ServerLibraryOrganizationAdoption(store, target, connection, {}, {}, io = StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { actor.compare() }.isFailure)
        fail = false
        assertTrue(actor.adopt(actor.compare(), sample)); assertEquals(3, calls)
    }

    @Test fun httpInvalidResponsePermanentlyPoisonsBeforeNextQuery() = runTest {
        val store = Store(); var calls = 0
        val connection = NotesFixture.connection { calls++; TranslationStoreHttpResponse(200, body = "{}") }
        val actor = ServerLibraryOrganizationAdoption(store, target, connection, {}, {}, io = StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { actor.compare() }.isFailure)
        assertTrue(runCatching { actor.compare() }.isFailure); assertEquals(1, calls); assertNull(store.record)
    }
}
