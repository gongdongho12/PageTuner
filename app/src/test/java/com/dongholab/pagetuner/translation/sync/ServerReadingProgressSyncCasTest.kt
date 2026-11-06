package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Atomic journal fake + production HTTP decoder; no socket, Android VM or physical store evidence. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ServerReadingProgressSyncCasTest {
    private val id = "abcdefab-1234-4abc-8def-abcdefabcdef"
    private val timestamp = "2026-11-06T00:00:00Z"

    @Test fun lateGetSuccessCannotOverwriteAnIndependentIntent() = lateGet { response(progress(2, "p2")) }
    @Test fun lateGetRateLimitCannotThrottleAnIndependentIntent() = lateGet {
        TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("60")))
    }
    @Test fun lateGetConflictCannotReplaceAnIndependentConflict() = lateGet { conflict(progress(2, "p2")) }
    @Test fun lateGetUnauthorizedCannotMarkTheReplacementTerminal() = lateGet { TranslationStoreHttpResponse(401) }
    @Test fun lateGetTimeoutCannotScheduleTheOldTransition() = lateGet { throw SocketTimeoutException() }

    private fun lateGet(result: () -> TranslationStoreHttpResponse) = runTest {
        val store = AtomicStore(); val document = document(); val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>(); var requests = 0
        val connection = connection { request ->
            requests++; assertEquals("GET", request.method); entered.complete(Unit); release.await(); result()
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection, 0); entered.await()
        val held = heldRequest(sync)
        val replacement = replacement(); store.replace(document.target(), replacement)
        release.complete(Unit); held.join(); runCurrent()
        assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
        assertEquals(1, requests); assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, requests); assertEquals(replacement, store.value(document.target()))
    }

    @Test fun oldAckCannotClearTheNewPendingOrItsQueuedConflictAndDeadline() = runTest {
        val document = document(); val original = mutation(1, "p2")
        val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), original)) }
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val sent = mutableListOf<ServerReadingMutation>()
        val connection = connection { request ->
            sent += ServerReadingProgressJson.mutation(JSONObject(request.body!!)); entered.complete(Unit); release.await(); response(progress(2, "p2"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection, 0); entered.await(); assertEquals(listOf(original), sent)
        val held = heldRequest(sync)
        val replacement = replacement(); store.replace(document.target(), replacement)
        release.complete(Unit); held.join(); runCurrent()
        assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
        assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
    }

    @Test fun outboxAckIsDiscardedWhenOnlyQueuedAnchorAndDeadlineChanged() = runTest {
        val document = document(); val original = mutation(1, "p2")
        val initial = DeviceReadingProgress(progress(1), original)
        val store = AtomicStore().apply { replace(document.target(), initial) }
        val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); var requests = 0
        val connection = connection { request ->
            requests++; assertEquals(original, ServerReadingProgressJson.mutation(JSONObject(request.body!!))); entered.complete(Unit)
            release.await(); response(progress(2, "p2"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.connect(connection); entered.await(); assertEquals(1, requests)
        val held = heldRequest(sync, outbox = true)
        val replacement = initial.copy(queuedAnchor = ServerReadingAnchor("p3", 2), retryAfterUntil = 120_000)
        store.replace(document.target(), replacement); release.complete(Unit); held.join(); runCurrent()
        assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
        advanceTimeBy(30_001); runCurrent(); assertEquals(1, requests)
    }

    @Test fun outboxLateFailureDoesNotAddConflictOrRetryToSameMutationWithNewFields() = runTest {
        for (status in listOf(409, 429, 401)) {
            val document = document(); val original = mutation(1, "p2")
            val initial = DeviceReadingProgress(progress(1), original)
            val store = AtomicStore().apply { replace(document.target(), initial) }
            val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
            val replayed = CompletableDeferred<Unit>(); var requests = 0; var now = 0L
            val connection = connection {
                requests++
                if (requests > 1) { replayed.complete(Unit); awaitCancellation() }
                entered.complete(Unit); release.await()
                when (status) {
                    409 -> conflict(progress(2, "p2"))
                    429 -> TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("60")))
                    else -> TranslationStoreHttpResponse(status)
                }
            }
            val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { now }
            sync.connect(connection); entered.await(); assertEquals(1, requests)
            val held = heldRequest(sync, outbox = true)
            val replacement = initial.copy(queuedAnchor = ServerReadingAnchor("p3", 2), retryAfterUntil = 120_000)
            store.replace(document.target(), replacement); release.complete(Unit); held.join(); runCurrent()
            assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
            advanceTimeBy(15_001); runCurrent(); assertEquals(1, requests)
            now = 120_001; advanceTimeBy(15_001); runCurrent(); replayed.await()
            assertEquals(2, requests); assertEquals(replacement, store.value(document.target()))
            sync.connect(null); runCurrent()
        }
    }

    @Test fun outboxCasLossAfterCalculationPreservesReplacementAndCannotSuppressItsLaterReplay() = runTest {
        for (hasConflict in listOf(true, false)) {
            val document = document(); val original = mutation(1, "p2")
            val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), original)) }
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val replayed = CompletableDeferred<ServerReadingMutation>(); var requests = 0; var now = 0L
            val replacement = replacement().let { if (hasConflict) it else it.copy(conflict = null) }
            val connection = connection { request ->
                val sent = ServerReadingProgressJson.mutation(JSONObject(request.body!!))
                if (++requests > 1) { replayed.complete(sent); awaitCancellation() }
                assertEquals(original, sent); entered.complete(Unit); release.await()
                TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("60")))
            }
            val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { now }
            sync.connect(connection); entered.await(); val held = heldRequest(sync, outbox = true)
            // The response-time read still sees the old row; change it only inside the CAS boundary.
            store.beforeCompare = { target -> store.replace(target, replacement) }
            release.complete(Unit); held.join(); runCurrent()
            assertEquals(1, store.mismatches); assertEquals(0, store.commits)
            assertEquals(replacement, store.value(document.target()))
            advanceTimeBy(15_001); runCurrent(); assertEquals(1, requests)
            if (!hasConflict) {
                now = 120_001; advanceTimeBy(15_001); runCurrent()
                assertEquals(replacement.pending, replayed.await())
                assertEquals(2, requests); assertEquals(replacement, store.value(document.target()))
            }
            sync.connect(null); runCurrent()
        }
    }

    @Test fun aliasInsertedInsidePageCasFailsClosedWithoutCanonicalOverwrite() = runTest {
        val document = document(); val store = AtomicStore(); var puts = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { request -> if (request.method == "PUT") puts++; response(progress(1)) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val canonical = store.value(document.target()); val commits = store.commits
        val upper = document.target().copy(recordId = id.uppercase())
        val alias = replacement().let { it.copy(remote = it.remote!!.copy(recordId = upper.recordId),
            conflict = it.conflict!!.copy(recordId = upper.recordId)) }
        store.beforeCompare = { store.replace(upper, alias) }
        sync.pageChanged(document.readerId, 1, 1, 0); runCurrent()
        assertEquals(ReadingProgressPhase.DeviceError, sync.state.value.phase)
        assertEquals(1, store.mismatches); assertEquals(commits, store.commits)
        assertEquals(canonical, store.value(document.target())); assertEquals(alias, store.value(upper))
        advanceTimeBy(701); runCurrent(); assertEquals(0, puts)
    }

    @Test fun accountKeyChangeWithSameClientRetiresTheHeldOldGet() = runTest {
        val document = document(); val store = AtomicStore(); val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val connection = connection {
            entered.complete(Unit); withContext(NonCancellable) { release.await() }; response(progress(3, "p4"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection, 0); entered.await(); val held = heldRequest(sync)
        val changed = connection.copy(accountKey = serverReadingAccountKey("https://reader.example", "other"))
        sync.connect(changed); runCurrent()
        assertEquals(ReadingProgressPhase.Inactive, sync.state.value.phase); assertNull(sync.state.value.readerId)
        release.complete(Unit); held.join(); runCurrent()
        assertEquals(0, store.commits); assertNull(store.value(document.target()))
        assertNull(sync.state.value.restore); assertNull(sync.state.value.readerId)
    }

    @Test fun externalIntentIsObservedBeforeSamePageEarlyReturn() = runTest {
        val document = document(); val store = AtomicStore()
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { response(progress(1)) }, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val commits = store.commits
        val replacement = replacement(); store.replace(document.target(), replacement)
        sync.pageChanged(document.readerId, 0, 0, 0); runCurrent()
        assertEquals(replacement, store.value(document.target())); assertEquals(commits, store.commits)
        assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
    }

    @Test fun staleLocalAndServerChoicesDoNotReapplyAgainstReplacementBase() = runTest {
        for (local in listOf(true, false)) {
            val document = document(); val store = AtomicStore().apply {
                replace(document.target(), DeviceReadingProgress(progress(1), mutation(1, "p2"), conflict = progress(2, "p3")))
            }
            var calls = 0
            val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
            sync.open(document, connection { calls++; error("Conflict-only open must not send") }, 0); runCurrent()
            assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
            val replacement = replacement(); store.replace(document.target(), replacement)
            if (local) sync.chooseLocal() else sync.chooseServer()
            runCurrent()
            assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
            assertEquals(0, calls); assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
            sync.connect(null); runCurrent()
        }
    }

    @Test fun casLossAfterPageCalculationDropsPendingAndSuccessSideEffects() = runTest {
        val document = document(); val store = AtomicStore(); var puts = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { request -> if (request.method == "PUT") puts++; response(progress(1)) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val committed = store.commits; val replacement = replacement()
        store.beforeCompare = { target -> store.replace(target, replacement) }
        sync.pageChanged(document.readerId, 1, 1, 0); runCurrent()
        assertEquals(replacement, store.value(document.target())); assertEquals(committed, store.commits)
        assertEquals(1, store.mismatches); assertEquals(0, puts)
        assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
        advanceTimeBy(701); runCurrent(); assertEquals(0, puts)
    }

    @Test fun casLossAfterReceivedCalculationCannotPublishSyncedOrOldRestore() = runTest {
        val document = document(); val store = AtomicStore(); val replacement = replacement()
        store.beforeCompare = { target -> store.replace(target, replacement) }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { response(progress(2, "p2")) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.Conflict }
        assertEquals(replacement, store.value(document.target())); assertEquals(0, store.commits)
        assertEquals(1, store.mismatches); assertEquals(ReadingProgressPhase.Conflict, sync.state.value.phase)
    }

    @Test fun twoActorsCannotBothPersistTheirHeldConflictTransition() = runTest {
        val document = document(); val original = mutation(1, "p2")
        val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), original)) }
        val one = CompletableDeferred<Unit>(); val two = CompletableDeferred<Unit>(); val entered = Channel<Unit>(Channel.UNLIMITED)
        val requests = java.util.concurrent.atomic.AtomicInteger()
        val connection = connection { request ->
            assertEquals(original, ServerReadingProgressJson.mutation(JSONObject(request.body!!)))
            val index = requests.incrementAndGet(); entered.send(Unit)
            if (index == 1) one.await() else two.await()
            conflict(progress(2, "p4"))
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val first = ServerReadingProgressSync(backgroundScope, store, dispatcher) { 0L }
        val second = ServerReadingProgressSync(backgroundScope, store, dispatcher) { 0L }
        first.open(document, connection, 0); entered.receive(); val firstJob = heldRequest(first)
        second.open(document, connection, 0); entered.receive(); val secondJob = heldRequest(second)
        assertEquals(2, requests.get())
        one.complete(Unit); firstJob.join(); runCurrent(); two.complete(Unit); secondJob.join(); runCurrent()
        assertEquals(1, store.commits); assertEquals(original, store.value(document.target())!!.pending)
        assertEquals(progress(2, "p4"), store.value(document.target())!!.conflict)
        assertEquals(ReadingProgressPhase.Conflict, first.state.value.phase)
        assertEquals(ReadingProgressPhase.Conflict, second.state.value.phase)
    }

    @Test fun inactiveTerminalSuppressionReloadsReplacementAndHonorsItsCooldown() = runTest {
        val document = document(); val old = mutation(1, "p2"); val next = mutation(4, "p3")
        val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), old)) }
        var now = 0L; val sent = mutableListOf<ServerReadingMutation>()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val connection = connection { request ->
            val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!)); sent += mutation
            if (mutation == old) { entered.complete(Unit); release.await(); TranslationStoreHttpResponse(401) }
            else response(progress(5, "p3"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { now }
        sync.connect(connection); entered.await(); val held = heldRequest(sync, outbox = true)
        release.complete(Unit); held.join(); runCurrent(); assertEquals(listOf(old), sent)
        val replacement = DeviceReadingProgress(progress(4), next, retryAfterUntil = 90_000)
        store.replace(document.target(), replacement)
        sync.close(); runCurrent(); advanceTimeBy(15_001); runCurrent()
        assertEquals(listOf(old), sent); assertEquals(replacement, store.value(document.target()))
        now = 90_001; advanceTimeBy(15_001); runCurrent()
        store.await { it.pending == null }
        assertEquals(listOf(old, next), sent); assertNull(store.value(document.target())!!.pending)
    }

    @Test fun uncertainAckReplaysSameMutationAfterLogoutBeforeQueuedPosition() = runTest {
        val document = document(); val original = mutation(1, "p2")
        val store = AtomicStore().apply {
            replace(document.target(), DeviceReadingProgress(progress(1), original, ServerReadingAnchor("p3", 1)))
        }
        val sent = mutableListOf<ServerReadingMutation>(); var timeout = true
        val connection = connection { request ->
            val pending = ServerReadingProgressJson.mutation(JSONObject(request.body!!)); sent += pending
            if (timeout) throw SocketTimeoutException()
            response(progress(pending.expectedVersion + 1, pending.anchor.paragraphId, pending.anchor.characterOffset))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Offline }
        sync.connect(null); runCurrent(); timeout = false
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Synced }
        assertEquals(3, sent.size); assertEquals(original, sent[0]); assertEquals(original, sent[1])
        assertEquals(2L, sent[2].expectedVersion); assertEquals(ServerReadingAnchor("p3", 1), sent[2].anchor)
        assertNotEquals(original.mutationId, sent[2].mutationId)
        assertNull(store.value(document.target())!!.pending)
    }

    @Test fun noncanonicalAliasAlongsideCanonicalFailsClosedWithoutMigrationOrNetwork() = runTest {
        val document = document(); val store = AtomicStore()
        store.replace(document.target(), DeviceReadingProgress(progress(1)))
        val upper = document.target().copy(recordId = id.uppercase())
        val alias = DeviceReadingProgress(progress(8).copy(recordId = upper.recordId), mutation(8, "p2"),
            ServerReadingAnchor("p3", 2), progress(9, "p4").copy(recordId = upper.recordId), 120_000)
        store.replace(upper, alias); var calls = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { calls++; response(progress(1)) }, 0); runCurrent()
        assertEquals(ReadingProgressPhase.DeviceError, sync.state.value.phase)
        assertEquals(alias, store.value(upper)); assertEquals(DeviceReadingProgress(progress(1)), store.value(document.target()))
        assertEquals(0, store.commits); assertEquals(0, calls)
    }

    @Test fun aliasOnlyOutboxFailsClosedInsteadOfTreatingCanonicalAbsenceAsEmpty() = runTest {
        val document = document(); val upper = document.target().copy(recordId = id.uppercase())
        val value = DeviceReadingProgress(progress(3).copy(recordId = upper.recordId), mutation(3, "p2"))
        val store = AtomicStore().apply { replace(upper, value) }; var calls = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.connect(connection { calls++; response(progress(4, "p2")) }); runCurrent()
        assertEquals(ReadingProgressPhase.DeviceError, sync.state.value.phase)
        assertNull(store.value(document.target())); assertEquals(value, store.value(upper))
        assertEquals(0, calls); assertEquals(0, store.commits)
    }

    @Test fun anotherInactiveReplacementCannotReviveTheActiveTerminalFailure() = runTest {
        val document = document(); val old = mutation(1, "p2")
        val other = document.target().copy(recordId = "abcdefab-1234-4abc-8def-abcdefabcdee")
        val otherMutation = mutation(4, "p3")
        val store = AtomicStore().apply {
            replace(document.target(), DeviceReadingProgress(progress(1), old))
            replace(other, DeviceReadingProgress(pending = otherMutation, retryAfterUntil = 90_000))
        }
        var activeCalls = 0; var otherCalls = 0
        val connection = connection { request ->
            if (request.url.contains(other.recordId)) { otherCalls++; error("Other cooldown must hold") }
            activeCalls++; TranslationStoreHttpResponse(401)
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        val replacement = DeviceReadingProgress(pending = mutation(5, "p4"), retryAfterUntil = 120_000)
        store.replace(other, replacement)
        advanceTimeBy(15_001); runCurrent()
        assertEquals(1, activeCalls); assertEquals(0, otherCalls)
        assertEquals(ReadingProgressPhase.Unavailable, sync.state.value.phase)
        assertEquals(old, store.value(document.target())!!.pending); assertEquals(replacement, store.value(other))
    }

    @Test fun unchangedConflictChoicePreservesItsPersistedCooldown() = runTest {
        val document = document(); val original = mutation(1, "p2")
        val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), original,
            ServerReadingAnchor("p3", 2), progress(4, "p4"), 60_000)) }
        var puts = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document, connection { puts++; error("Cooldown must hold chosen local intent") }, 0); runCurrent()
        sync.chooseLocal(); runCurrent()
        val selected = store.value(document.target())!!
        assertEquals(60_000L, selected.retryAfterUntil); assertEquals(4L, selected.pending!!.expectedVersion)
        assertEquals(ServerReadingAnchor("p3", 2), selected.pending.anchor)
        assertNotEquals(original.mutationId, selected.pending.mutationId)
        assertNull(selected.conflict); assertEquals(ReadingProgressPhase.RateLimited, sync.state.value.phase)
        advanceTimeBy(10_000); runCurrent(); assertEquals(0, puts)
    }

    @Test fun closingTerminalReaderRefreshesItsReplacementBeforeInactiveDispatch() = runTest {
        val document = document(); val original = mutation(1, "p2"); val next = mutation(4, "p3")
        val store = AtomicStore().apply { replace(document.target(), DeviceReadingProgress(progress(1), original)) }
        var now = 0L; val sent = mutableListOf<ServerReadingMutation>()
        val connection = connection { request ->
            val mutation = ServerReadingProgressJson.mutation(JSONObject(request.body!!)); sent += mutation
            if (mutation == original) TranslationStoreHttpResponse(401) else response(progress(5, "p3"))
        }
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { now }
        sync.open(document, connection, 0); sync.state.first { it.phase == ReadingProgressPhase.Unavailable }
        val replacement = DeviceReadingProgress(progress(4), next, retryAfterUntil = 90_000)
        store.replace(document.target(), replacement); sync.close(); runCurrent()
        advanceTimeBy(15_001); runCurrent(); assertEquals(listOf(original), sent)
        assertEquals(replacement, store.value(document.target()))
        now = 90_001; advanceTimeBy(15_001); runCurrent(); store.await { it.pending == null }
        assertEquals(listOf(original, next), sent); assertNull(sync.state.value.readerId)
    }

    @Test fun unsupportedCaptureDoesNotFallbackToLegacyReadOrWrite() = runTest {
        val store = AtomicStore().apply { supportCapture = false }; var requests = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document(), connection { requests++; response(progress(1)) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        assertEquals(ReadingProgressPhase.DeviceError, sync.state.value.phase)
        assertEquals(0, requests); assertEquals(0, store.legacyReads); assertEquals(0, store.legacyWrites)
    }

    @Test fun unsupportedCasDoesNotFallbackToUnconditionalWrite() = runTest {
        val store = AtomicStore().apply { supportCas = false }; var requests = 0
        val sync = ServerReadingProgressSync(backgroundScope, store, StandardTestDispatcher(testScheduler)) { 0L }
        sync.open(document(), connection { requests++; response(progress(1)) }, 0)
        sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        assertEquals(ReadingProgressPhase.DeviceError, sync.state.value.phase)
        assertEquals(1, requests); assertEquals(0, store.commits); assertEquals(0, store.legacyWrites)
        assertNull(store.value(document().target()))
    }

    // Join the exact held network coroutine (which enqueues its response action before completing),
    // then runCurrent to finish the serialized actor. This avoids wall sleeps and timer-driven polls.
    private fun heldRequest(sync: ServerReadingProgressSync, outbox: Boolean = false): Job =
        ServerReadingProgressSync::class.java.getDeclaredField(if (outbox) "outboxNetwork" else "network").let {
            it.isAccessible = true; requireNotNull(it.get(sync) as Job?)
        }

    private fun document(): ServerReadingDocument {
        val texts = List(4) { "Paragraph ${it + 1}" }
        val content = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en",
            texts.mapIndexed { index, text -> ContentParagraph("p${index + 1}", index, text) })
        val entry = ServerLibraryEntry(id, "Book", "en", texts.size, content.sourceRevision, ServerLibraryKind.Originals)
        return ServerReadingDocument.create(serverReadingAccountKey("https://reader.example", "reader"),
            ServerLibraryDocument(entry, texts, sourceContent = content))
    }
    private fun progress(version: Long, paragraph: String = "p1", offset: Int = 0) = ServerReadingProgress("ORIGINAL", id,
        version, if (version == 0L) null else ServerReadingAnchor(paragraph, offset), if (version == 0L) null else timestamp)
    private fun mutation(version: Long, paragraph: String) = ServerReadingMutation(version, UUID.randomUUID().toString(), ServerReadingAnchor(paragraph, 0))
    private fun replacement() = DeviceReadingProgress(progress(8), mutation(8, "p2"), ServerReadingAnchor("p3", 2), progress(9, "p4"), 120_000)
    private fun response(value: ServerReadingProgress) = TranslationStoreHttpResponse(200, body = ServerReadingProgressJson.encode(value).toString())
    private fun conflict(value: ServerReadingProgress) = TranslationStoreHttpResponse(409,
        body = JSONObject().put("code", "READING_PROGRESS_CONFLICT").put("current", ServerReadingProgressJson.encode(value)).toString())
    private fun connection(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) = ServerReadingConnection(
        serverReadingAccountKey("https://reader.example", "reader"), HttpTranslationStore("https://reader.example",
            TranslationStoreBasicAuth("reader", "password"), TranslationStoreHttpTransport { request ->
                if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200,
                    mapOf("Set-Cookie" to listOf("JSESSIONID=test; Path=/; HttpOnly")), """{"headerName":"X-CSRF-TOKEN","token":"token"}""")
                else handler(request)
            }))

    private class AtomicStore : ServerReadingProgressStore {
        private val rows = linkedMapOf<ServerReadingTarget, DeviceReadingProgress>()
        var supportCapture = true; var supportCas = true
        var legacyReads = 0; var legacyWrites = 0; var commits = 0; var mismatches = 0
        var beforeCompare: ((ServerReadingTarget) -> Unit)? = null
        val changes = Channel<DeviceReadingProgress>(Channel.UNLIMITED)
        @Synchronized fun replace(target: ServerReadingTarget, value: DeviceReadingProgress) { rows[target] = value }
        @Synchronized fun value(target: ServerReadingTarget) = rows[target]
        override fun read(document: ServerReadingDocument): DeviceReadingProgress = read(document.target())
        override fun read(target: ServerReadingTarget): DeviceReadingProgress { legacyReads++; error("Legacy read is not atomic") }
        override fun write(document: ServerReadingDocument, value: DeviceReadingProgress) = write(document.target(), value)
        override fun write(target: ServerReadingTarget, value: DeviceReadingProgress) { legacyWrites++; error("Unconditional write forbidden") }
        @Synchronized override fun pending(accountKey: String) = rows.filter { it.key.accountKey == accountKey && it.value.pending != null }.keys.toList()
        @Synchronized override fun captureSnapshot(target: ServerReadingTarget): ReadingProgressJournalSnapshot {
            if (!supportCapture) throw UnsupportedOperationException("No atomic capture")
            return ReadingProgressJournalSnapshot(target, rows.filter { it.key.accountKey == target.accountKey &&
                it.key.kind == target.kind && it.key.recordId.equals(target.recordId, ignoreCase = true) }
                .map { ReadingProgressJournalObservation(it.key, it.value) })
        }
        @Synchronized override fun compareAndWrite(target: ServerReadingTarget, expected: ReadingProgressJournalSnapshot,
            value: DeviceReadingProgress, guard: () -> Unit): Boolean {
            if (!supportCas) throw UnsupportedOperationException("No atomic CAS")
            beforeCompare?.also { beforeCompare = null }?.invoke(target)
            guard()
            if (captureSnapshot(target) != expected) { mismatches++; return false }
            guard(); rows[target] = value; commits++; changes.trySend(value); return true
        }
        suspend fun await(predicate: (DeviceReadingProgress) -> Boolean) { while (!predicate(changes.receive())) Unit }
    }
}
