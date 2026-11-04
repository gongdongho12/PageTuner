package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Scoped GET/journal tests use injected observations/transport, not a physical account or device. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshServerReadingProgressReaderTest {
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val time = "2026-11-02T00:00:00Z"
    private val anchor = ServerReadingAnchor(" P:é e\u0301 🌏  ", 17)
    private val mutation = ServerReadingMutation(3, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor)
    private fun view(version: Long = 3, scope: ServerReadingTarget = target) = ServerReadingProgress(
        scope.kind, scope.recordId, version, anchor.takeIf { version > 0 }, time.takeIf { version > 0 })
    private fun observation(value: ServerReadingProgress = view(), scope: ServerReadingTarget = target) =
        ReadingProgressJournalObservation(scope, DeviceReadingProgress(remote = value))
    private fun dirty() = listOf(
        DeviceReadingProgress(remote = view(), pending = mutation, retryAfterUntil = 999_999),
        DeviceReadingProgress(remote = view(), pending = mutation, queuedAnchor = anchor.copy(characterOffset = 0), retryAfterUntil = 999_999),
        DeviceReadingProgress(remote = view(), pending = mutation, conflict = view(4), retryAfterUntil = 999_999),
    )

    private open class LegacyStore : ServerReadingProgressStore {
        var legacyCalls = 0
        protected fun forbidden(): Nothing { legacyCalls++; error("Legacy read/write/pending must not be used") }
        override fun read(document: ServerReadingDocument): DeviceReadingProgress = forbidden()
        override fun write(document: ServerReadingDocument, value: DeviceReadingProgress): Unit = forbidden()
        override fun pending(accountKey: String): List<ServerReadingTarget> = forbidden()
        override fun read(target: ServerReadingTarget): DeviceReadingProgress = forbidden()
        override fun write(target: ServerReadingTarget, value: DeviceReadingProgress): Unit = forbidden()
    }
    private class Store : LegacyStore() {
        var rows = listOf<ReadingProgressJournalObservation>()
        var calls = 0
        var onObserve: (Int) -> Unit = {}
        var failure: Exception? = null
        override fun observeAliases(target: ServerReadingTarget): List<ReadingProgressJournalObservation> {
            calls++; failure?.let { throw it }; onObserve(calls)
            return rows.toList()
        }
    }
    private fun connection(transport: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse = { error("Unexpected HTTP") }) =
        ServerReadingConnection(target.accountKey, HttpTranslationStore("https://reader.example",
            TranslationStoreBasicAuth("reader", "password"), TranslationStoreHttpTransport(transport)))
    private fun reader(store: ServerReadingProgressStore, io: CoroutineDispatcher,
        current: () -> Unit = {}, query: suspend () -> ServerReadingProgress = { view() }) =
        FreshServerReadingProgressReader(store, target, connection(), current, query, io)
    private suspend fun fail(reason: FreshReadingProgressFailure, block: suspend () -> Unit) {
        val error = runCatching { block() }.exceptionOrNull()
        assertTrue("Expected $reason but received $error", error is FreshReadingProgressException)
        assertEquals(reason, (error as FreshReadingProgressException).failure)
    }
    private fun terminal(reader: FreshServerReadingProgressReader) {
        val error = assertThrows(FreshReadingProgressException::class.java) { reader.assertCurrent() }
        assertEquals(FreshReadingProgressFailure.STALE, error.failure)
    }

    @Test fun defaultProductionQueryUsesOnlyTheCapturedProgressGet() = runTest {
        val requests = mutableListOf<TranslationStoreHttpRequest>(); val store = Store()
        val fixed = connection { request -> requests += request; TranslationStoreHttpResponse(200,
            body = ServerReadingProgressJson.encode(view()).toString()) }
        val reader = FreshServerReadingProgressReader(store, target, fixed, {}, io = StandardTestDispatcher(testScheduler))
        val actual = reader.read(); reader.checkReady(actual); reader.checkBeforeWrite(actual)
        assertEquals(view(), actual)
        assertEquals(listOf("GET"), requests.map { it.method })
        assertTrue(requests.single().url.endsWith("/reading-progress/ORIGINAL/${target.recordId}"))
        assertEquals(0, store.legacyCalls)
    }

    @Test fun absenceAndExactUnicodeAreReturnedWithoutInventingDevicePosition() = runTest {
        for (value in listOf(view(0), view())) {
            val store = Store(); val reader = reader(store, StandardTestDispatcher(testScheduler), query = { value })
            assertEquals(value, reader.read()); reader.checkReady(value); reader.checkBeforeWrite(value)
            assertTrue(store.rows.isEmpty()); assertEquals(0, store.legacyCalls)
        }
    }

    @Test fun readinessRequiresThisReadersSuccessfulGetAndDoesNotStartOne() = runTest {
        val store = Store(); var gets = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { gets++; view() })
        fail(FreshReadingProgressFailure.NOT_READ) { reader.checkReady(view()) }
        fail(FreshReadingProgressFailure.NOT_READ) { reader.checkBeforeWrite(view()) }
        reader.assertCurrent(); assertEquals(0, gets)
        val actual = reader.read(); reader.checkReady(actual); assertEquals(1, gets)
    }

    @Test fun uppercaseAndMixedAliasesObserveMaximumIndependentOfDirectoryOrder() = runTest {
        val upper = target.copy(recordId = target.recordId.uppercase())
        val mixed = target.copy(recordId = target.recordId.replaceFirst('a', 'A'))
        val rows = listOf(observation(view(2)), observation(view(8, upper), upper), observation(view(5, mixed), mixed))
        for (ordered in listOf(rows, rows.reversed())) {
            val store = Store().also { it.rows = ordered }
            val reader = reader(store, StandardTestDispatcher(testScheduler), query = { view(8) })
            val actual = reader.read(); reader.checkReady(actual); reader.checkBeforeWrite(actual)
            assertEquals(view(8), actual); assertEquals(ordered, store.rows); assertEquals(0, store.legacyCalls)
        }
    }

    @Test fun uuidCaseDifferencesAloneDoNotChangeSameVersionSnapshotContent() = runTest {
        val upper = target.copy(recordId = target.recordId.uppercase())
        val store = Store().also { it.rows = listOf(observation(), observation(view(3, upper), upper)) }
        val reader = reader(store, StandardTestDispatcher(testScheduler))
        assertEquals(view(), reader.read()); reader.checkReady(view()); assertEquals(0, store.legacyCalls)
    }

    @Test fun sameVersionDifferentAliasAnchorsAreNotHiddenByOrdering() = runTest {
        val upper = target.copy(recordId = target.recordId.uppercase())
        val rows = listOf(observation(), observation(view(3, upper).copy(anchor = anchor.copy(characterOffset = 2)), upper))
        for (order in listOf(rows, rows.reversed())) {
            val store = Store().also { it.rows = order }; var gets = 0
            val reader = reader(store, StandardTestDispatcher(testScheduler), query = { gets++; view() })
            fail(FreshReadingProgressFailure.STORAGE) { reader.read() }
            assertEquals(0, gets); terminal(reader); assertEquals(order, store.rows)
        }
    }

    @Test fun sameVersionDifferentAliasTimestampsAreRejectedBeforeGet() = runTest {
        val upper = target.copy(recordId = target.recordId.uppercase())
        val store = Store().also { it.rows = listOf(observation(), observation(view(3, upper).copy(updatedAt = "2026-11-02T00:00:01Z"), upper)) }
        val reader = reader(store, StandardTestDispatcher(testScheduler))
        fail(FreshReadingProgressFailure.STORAGE) { reader.read() }; terminal(reader)
    }

    @Test fun preGetPendingQueuedAndConflictArePreservedAndNeverFlushed() = runTest {
        for (record in dirty()) {
            val rows = listOf(ReadingProgressJournalObservation(target, record)); val store = Store().also { it.rows = rows }; var gets = 0
            val reader = reader(store, StandardTestDispatcher(testScheduler), query = { gets++; view(4) })
            fail(FreshReadingProgressFailure.PENDING) { reader.read() }
            assertEquals(0, gets); assertEquals(rows, store.rows); assertEquals(0, store.legacyCalls); reader.assertCurrent()
        }
    }

    @Test fun postGetPendingQueuedAndConflictDiscardResponseWithoutChangingIntent() = runTest {
        for (record in dirty()) {
            val store = Store(); val rows = listOf(ReadingProgressJournalObservation(target, record)); var gets = 0
            val reader = reader(store, StandardTestDispatcher(testScheduler), query = { gets++; store.rows = rows; view(4) })
            fail(FreshReadingProgressFailure.PENDING) { reader.read() }
            assertEquals(1, gets); assertEquals(rows, store.rows); assertEquals(0, store.legacyCalls)
        }
    }

    @Test fun finalReadinessRejectsLaterPendingQueuedAndConflictWithoutMutation() = runTest {
        for (record in dirty()) {
            val store = Store(); val reader = reader(store, StandardTestDispatcher(testScheduler), query = { view(4) })
            val actual = reader.read(); store.rows = listOf(ReadingProgressJournalObservation(target, record))
            fail(FreshReadingProgressFailure.PENDING) { reader.checkReady(actual) }
            fail(FreshReadingProgressFailure.PENDING) { reader.checkBeforeWrite(actual) }
            assertEquals(record, store.rows.single().record); assertEquals(0, store.legacyCalls)
        }
    }

    @Test fun journalPeakSurvivesNetworkFailureAndDeletion() = runTest {
        val store = Store().also { it.rows = listOf(observation(view(8))) }; var gets = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = {
            gets++; if (gets == 1) throw TranslationStoreException(TranslationStoreFailure.NETWORK) else view(7)
        })
        val first = runCatching { reader.read() }.exceptionOrNull()
        assertEquals(TranslationStoreFailure.NETWORK, (first as TranslationStoreException).failure); reader.assertCurrent()
        store.rows = emptyList()
        fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }
        assertEquals(2, gets); terminal(reader)
    }

    @Test fun journalPeakIsRecordedBeforePendingRejectionAndRetainedAfterClear() = runTest {
        val store = Store().also { it.rows = listOf(ReadingProgressJournalObservation(target,
            DeviceReadingProgress(remote = view(8), pending = mutation))) }
        var gets = 0; val reader = reader(store, StandardTestDispatcher(testScheduler), query = { gets++; view(7) })
        fail(FreshReadingProgressFailure.PENDING) { reader.read() }; assertEquals(0, gets)
        store.rows = emptyList()
        fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; assertEquals(1, gets); terminal(reader)
    }

    @Test fun conflictPeakIsNotLostWhenTheConflictingJournalIsRemoved() = runTest {
        val store = Store().also { it.rows = listOf(ReadingProgressJournalObservation(target,
            DeviceReadingProgress(remote = view(3), pending = mutation, conflict = view(9)))) }
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { view(8) })
        fail(FreshReadingProgressFailure.PENDING) { reader.read() }; store.rows = emptyList()
        fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; terminal(reader)
    }

    @Test fun receivedApiPeakSurvivesPostGetPendingRejection() = runTest {
        val store = Store(); var gets = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = {
            gets++; if (gets == 1) { store.rows = listOf(ReadingProgressJournalObservation(target, DeviceReadingProgress(pending = mutation))); view(8) } else view(7)
        })
        fail(FreshReadingProgressFailure.PENDING) { reader.read() }; store.rows = emptyList()
        fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; terminal(reader)
    }

    @Test fun aNewerSuccessfulGetCannotApprovePreparedOlderBytes() = runTest {
        val store = Store(); var next = view(3)
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { next })
        val old = reader.read(); next = view(4); val fresh = reader.read()
        fail(FreshReadingProgressFailure.STALE_VIEW) { reader.checkReady(old) }
        fail(FreshReadingProgressFailure.STALE_VIEW) { reader.checkBeforeWrite(old) }
        reader.checkReady(fresh); reader.checkBeforeWrite(fresh); reader.assertCurrent()
    }

    @Test fun newerJournalAfterPreparationRefusesBothAsyncAndSynchronousApproval() = runTest {
        val store = Store(); val reader = reader(store, StandardTestDispatcher(testScheduler))
        val actual = reader.read(); store.rows = listOf(observation(view(4)))
        assertTrue(runCatching { reader.checkReady(actual) }.isFailure)
        assertTrue(runCatching { reader.checkBeforeWrite(actual) }.isFailure)
        assertEquals(view(4), store.rows.single().record.remote); assertEquals(0, store.legacyCalls)
    }

    @Test fun lowerLaterGetIsInvalidAndPermanentlyDiscardsReader() = runTest {
        val store = Store(); var next = view(4)
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { next })
        reader.read(); next = view(3)
        fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; terminal(reader)
        next = view(5); fail(FreshReadingProgressFailure.STALE) { reader.read() }
    }

    @Test fun sameVersionChangedApiAnchorOrTimestampIsTerminal() = runTest {
        for (changed in listOf(view().copy(anchor = anchor.copy(characterOffset = 2)), view().copy(updatedAt = "2026-11-02T00:00:01Z"))) {
            val store = Store(); var next = view(); val reader = reader(store, StandardTestDispatcher(testScheduler), query = { next })
            reader.read(); next = changed
            fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; terminal(reader)
        }
    }

    @Test fun overlappingOlderGetCannotReturnAfterNewerGetFinishes() = runTest {
        val held = CompletableDeferred<ServerReadingProgress>(); var calls = 0
        val reader = reader(Store(), StandardTestDispatcher(testScheduler), query = { if (++calls == 1) held.await() else view(4) })
        val old = async { runCatching { reader.read() } }; runCurrent()
        assertEquals(view(4), reader.read())
        held.complete(view(3)); runCurrent()
        assertEquals(FreshReadingProgressFailure.INVALID_RESPONSE,
            (old.await().exceptionOrNull() as FreshReadingProgressException).failure); terminal(reader)
    }

    /** Pause dispatch back from withContext(IO), after the journal snapshot has already returned. */
    private class HoldingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        var holdNext = false
        private var held: Pair<CoroutineContext, Runnable>? = null
        val hasHeld get() = held != null
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (holdNext) { check(held == null); holdNext = false; held = context to block }
            else delegate.dispatch(context, block)
        }
        fun release() { val next = requireNotNull(held); held = null; delegate.dispatch(next.first, next.second) }
    }

    @Test fun finalJournalDispatcherReturnRechecksANewerConcurrentGet() = runTest {
        val store = Store(); val io = StandardTestDispatcher(testScheduler); val gate = HoldingDispatcher(StandardTestDispatcher(testScheduler)); var gets = 0
        val reader = reader(store, io, query = { view(if (++gets == 1) 3 else 4) })
        store.onObserve = { if (it == 2) gate.holdNext = true }
        val old = async(gate) { runCatching { reader.read() } }; runCurrent()
        assertTrue("Expected suspension after the final journal observation", gate.hasHeld)
        assertEquals(view(4), reader.read())
        gate.release(); runCurrent()
        assertTrue(old.await().isFailure)
    }

    @Test fun readinessDispatcherReturnCannotApproveAnOlderPinnedView() = runTest {
        val store = Store(); val io = StandardTestDispatcher(testScheduler); val gate = HoldingDispatcher(StandardTestDispatcher(testScheduler)); var next = view(3)
        val reader = reader(store, io, query = { next }); val old = reader.read()
        store.onObserve = { if (it == 3) gate.holdNext = true }
        val approval = async(gate) { runCatching { reader.checkReady(old) } }; runCurrent()
        assertTrue(gate.hasHeld); next = view(4); val fresh = reader.read()
        gate.release(); runCurrent()
        assertTrue(approval.await().isFailure); reader.checkReady(fresh)
    }

    @Test fun invalidProductionResponseFailurePoisonsReaderEvenIfNextResponseWouldBeValid() = runTest {
        val store = Store(); var calls = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = {
            calls++; if (calls == 1) throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE) else view()
        })
        val error = runCatching { reader.read() }.exceptionOrNull()
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, (error as TranslationStoreException).failure); terminal(reader)
        fail(FreshReadingProgressFailure.STALE) { reader.read() }; assertEquals(1, calls)
    }

    @Test fun wrongTypedApiScopeOrMalformedAnchorNeverBecomesApproved() = runTest {
        for (invalid in listOf(view().copy(recordId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
            view().copy(kind = "TRANSLATION"), view().copy(anchor = ServerReadingAnchor("\ud800", 0)),
            view().copy(version = 0), view().copy(version = MaxReadingVersion + 1))) {
            val reader = reader(Store(), StandardTestDispatcher(testScheduler), query = { invalid })
            fail(FreshReadingProgressFailure.INVALID_RESPONSE) { reader.read() }; terminal(reader)
        }
    }

    @Test fun unsupportedObservationStoreNeverFallsBackToLegacyReadOrPending() = runTest {
        val store = LegacyStore(); var calls = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { calls++; view() })
        fail(FreshReadingProgressFailure.STORAGE) { reader.read() }
        assertEquals(0, store.legacyCalls); assertEquals(0, calls); terminal(reader)
    }

    @Test fun corruptObservationIsTerminalAndPreservesStoredRows() = runTest {
        val store = Store().also { it.rows = listOf(observation()); it.failure = IOException("corrupt bytes") }
        val reader = reader(store, StandardTestDispatcher(testScheduler))
        fail(FreshReadingProgressFailure.STORAGE) { reader.read() }; terminal(reader)
        store.failure = null; fail(FreshReadingProgressFailure.STALE) { reader.read() }
        assertEquals(listOf(observation()), store.rows); assertEquals(0, store.legacyCalls)
    }

    @Test fun malformedObservationScopeOrStateFailsClosedBeforeGet() = runTest {
        val invalidRows = listOf(
            observation(scope = target.copy(accountKey = "b".repeat(64))),
            observation(scope = target.copy(kind = "TRANSLATION")),
            observation(scope = target.copy(recordId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
            observation(view().copy(recordId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")),
            ReadingProgressJournalObservation(target, DeviceReadingProgress(queuedAnchor = anchor)),
            ReadingProgressJournalObservation(target, DeviceReadingProgress(remote = view(), retryAfterUntil = -1)),
            ReadingProgressJournalObservation(target, DeviceReadingProgress(pending = mutation.copy(mutationId = "invalid"))),
        )
        for (row in invalidRows) {
            val store = Store().also { it.rows = listOf(row) }; var calls = 0
            val reader = reader(store, StandardTestDispatcher(testScheduler), query = { calls++; view() })
            fail(FreshReadingProgressFailure.STORAGE) { reader.read() }; assertEquals(0, calls); terminal(reader)
            assertEquals(listOf(row), store.rows)
        }
    }

    @Test fun badCapturedTargetAndConnectionAreRejectedBeforeAnyIo() = runTest {
        val store = Store(); val io = StandardTestDispatcher(testScheduler)
        for (invalid in listOf(target.copy(accountKey = "bad"), target.copy(kind = "PDF"), target.copy(recordId = target.recordId.uppercase()))) {
            assertThrows(IllegalArgumentException::class.java) { FreshServerReadingProgressReader(store, invalid, connection(), {}, io = io) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            FreshServerReadingProgressReader(store, target, connection().copy(accountKey = "b".repeat(64)), {}, io = io)
        }
        assertEquals(0, store.calls); assertEquals(0, store.legacyCalls)
    }

    @Test fun throwingCurrentGuardTerminatesIssuanceEvenIfTheRawValueReturns() = runTest {
        val store = Store(); var live = true; var calls = 0
        val reader = reader(store, StandardTestDispatcher(testScheduler), current = { check(live) }, query = { calls++; view() })
        live = false; fail(FreshReadingProgressFailure.STALE) { reader.read() }
        live = true; fail(FreshReadingProgressFailure.STALE) { reader.read() }
        assertEquals(0, calls); assertEquals(0, store.calls)
    }

    @Test fun aCapturedTerminalAuthorityDoesNotBorrowAReplacementSameAccountOwner() = runTest {
        class Authority { var live = true; fun check() { check(live) } }
        val original = Authority(); var owner = original
        val reader = reader(Store(), StandardTestDispatcher(testScheduler), current = owner::check)
        reader.read(); original.live = false; owner = Authority()
        fail(FreshReadingProgressFailure.STALE) { reader.checkReady(view()) }; assertTrue(owner.live)
        terminal(reader)
    }

    @Test fun closeDuringHeldGetDiscardsLateSuccessWithoutLegacyWrites() = runTest {
        val store = Store(); val held = CompletableDeferred<ServerReadingProgress>()
        val reader = reader(store, StandardTestDispatcher(testScheduler), query = { held.await() })
        val pending = async { runCatching { reader.read() } }; runCurrent()
        reader.close(); held.complete(view()); runCurrent()
        assertEquals(FreshReadingProgressFailure.STALE, (pending.await().exceptionOrNull() as FreshReadingProgressException).failure)
        assertEquals(0, store.legacyCalls); terminal(reader)
    }

    @Test fun closeDuringHeldGetDiscardsLateTransportErrorDetails() = runTest {
        val held = CompletableDeferred<ServerReadingProgress>(); val reader = reader(Store(), StandardTestDispatcher(testScheduler), query = { held.await() })
        val pending = async { runCatching { reader.read() } }; runCurrent()
        reader.close(); held.completeExceptionally(IOException("private transport detail")); runCurrent()
        val error = pending.await().exceptionOrNull() as FreshReadingProgressException
        assertEquals(FreshReadingProgressFailure.STALE, error.failure); assertFalse(error.message!!.contains("private"))
    }

    @Test fun cancellationDuringNoncancellableTransportPermanentlyExpiresReader() = runTest {
        val held = CompletableDeferred<ServerReadingProgress>()
        val reader = reader(Store(), StandardTestDispatcher(testScheduler), query = { withContext(NonCancellable) { held.await() } })
        val job = launch { reader.read() }; runCurrent(); job.cancel(); held.complete(view()); runCurrent(); job.join()
        terminal(reader)
    }

    @Test fun currentLossAfterFinalObservationRejectsApproval() = runTest {
        val store = Store(); var live = true
        val reader = reader(store, StandardTestDispatcher(testScheduler), current = { check(live) })
        val actual = reader.read(); store.onObserve = { live = false }
        fail(FreshReadingProgressFailure.STALE) { reader.checkReady(actual) }; terminal(reader)
    }

    @Test fun aDifferentPinnedAnchorCannotBorrowAnOtherwiseSuccessfulRead() = runTest {
        val reader = reader(Store(), StandardTestDispatcher(testScheduler)); val actual = reader.read()
        fail(FreshReadingProgressFailure.STALE_VIEW) { reader.checkReady(actual.copy(anchor = anchor.copy(characterOffset = 2))) }
        fail(FreshReadingProgressFailure.STALE_VIEW) { reader.checkBeforeWrite(actual.copy(updatedAt = "2026-11-02T00:00:01Z")) }
        reader.checkReady(actual)
    }
}
