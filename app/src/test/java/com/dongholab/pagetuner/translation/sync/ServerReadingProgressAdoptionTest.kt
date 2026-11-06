package com.dongholab.pagetuner.translation.sync

import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerReadingProgressAdoptionTest {
    private val id = "abcdefab-1234-4abc-8def-abcdefabcdef"
    private val time = "2026-11-06T00:00:00Z"
    private val account = connection { error("Injected query must not touch HTTP") }
    private val target = ServerReadingTarget(account.accountKey, "ORIGINAL", id)
    private val selected = ServerReadingAnchor("  p:é中😀  ", 18)
    private fun remote(version: Long = 0, anchor: ServerReadingAnchor = selected) = ServerReadingProgress(
        target.kind, target.recordId, version, anchor.takeIf { version > 0 }, time.takeIf { version > 0 })
    private fun pending(version: Long = 0) = ServerReadingMutation(version, UUID.randomUUID().toString(), selected)
    private fun actor(store: Store, dispatcher: CoroutineDispatcher, guard: () -> Unit = {},
        canonical: suspend () -> Unit = {}, query: suspend () -> ServerReadingProgress = { remote() }) =
        ServerReadingProgressAdoption(store, target, account, guard, canonical, query, dispatcher)

    @Test fun comparisonIsGetOnlyAndPresentAnchorCanCreateVersionZeroIntent() = runTest {
        val store = Store(); var gets = 0
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { gets++; remote() })
        val shown = command.compare()
        assertNull(shown.record.canonical); assertNull(store.value(target)); assertEquals(1, gets)
        assertTrue(command.adopt(shown, selected))
        val intent = store.value(target)!!.pending!!
        assertEquals(selected, intent.anchor); assertEquals(0L, intent.expectedVersion)
        assertEquals(intent.mutationId, UUID.fromString(intent.mutationId).toString())
        assertEquals(2, gets); assertEquals(1, store.commits)
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertEquals(0, store.legacyReads); assertEquals(0, store.legacyWrites)
    }

    @Test fun sameAnchorStillCreatesFreshMutationAndPreservesRetryDeadlineAndUnicode() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(3), retryAfterUntil = 999_999)) }
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { remote(3) })
        assertTrue(command.adopt(command.compare(), selected))
        val first = store.value(target)!!.pending!!
        assertEquals(selected, first.anchor); assertEquals(3L, first.expectedVersion)
        assertEquals(999_999L, store.value(target)!!.retryAfterUntil)
        // Model a completed normal ACK, not an adoption-generated PUT.
        store.replace(target, DeviceReadingProgress(remote(4), retryAfterUntil = 999_999))
        val next = actor(store, StandardTestDispatcher(testScheduler), query = { remote(4) })
        assertTrue(next.adopt(next.compare(), selected))
        assertNotEquals(first.mutationId, store.value(target)!!.pending!!.mutationId)
        assertEquals(4L, store.value(target)!!.pending!!.expectedVersion)
    }

    @Test fun missingAndExistingEmptyCanonicalRowsAreDifferentConfirmationBases() = runTest {
        for (initial in listOf<DeviceReadingProgress?>(null, DeviceReadingProgress())) {
            val store = Store().apply { initial?.let { replace(target, it) } }
            val command = actor(store, StandardTestDispatcher(testScheduler))
            val shown = command.compare()
            if (initial == null) store.replace(target, DeviceReadingProgress()) else store.remove(target)
            val before = store.snapshot(target)
            assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
            assertEquals(before, store.snapshot(target)); assertEquals(0, store.commits)
        }
    }

    @Test fun changedDeadlineConsumesConfirmationAndNeverOverwritesDeviceState() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(retryAfterUntil = 100)) }
        val command = actor(store, StandardTestDispatcher(testScheduler)); val shown = command.compare()
        store.replace(target, DeviceReadingProgress(retryAfterUntil = 101))
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        store.replace(target, DeviceReadingProgress(retryAfterUntil = 100))
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertEquals(0, store.commits)
    }

    @Test fun dirtyPendingQueuedAndConflictArePreservedAndDoNotQuery() = runTest {
        val mutation = pending()
        for (record in listOf(DeviceReadingProgress(pending = mutation),
            DeviceReadingProgress(pending = mutation, queuedAnchor = ServerReadingAnchor("next", 0)),
            DeviceReadingProgress(remote(2), mutation, conflict = remote(3), retryAfterUntil = 120_000))) {
            val store = Store().apply { replace(target, record) }; var queries = 0
            val command = actor(store, StandardTestDispatcher(testScheduler), query = { queries++; remote(4) })
            assertTrue(runCatching { command.compare() }.isFailure)
            assertEquals(record, store.value(target)); assertEquals(0, queries); assertEquals(0, store.commits)
        }
    }

    @Test fun dirtyJournalRemoteHighWaterSurvivesRejectionAndDeletion() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(7), pending(7))) }
        var value = remote(8); var queries = 0
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { queries++; value })
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, queries)
        store.remove(target); value = remote(6)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun dirtyConflictHighWaterIsObservedBeforeRejection() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(2), pending(2), conflict = remote(9))) }
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { remote(8) })
        assertTrue(runCatching { command.compare() }.isFailure)
        store.remove(target)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun journalHighWaterSurvivesNetworkFailureAndRowDeletion() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(5))) }
        var fail = true; var value = remote(4)
        val command = actor(store, StandardTestDispatcher(testScheduler), query = {
            if (fail) throw SocketTimeoutException(); value
        })
        assertTrue(runCatching { command.compare() }.isFailure)
        store.remove(target); fail = false
        assertTrue(runCatching { command.compare() }.isFailure)
        value = remote(6)
        assertTrue(runCatching { command.compare() }.isFailure) // Inconsistent GET is terminal.
        assertEquals(0, store.commits)
    }

    @Test fun olderJournalDoesNotReplaceSuccessfulApiHighWater() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(2))) }
        var value = remote(4)
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        command.compare(); command.compare() // Unchanged v2 journal legitimately lags GET v4.
        store.remove(target); value = remote(3)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, store.commits)
    }

    @Test fun pendingAppearingAfterGetRetainsApiHighWaterBeforeRejection() = runTest {
        val store = Store(); var inject = true; var value = remote(8)
        val command = actor(store, StandardTestDispatcher(testScheduler), query = {
            if (inject) store.replace(target, DeviceReadingProgress(pending = pending()))
            value
        })
        assertTrue(runCatching { command.compare() }.isFailure)
        store.remove(target); inject = false; value = remote(7)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, store.commits)
    }

    @Test fun higherServerValueRequiresFreshComparisonWithoutWriting() = runTest {
        val store = Store(); var value = remote(1)
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        val shown = command.compare(); value = remote(2)
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
        assertTrue(command.adopt(command.compare(), selected))
        assertEquals(2L, store.value(target)!!.pending!!.expectedVersion)
    }

    @Test fun lowerOrSameVersionChangedAnchorOrTimePermanentlyExpiresCommand() = runTest {
        for (invalid in listOf(remote(1), remote(2, selected.copy(characterOffset = 19)),
            remote(2).copy(updatedAt = "2026-11-07T00:00:00Z"))) {
            val store = Store(); var value = remote(2)
            val command = actor(store, StandardTestDispatcher(testScheduler), query = { value })
            val shown = command.compare(); value = invalid
            assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
            value = remote(3)
            assertTrue(runCatching { command.compare() }.isFailure)
            assertNull(store.value(target)); assertEquals(0, store.commits)
        }
    }

    @Test fun aliasesRejectWithoutMigrationAndRetainTheirHighWaterRegardlessOfOrder() = runTest {
        for (reverse in listOf(false, true)) {
            val upper = target.copy(recordId = id.uppercase())
            val store = Store()
            val entries = listOf(target to DeviceReadingProgress(remote(3)), upper to
                DeviceReadingProgress(remote(10).copy(recordId = upper.recordId), pending(10)))
            (if (reverse) entries.reversed() else entries).forEach { (key, row) -> store.replace(key, row) }
            var queries = 0
            val command = actor(store, StandardTestDispatcher(testScheduler), query = { queries++; remote(9) })
            val before = store.snapshot(target)
            assertTrue(runCatching { command.compare() }.isFailure)
            assertEquals(before, store.snapshot(target)); assertEquals(0, queries)
            store.remove(target); store.remove(upper)
            assertTrue(runCatching { command.compare() }.isFailure)
            assertEquals(0, store.commits)
        }
    }

    @Test fun sameVersionDifferentJournalContentIsInvalidEvenBelowHighestVersion() = runTest {
        val upper = target.copy(recordId = id.uppercase())
        val store = Store().apply {
            replace(target, DeviceReadingProgress(remote(2), conflict = remote(9)))
            replace(upper, DeviceReadingProgress(remote(2, selected.copy(characterOffset = 20)).copy(recordId = upper.recordId)))
        }
        var queries = 0
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { queries++; remote(10) })
        assertTrue(runCatching { command.compare() }.isFailure)
        store.remove(target); store.remove(upper)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, queries); assertEquals(0, store.commits)
    }

    @Test fun displayedRecordAndRemoteCannotReplacePrivateConfirmationValues() = runTest {
        val store = Store().apply { replace(target, DeviceReadingProgress(remote(2))) }
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { remote(2) })
        val shown = command.compare()
        val forgedDisplay = ReadingProgressAdoptionComparison(shown.id,
            ReadingProgressJournalSnapshot(target, emptyList()), remote(99))
        assertTrue(runCatching { command.adopt(forgedDisplay, selected) }.isFailure)
        (shown.record.aliases as MutableList).clear()
        assertTrue(command.adopt(shown, selected))
        assertEquals(2L, store.value(target)!!.pending!!.expectedVersion)
        assertEquals(remote(2), store.value(target)!!.remote)
    }

    @Test fun canonicalRowRetainsRawRemoteUuidForCasButProjectsOnlyHighWaterScope() = runTest {
        val raw = remote(4).copy(recordId = id.uppercase())
        val store = Store().apply { replace(target, DeviceReadingProgress(raw, retryAfterUntil = 70_000)) }
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { remote(4) })
        val shown = command.compare()
        assertEquals(raw, shown.record.canonical!!.remote)
        assertEquals(remote(4), shown.remote)
        assertTrue(command.adopt(shown, selected))
        assertEquals(remote(4), store.value(target)!!.remote)
        assertEquals(4L, store.value(target)!!.pending!!.expectedVersion)
        assertEquals(70_000L, store.value(target)!!.retryAfterUntil)
    }

    @Test fun uppercaseRawConflictIsObservedBeforeDirtyRejectionWithoutNormalizingStoredRow() = runTest {
        val raw = remote(9).copy(recordId = id.uppercase())
        val record = DeviceReadingProgress(remote(2), pending(2), conflict = raw)
        val store = Store().apply { replace(target, record) }
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { remote(8) })
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(record, store.value(target)); store.remove(target)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, store.commits)
    }

    @Test fun anotherCommandOrReplacedComparisonCannotBorrowTheCurrentToken() = runTest {
        val store = Store(); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = actor(store, dispatcher); val second = actor(store, dispatcher)
        val old = first.compare(); val latest = first.compare(); val foreign = second.compare()
        assertTrue(runCatching { first.adopt(old, selected) }.isFailure)
        // Rejected old/foreign callback must not issue any new comparison or write.
        assertTrue(runCatching { first.adopt(foreign, selected) }.isFailure)
        assertTrue(first.adopt(latest, selected)); assertEquals(1, store.commits)
    }

    @Test fun invalidSelectedAnchorConsumesTheOtherwiseValidConfirmation() = runTest {
        val store = Store(); val command = actor(store, StandardTestDispatcher(testScheduler))
        val shown = command.compare()
        assertTrue(runCatching { command.adopt(shown, selected.copy(characterOffset = -1)) }.isFailure)
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun storeCasMismatchPreservesNewPendingAndDoesNotRetryCalculatedTransition() = runTest {
        val store = Store(); val command = actor(store, StandardTestDispatcher(testScheduler))
        val shown = command.compare()
        val replacement = DeviceReadingProgress(remote(4), pending(4), selected.copy(characterOffset = 19), remote(5), 90_000)
        store.beforeCompare = { store.replace(target, replacement) }
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertEquals(replacement, store.value(target)); assertEquals(0, store.commits)
        assertEquals(1, store.mismatches)
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
    }

    @Test fun twoCommandsComparingOneAbsentBaseHaveOnlyOneDurableWinner() = runTest {
        val store = Store(); val dispatcher = StandardTestDispatcher(testScheduler)
        val first = actor(store, dispatcher); val second = actor(store, dispatcher)
        val a = first.compare(); val b = second.compare()
        val results = awaitAll(async { runCatching { first.adopt(a, selected) }.getOrDefault(false) },
            async { runCatching { second.adopt(b, selected.copy(characterOffset = 19)) }.getOrDefault(false) })
        assertEquals(1, results.count { it }); assertEquals(1, store.commits)
    }

    @Test fun finalCanonicalRecheckAndLastGuardBothCanRejectWithoutErasingRows() = runTest {
        for (failCanonical in listOf(false, true)) {
            val store = Store().apply { replace(target, DeviceReadingProgress(retryAfterUntil = 60_000)) }
            var current = true; var canonical = true
            val command = actor(store, StandardTestDispatcher(testScheduler), guard = { check(current) }, canonical = { check(canonical) })
            val shown = command.compare(); val before = store.snapshot(target)
            if (failCanonical) canonical = false else store.beforeLastGuard = { current = false }
            assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
            assertEquals(before, store.snapshot(target)); assertEquals(0, store.commits)
            current = true; canonical = true
            assertTrue(runCatching { command.compare() }.isFailure)
        }
    }

    @Test fun canonicalChangeDuringFinalCallbackIsCaughtByExactCas() = runTest {
        val store = Store(); var callbacks = 0
        val replacement = DeviceReadingProgress(retryAfterUntil = 30_000)
        val command = actor(store, StandardTestDispatcher(testScheduler), canonical = {
            if (++callbacks == 4) store.replace(target, replacement)
        })
        val shown = command.compare()
        assertTrue(runCatching { command.adopt(shown, selected) }.isFailure)
        assertEquals(replacement, store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun closeDuringHeldNonCancellableGetDiscardsLateResponseForever() = runTest {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val command = actor(store, StandardTestDispatcher(testScheduler), query = {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); remote() }
        })
        val work = async { runCatching { command.compare() } }
        entered.await(); command.close(); release.complete(Unit)
        assertTrue(work.await().isFailure); assertNull(store.value(target))
        assertTrue(runCatching { command.compare() }.isFailure)
    }

    @Test fun guardExceptionIsTerminalEvenWhenCallerBecomesCurrentAgain() = runTest {
        val store = Store(); var current = false; var queries = 0
        val command = actor(store, StandardTestDispatcher(testScheduler), guard = { check(current) }, query = { queries++; remote() })
        assertTrue(runCatching { command.compare() }.isFailure)
        current = true
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, queries); assertEquals(0, store.commits)
    }

    @Test fun cancellationDuringGetIsTerminalAndNeverCreatesIntent() = runTest {
        val store = Store(); val entered = CompletableDeferred<Unit>()
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { entered.complete(Unit); awaitCancellation() })
        val work = async { command.compare() }; entered.await(); work.cancelAndJoin()
        assertTrue(runCatching { command.compare() }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun cancellationWhileWaitingForCommandMutexAlsoRetiresHeldGet() = runTest {
        val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val command = actor(store, StandardTestDispatcher(testScheduler), query = {
            withContext(NonCancellable) { entered.complete(Unit); release.await(); remote() }
        })
        val first = async { runCatching { command.compare() } }; entered.await()
        val queued = async { command.compare() }; runCurrent(); queued.cancelAndJoin()
        release.complete(Unit)
        assertTrue(first.await().isFailure)
        assertTrue(runCatching { command.compare() }.isFailure)
        assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    @Test fun closeOrCancellationInsideLastGuardRejectsBeforeCommit() = runTest {
        for (cancel in listOf(false, true)) {
            val store = Store(); var staged = false
            lateinit var command: ServerReadingProgressAdoption; lateinit var work: Deferred<Boolean>
            command = actor(store, StandardTestDispatcher(testScheduler), guard = {
                if (staged) { if (cancel) work.cancel() else command.close() }
            })
            val shown = command.compare(); store.beforeLastGuard = { staged = true }
            work = async { runCatching { command.adopt(shown, selected) }.getOrDefault(false) }
            assertFalse(runCatching { work.await() }.getOrDefault(false))
            assertNull(store.value(target)); assertEquals(0, store.commits)
        }
    }

    @Test fun postCommitCloseAndDispatcherCancellationCannotReportFalseRollback() = runTest {
        val store = Store(); val command = actor(store, StandardTestDispatcher(testScheduler))
        val shown = command.compare(); val result = CompletableDeferred<Boolean>(); lateinit var work: Job
        store.afterCommit = { command.close(); work.cancel() }
        work = launch { result.complete(command.adopt(shown, selected)) }
        assertTrue(result.await()); work.join()
        assertNotNull(store.value(target)!!.pending); assertEquals(1, store.commits)
    }

    @Test fun invalidTargetResponseAndVersionCeilingCannotCommit() = runTest {
        val store = Store(); var value = remote(MaxReadingVersion)
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { value })
        assertTrue(runCatching { command.adopt(command.compare(), selected) }.isFailure)
        assertNull(store.value(target))
        value = remote().copy(recordId = "abcdefab-1234-4abc-8def-abcdefabcdee")
        assertTrue(runCatching { command.compare() }.isFailure)
        value = remote()
        assertTrue(runCatching { command.compare() }.isFailure); assertEquals(0, store.commits)
    }

    @Test fun constructorRejectsAnotherAccountOrNoncanonicalTarget() = runTest {
        val store = Store(); val dispatcher = StandardTestDispatcher(testScheduler)
        for (invalid in listOf(target.copy(accountKey = "0".repeat(64)), target.copy(recordId = id.uppercase()), target.copy(kind = "PDF"))) {
            assertTrue(runCatching { ServerReadingProgressAdoption(store, invalid, account, {}, {}, { remote() }, dispatcher) }.isFailure)
        }
        assertEquals(0, store.commits)
    }

    @Test fun unsupportedCaptureAndCasNeverFallbackToLegacyReadsOrWrites() = runTest {
        for (captureSupported in listOf(false, true)) {
            val store = Store().apply { supportCapture = captureSupported; supportCas = false }
            val command = actor(store, StandardTestDispatcher(testScheduler))
            assertTrue(runCatching { if (captureSupported) command.adopt(command.compare(), selected) else command.compare() }.isFailure)
            assertEquals(0, store.commits); assertEquals(0, store.legacyReads); assertEquals(0, store.legacyWrites)
        }
    }

    @Test fun corruptedObservationPermanentlyExpiresBeforeAnotherQuery() = runTest {
        val store = Store().apply { failCapture = true }; var queries = 0
        val command = actor(store, StandardTestDispatcher(testScheduler), query = { queries++; remote() })
        assertTrue(runCatching { command.compare() }.isFailure)
        store.failCapture = false
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(0, queries); assertEquals(0, store.commits)
    }

    @Test fun productionHttpDecoderUsesOnlyDirectGetAndNetworkFailureCanBeRetried() = runTest {
        val store = Store(); var calls = 0; var fail = true
        val connection = connection { request ->
            assertEquals("GET", request.method); calls++
            if (fail) throw SocketTimeoutException()
            TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(remote()).toString())
        }
        val command = ServerReadingProgressAdoption(store, target, connection, {}, {}, io = StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { command.compare() }.isFailure)
        fail = false
        assertTrue(command.adopt(command.compare(), selected)); assertEquals(3, calls)
        assertEquals(1, store.commits)
    }

    @Test fun productionInvalidResponseCannotBeRevivedByLaterValidPayload() = runTest {
        val store = Store(); var calls = 0; var malformed = true
        val connection = connection { calls++; TranslationStoreHttpResponse(200,
            body = if (malformed) "{}" else ServerReadingProgressJson.encode(remote()).toString()) }
        val command = ServerReadingProgressAdoption(store, target, connection, {}, {}, io = StandardTestDispatcher(testScheduler))
        assertTrue(runCatching { command.compare() }.isFailure)
        malformed = false
        assertTrue(runCatching { command.compare() }.isFailure)
        assertEquals(1, calls); assertNull(store.value(target)); assertEquals(0, store.commits)
    }

    private fun connection(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) = ServerReadingConnection(
        serverReadingAccountKey("https://reader.example", "reader"), HttpTranslationStore("https://reader.example",
            TranslationStoreBasicAuth("reader", "password"), TranslationStoreHttpTransport { request ->
                if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200,
                    mapOf("Set-Cookie" to listOf("JSESSIONID=test; Path=/; HttpOnly")), """{"headerName":"X-CSRF-TOKEN","token":"token"}""")
                else handler(request)
            }))

    private class Store : ServerReadingProgressStore {
        private val rows = linkedMapOf<ServerReadingTarget, DeviceReadingProgress>()
        var commits = 0; var mismatches = 0; var legacyReads = 0; var legacyWrites = 0
        var supportCapture = true; var supportCas = true; var failCapture = false
        var beforeCompare: () -> Unit = {}; var beforeLastGuard: () -> Unit = {}; var afterCommit: () -> Unit = {}
        @Synchronized fun replace(target: ServerReadingTarget, value: DeviceReadingProgress) { rows[target] = value }
        @Synchronized fun remove(target: ServerReadingTarget) { rows.remove(target) }
        @Synchronized fun value(target: ServerReadingTarget) = rows[target]
        @Synchronized fun snapshot(target: ServerReadingTarget) = ReadingProgressJournalSnapshot(target,
            rows.filter { it.key.accountKey == target.accountKey && it.key.kind == target.kind && it.key.recordId.equals(target.recordId, true) }
                .map { ReadingProgressJournalObservation(it.key, it.value) })
        override fun read(document: ServerReadingDocument) = read(document.target())
        override fun read(target: ServerReadingTarget): DeviceReadingProgress { legacyReads++; error("Legacy read forbidden") }
        override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = write(document.target(), value)
        override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) { legacyWrites++; error("Unconditional write forbidden") }
        override fun pending(accountKey: String): List<ServerReadingTarget> = error("Pending-only discovery forbidden")
        @Synchronized override fun captureSnapshot(target: ServerReadingTarget): ReadingProgressJournalSnapshot {
            if (!supportCapture) throw UnsupportedOperationException("Atomic capture unavailable")
            if (failCapture) error("Corrupt/incomplete journal observation")
            return snapshot(target)
        }
        @Synchronized override fun compareAndWrite(target: ServerReadingTarget, expected: ReadingProgressJournalSnapshot,
            value: DeviceReadingProgress, guard: () -> Unit): Boolean {
            if (!supportCas) throw UnsupportedOperationException("Atomic CAS unavailable")
            val detached = ReadingProgressJournalSnapshot(target, listOf(ReadingProgressJournalObservation(target, value))).canonical!!
            beforeCompare(); guard()
            if (snapshot(target) != expected) { mismatches++; return false }
            beforeLastGuard(); guard()
            rows[target] = detached; commits++; afterCommit(); return true
        }
    }
}
