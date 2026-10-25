package com.dongholab.pagetuner.translation.sync

import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLibraryOrganizationSyncCasTest {
    private val sample = LibraryOrganization("읽을  책 é", listOf("é", "é", "Tag", "tag", "中  文"), true)
    private fun target(account: ServerReadingConnection) = ServerReadingTarget(account.accountKey, "ORIGINAL", NotesFixture.recordId)
    private fun view(version: Long = 1, organization: LibraryOrganization = sample) = ServerLibraryOrganization(
        "ORIGINAL", NotesFixture.recordId, version, organization.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
    private fun pending(version: Long = 1, id: String = NotesFixture.noteId, value: LibraryOrganization = sample) =
        LibraryOrganizationMutation(version, id, value)
    private fun response(value: ServerLibraryOrganization) = NotesFixture.response(ServerLibraryOrganizationJson.encode(value))
    private fun request(value: TranslationStoreHttpRequest) = ServerLibraryOrganizationJson.mutation(JSONObject(value.body!!))
    private fun conflict() = TranslationStoreHttpResponse(409, body = JSONObject().put("code", "LIBRARY_ORGANIZATION_CONFLICT")
        .put("current", ServerLibraryOrganizationJson.encode(view(8))).toString())

    private class Store : ServerLibraryOrganizationStore {
        val rows = ConcurrentHashMap<String, DeviceLibraryOrganization>()
        val changes = Channel<Unit>(Channel.UNLIMITED)
        val commits = AtomicInteger()
        @Volatile var beforeCas: (DeviceLibraryOrganization?, DeviceLibraryOrganization) -> Unit = { _, _ -> }
        override fun read(target: ServerReadingTarget) = readNullable(target) ?: DeviceLibraryOrganization()
        @Synchronized override fun readNullable(target: ServerReadingTarget): DeviceLibraryOrganization? = rows[target.key]?.let {
            decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, it), target)
        }
        override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) { error("Normal sync must not use unconditional writes") }
        override fun targets(accountKey: String) = rows.keys.filter { it.startsWith("$accountKey:") }.map {
            it.split(':').let { parts -> ServerReadingTarget(parts[0], parts[1], parts[2]) }
        }
        override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
            value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
            beforeCas(expected, value)
            return synchronized(this) {
                guard()
                if (readNullable(target) != expected) false else {
                    rows[target.key] = decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, value), target)
                    commits.incrementAndGet(); changes.trySend(Unit); true
                }
            }
        }
        suspend fun await(target: ServerReadingTarget, predicate: (DeviceLibraryOrganization?) -> Boolean) {
            while (!predicate(readNullable(target))) changes.receive()
        }
    }

    @Test fun lateNormalGetSuccessRateLimitConflictAndAuthCannotReplaceIndependentIntent() = runTest(timeout = 15.seconds) {
        for (kind in listOf("ok", "429", "409", "401", "timeout")) {
            val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var puts = 0
            val account = NotesFixture.connection(kind) { req ->
                if (req.method == "PUT") { puts++; error("Replacement is still cooling down") }
                withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    when (kind) {
                        "429" -> TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("300")))
                        "409" -> conflict()
                        "401" -> TranslationStoreHttpResponse(401)
                        "timeout" -> throw SocketTimeoutException()
                        else -> response(view())
                    }
                }
            }
            val sync = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
            sync.open(target(account), account); entered.await(); val old = sync.state.value
            val replacement = DeviceLibraryOrganization(view(2), pending(2), retryAfterUntil = 120_000)
            assertTrue(store.compareAndWrite(target(account), null, replacement) {})
            release.complete(Unit)
            val latest = sync.state.first { it.session != old.session && it.phase == ReadingProgressPhase.RateLimited }
            assertTrue(latest.staleActionRejected); assertEquals(replacement, store.readNullable(target(account))); assertEquals(0, puts)
            sync.connect(null); sync.state.first { it.accountKey == null }
        }
    }

    @Test fun latePutAckAndErrorsCannotClearNewPendingQueuedConflictOrDeadline() = runTest(timeout = 15.seconds) {
        for (kind in listOf("ack", "429", "409", "401", "timeout")) {
            val store = Store(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val writes = mutableListOf<LibraryOrganizationMutation>()
            val account = NotesFixture.connection(kind) { req ->
                check(req.method == "PUT"); val mutation = request(req); writes += mutation
                withContext(NonCancellable) {
                    entered.complete(Unit); release.await()
                    when (kind) {
                        "429" -> TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("300")))
                        "409" -> conflict()
                        "401" -> TranslationStoreHttpResponse(401)
                        "timeout" -> throw SocketTimeoutException()
                        else -> response(view(mutation.expectedVersion + 1, mutation.organization))
                    }
                }
            }
            val old = DeviceLibraryOrganization(view(), pending())
            store.rows[target(account).key] = old
            val sync = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
            sync.open(target(account), account); entered.await(); val oldSession = sync.state.value.session
            val replacement = DeviceLibraryOrganization(view(3), pending(3, NotesFixture.otherId, LibraryOrganization()),
                sample.copy(folder = "Queued"), view(4), 120_000)
            assertTrue(store.compareAndWrite(target(account), old, replacement) {})
            release.complete(Unit)
            sync.state.first { it.session != oldSession && it.phase == ReadingProgressPhase.Conflict }
            assertEquals(replacement, store.readNullable(target(account))); assertEquals(listOf(old.pending), writes)
            sync.connect(null); sync.state.first { it.accountKey == null }
        }
    }

    @Test fun staleEditorSameValueCannotSkipCanonicalReloadOrAppendQueuedIntent() = runTest(timeout = 10.seconds) {
        val store = Store(); var puts = 0
        val account = NotesFixture.connection { if (it.method == "PUT") { puts++; error("cooldown") }; response(view()) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
        sync.open(target(account), account); val form = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val previous = store.readNullable(target(account))
        val replacement = DeviceLibraryOrganization(view(2), pending(2, value = LibraryOrganization("Imported")), retryAfterUntil = 120_000)
        assertTrue(store.compareAndWrite(target(account), previous, replacement) {})
        sync.edit(form.session, sample, form.base) // Equal to the cached old local must still check the durable base.
        sync.state.first { it.session != form.session && it.phase == ReadingProgressPhase.RateLimited }
        assertEquals(replacement, store.readNullable(target(account))); assertEquals(0, puts)
        sync.edit(form.session, sample.copy(folder = "Old form"), form.base); runCurrent()
        assertEquals(replacement, store.readNullable(target(account)))
    }

    @Test fun staleConflictChoiceCannotDropExternalIntent() = runTest(timeout = 10.seconds) {
        val store = Store(); val account = NotesFixture.connection { error("Conflicted row should not send") }
        val old = DeviceLibraryOrganization(view(), pending(), sample.copy(folder = "Queued old"), view(2), 120_000)
        store.rows[target(account).key] = old
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
        sync.open(target(account), account); val form = sync.state.first { it.choice != null }
        val replacement = DeviceLibraryOrganization(view(3), pending(3, NotesFixture.otherId), retryAfterUntil = 120_000)
        assertTrue(store.compareAndWrite(target(account), old, replacement) {})
        sync.choose(form.session, false, form.choice!!)
        sync.state.first { it.session != form.session && it.phase == ReadingProgressPhase.RateLimited }
        assertEquals(replacement, store.readNullable(target(account)))
    }

    @Test fun lostCasDiscardsTransitionAndPreservesReplacementWithoutApplyingSuccessSideEffects() = runTest(timeout = 10.seconds) {
        val store = Store(); val account = NotesFixture.connection { response(view()) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
        sync.open(target(account), account); val form = sync.state.first { it.phase == ReadingProgressPhase.Synced }
        val replacement = DeviceLibraryOrganization(view(5), pending(5), sample.copy(folder = "New queued"), view(6), 120_000)
        store.beforeCas = { _, next ->
            if (next.pending != null) { store.beforeCas = { _, _ -> }; store.rows[target(account).key] = replacement }
        }
        sync.edit(form.session, LibraryOrganization(), form.base)
        val state = sync.state.first { it.session != form.session && it.phase == ReadingProgressPhase.Conflict }
        assertTrue(state.staleActionRejected); assertEquals(replacement, store.readNullable(target(account)))
        assertEquals(1, store.commits.get()) // Only the original successful GET journal, never the lost edit.
    }

    @Test fun inactivePumpRereadsReplacementBeforeCooldownAndDispatch() = runTest(timeout = 10.seconds) {
        val store = Store(); val sent = mutableListOf<LibraryOrganizationMutation>()
        val account = NotesFixture.connection { req ->
            val mutation = request(req); sent += mutation; response(view(mutation.expectedVersion + 1, mutation.organization))
        }
        val original = DeviceLibraryOrganization(view(), pending(), retryAfterUntil = 5_000)
        store.rows[target(account).key] = original
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account); val initial = sync.state.first { it.pendingDocuments == 1 }
        val replacement = DeviceLibraryOrganization(view(2), pending(2, NotesFixture.otherId, LibraryOrganization()), retryAfterUntil = 10_000)
        assertTrue(store.compareAndWrite(target(account), original, replacement) {})
        advanceTimeBy(5_001); runCurrent()
        sync.state.first { it.session != initial.session }
        assertTrue(sent.isEmpty()); assertEquals(replacement, store.readNullable(target(account)))
        advanceTimeBy(5_001); runCurrent()
        store.await(target(account)) { it?.pending == null }
        assertEquals(listOf(replacement.pending), sent)
    }

    @Test fun explicitIdleHandoffDeliversSameMutationAcrossTimeoutAndRestart() = runTest(timeout = 10.seconds) {
        val store = Store(); val writes = mutableListOf<LibraryOrganizationMutation>(); var fail = true
        val firstWrite = CompletableDeferred<Unit>()
        val account = NotesFixture.connection { req ->
            val mutation = request(req); writes += mutation; firstWrite.complete(Unit)
            if (fail) throw SocketTimeoutException()
            response(view(mutation.expectedVersion + 1, mutation.organization))
        }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account); sync.state.first { it.accountKey == account.accountKey }
        val beforeClose = sync.state.value.session; sync.close()
        val idle = sync.state.first { it.session > beforeClose }
        val replacement = DeviceLibraryOrganization(view(), pending(value = LibraryOrganization()))
        assertTrue(store.compareAndWrite(target(account), null, replacement) {})
        runCurrent(); assertTrue(writes.isEmpty())
        sync.notifyCommitted(target(account), account, idle.session)
        firstWrite.await()
        sync.connect(null); sync.state.first { it.accountKey == null }; fail = false
        val restarted = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        restarted.connect(account)
        store.await(target(account)) { it?.pending == null }
        assertEquals(listOf(replacement.pending, replacement.pending), writes)
    }

    @Test fun retainedHandoffCannotBorrowAccountAbaOrReplacementClientSession() = runTest(timeout = 10.seconds) {
        val store = Store(); var writes = 0
        val account = NotesFixture.connection { req -> writes++; val mutation = request(req); response(view(mutation.expectedVersion + 1, mutation.organization)) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.connect(account); val first = sync.state.first { it.accountKey == account.accountKey }
        sync.connect(null); sync.state.first { it.accountKey == null }
        sync.connect(account); sync.state.first { it.accountKey == account.accountKey }
        val beforeClose = sync.state.value.session; sync.close()
        val idle = sync.state.first { it.session > beforeClose }
        val replacement = DeviceLibraryOrganization(view(), pending())
        assertTrue(store.compareAndWrite(target(account), null, replacement) {})
        sync.notifyCommitted(target(account), account, first.session)
        sync.notifyCommitted(target(account), NotesFixture.connection { error("wrong client") }, idle.session)
        runCurrent(); assertEquals(0, writes); assertEquals(replacement, store.readNullable(target(account)))
        sync.notifyCommitted(target(account), account, idle.session)
        store.await(target(account)) { it?.pending == null }
        assertEquals(1, writes)
    }

    @Test fun duplicateActorsCompetingEditsHaveOneCasWinnerWithoutQueuedOverwrite() = runTest(timeout = 10.seconds) {
        val store = Store(); val account = NotesFixture.connection { error("Cooldown must defer HTTP") }
        store.rows[target(account).key] = DeviceLibraryOrganization(view(), retryAfterUntil = 120_000)
        val first = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
        val second = ServerLibraryOrganizationSync(backgroundScope, store) { 0L }
        first.open(target(account), account); second.open(target(account), account)
        val a = first.state.first { it.phase == ReadingProgressPhase.RateLimited }
        val b = second.state.first { it.phase == ReadingProgressPhase.RateLimited }
        val entered = CountDownLatch(2)
        store.beforeCas = { _, next -> if (next.pending != null) { entered.countDown(); check(entered.await(5, TimeUnit.SECONDS)) } }
        first.edit(a.session, LibraryOrganization("First"), a.base)
        second.edit(b.session, LibraryOrganization("Second"), b.base)
        store.await(target(account)) { it?.pending != null }
        val loser = if (store.read(target(account)).local!!.folder == "First") second else first
        loser.state.first { it.staleActionRejected }
        assertEquals(1, store.commits.get()); assertNull(store.read(target(account)).queued)
    }

    @Test fun unsupportedStoreFailsClosedWithoutUnconditionalWriteOrHttp() = runTest(timeout = 10.seconds) {
        var writes = 0; var calls = 0
        val store = object : ServerLibraryOrganizationStore {
            override fun read(target: ServerReadingTarget) = DeviceLibraryOrganization()
            override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization) { writes++ }
            override fun targets(accountKey: String) = emptyList<ServerReadingTarget>()
        }
        val account = NotesFixture.connection { calls++; response(view()) }
        val sync = ServerLibraryOrganizationSync(backgroundScope, store) { testScheduler.currentTime }
        sync.open(target(account), account); sync.state.first { it.phase == ReadingProgressPhase.DeviceError }
        sync.retry(); runCurrent()
        assertEquals(0, calls); assertEquals(0, writes)
    }
}
