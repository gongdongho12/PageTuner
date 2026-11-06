package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class ReadingProgressPhase { Inactive, Loading, Synced, Pending, Offline, RateLimited, Conflict, Unavailable, DeviceError }
data class ReadingProgressRestore(val readerId: String, val pageIndex: Int, val sequence: Long, val expectedPageChangeRevision: Long,
    val characterOffset: Int = 0)
data class ReadingProgressUiState(
    val readerId: String? = null,
    val phase: ReadingProgressPhase = ReadingProgressPhase.Inactive,
    val restore: ReadingProgressRestore? = null,
    val localPage: Int? = null,
    val serverPage: Int? = null,
    val pendingDocuments: Int = 0,
)

/** A navigation performed before Compose observes it still wins over a late server restore. */
fun applyServerReadingProgressRestore(reader: com.dongholab.pagetuner.reader.ReaderViewModel,
    sync: ServerReadingProgressSync, restore: ReadingProgressRestore): Boolean {
    val current = reader.uiState.value
    if (current.document.id != restore.readerId) return false
    if (current.pageChangeRevision != restore.expectedPageChangeRevision) {
        if (current.displayPosition?.fromEnd != true) {
            sync.pageChanged(current.document.id, current.safePageIndex, current.pageChangeRevision, current.characterOffset)
        }
        return false
    }
    reader.changeReadingPosition(restore.pageIndex, restore.characterOffset, userInitiated = false)
    return true
}

/** A serialized device journal keeps late responses and offline writes out of another account/session. */
class ServerReadingProgressSync(private val scope: CoroutineScope, private val storage: ServerReadingProgressStore,
    private val storageDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Open(val document: ServerReadingDocument, val connection: ServerReadingConnection, val page: Int, val revision: Long,
            val characterOffset: Int) : Action
        data class Connect(val connection: ServerReadingConnection?) : Action
        data object Close : Action
        data class Page(val readerId: String, val page: Int, val revision: Long?, val characterOffset: Int) : Action
        data class Retry(val ticket: Long? = null) : Action
        data class Resolve(val local: Boolean) : Action
        data class Received(val ticket: Long, val mutation: ServerReadingMutation?, val result: Result<ServerReadingProgress>) : Action
        data class Send(val ticket: Long) : Action
        data class ScanOutbox(val ticket: Long) : Action
        data class OutboxReceived(val ticket: Long, val target: ServerReadingTarget, val mutation: ServerReadingMutation,
            val expected: ReadingProgressJournalSnapshot, val result: Result<ServerReadingProgress>) : Action
        data object Reevaluate : Action
    }
    private data class Session(
        var ticket: Long, val document: ServerReadingDocument, val connection: ServerReadingConnection,
        var record: DeviceReadingProgress, var page: Int, var restoredPage: Int? = null, var failedStorage: Boolean = false,
        var sentMutationId: String? = null,
        var pageChangeRevision: Long = 0,
        var terminalFailure: Boolean = false,
        var characterOffset: Int = 0,
        var durableBase: ReadingProgressJournalSnapshot? = null,
    )
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(ReadingProgressUiState())
    val state = mutableState.asStateFlow()
    private var session: Session? = null
    @Volatile private var generation = 0L
    private var restoreSequence = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var accountConnection: ServerReadingConnection? = null
    private var outboxNetwork: Job? = null
    private var outboxTimer: Job? = null
    private val attemptedOutbox = mutableSetOf<String>()
    private val terminalOutbox = mutableSetOf<String>()
    private val outboxBases = mutableMapOf<String, ReadingProgressJournalSnapshot>()
    private class JournalChanged : Exception()

    init { scope.launch {
        for (action in actions) {
            try { handle(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: JournalChanged) { actions.trySend(Action.Reevaluate) }
            catch (_: Exception) {
                session?.failedStorage = true
                stop()
                mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.DeviceError,
                    restore = null, localPage = null, serverPage = null)
            }
        }
    } }

    fun open(document: ServerReadingDocument, connection: ServerReadingConnection, page: Int, revision: Long = 0, characterOffset: Int = 0) {
        actions.trySend(Action.Open(document, connection, page, revision, characterOffset)) }
    fun connect(connection: ServerReadingConnection?) { actions.trySend(Action.Connect(connection)) }
    fun close() { actions.trySend(Action.Close) }
    fun pageChanged(readerId: String, page: Int, revision: Long? = null, characterOffset: Int = 0) {
        actions.trySend(Action.Page(readerId, page, revision, characterOffset)) }
    fun retry() { actions.trySend(Action.Retry()) }
    fun chooseLocal() { actions.trySend(Action.Resolve(true)) }
    fun chooseServer() { actions.trySend(Action.Resolve(false)) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (accountConnection?.client === action.connection?.client && accountConnection?.accountKey == action.connection?.accountKey) return
                stop(); session = null
                terminalOutbox.clear()
                outboxBases.clear()
                accountConnection = action.connection
                mutableState.value = ReadingProgressUiState()
                pumpOutbox()
            }
            is Action.Open -> {
                if (action.document.accountKey != action.connection.accountKey) return
                val current = session
                if (current?.document?.openId == action.document.openId && !current.failedStorage && current.connection.client === action.connection.client &&
                    current.connection.accountKey == action.connection.accountKey) {
                    ensureFresh(current)
                    return
                }
                current?.durableBase?.let { outboxBases[current.document.key] = it }
                stop()
                if (accountConnection?.client !== action.connection.client || accountConnection?.accountKey != action.connection.accountKey) {
                    terminalOutbox.clear(); outboxBases.clear()
                }
                accountConnection = action.connection
                terminalOutbox.remove(action.document.key)
                outboxBases.remove(action.document.key)
                val next = Session(generation, action.document, action.connection, DeviceReadingProgress(), action.page)
                next.pageChangeRevision = action.revision
                next.characterOffset = action.characterOffset
                session = next
                mutableState.value = ReadingProgressUiState(action.document.readerId, ReadingProgressPhase.Loading)
                install(next, capture(action.document.target()))
                // A persisted mutation may have reached the server before the previous process exited.
                next.sentMutationId = next.record.pending?.mutationId
                next.record.localAnchor?.let { restore(next, it) }
                if (next.record.conflict != null) showConflict(next) else if (next.record.pending != null) send(next) else fetch(next)
                pumpOutbox()
            }
            Action.Close -> {
                session?.let { current -> current.durableBase?.let { outboxBases[current.document.key] = it } }
                stop(); session = null; mutableState.value = ReadingProgressUiState(); pumpOutbox()
            }
            is Action.Page -> {
                val current = session ?: return
                if (current.document.readerId != action.readerId || current.failedStorage) return
                ensureFresh(current)
                if (current.document.readerId != action.readerId || current.failedStorage ||
                    action.page == current.page && action.characterOffset == current.characterOffset &&
                        (action.revision == null || action.revision == current.pageChangeRevision)) return
                if (action.revision != null && action.revision < current.pageChangeRevision) return
                val userMoved = action.revision != null && action.revision != current.pageChangeRevision
                if (action.revision != null) current.pageChangeRevision = action.revision
                current.page = action.page
                current.characterOffset = action.characterOffset
                if (action.revision != null && !userMoved) {
                    if (action.page == current.restoredPage) current.restoredPage = null
                    return
                }
                if (action.page == current.restoredPage && !userMoved) { current.restoredPage = null; return }
                current.restoredPage = null
                val anchor = current.document.anchor(action.page, action.characterOffset) ?: return
                current.document.validate(anchor)
                val previous = current.record
                if (previous.localAnchor == anchor) return
                val pending = previous.pending
                val next = when {
                    pending == null -> previous.copy(pending = mutation(previous.remote?.version ?: 0, anchor))
                    previous.conflict == null && pending.mutationId != current.sentMutationId ->
                        previous.copy(pending = mutation(pending.expectedVersion, anchor), queuedAnchor = null)
                    else -> previous.copy(queuedAnchor = anchor.takeUnless { it == pending.anchor })
                }
                persist(current, next)
                mutableState.value = mutableState.value.copy(restore = null)
                if (current.terminalFailure) {
                    mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Unavailable)
                } else if (next.conflict != null) showConflict(current) else {
                    mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Pending)
                    schedule(current, 700, retry = false)
                }
            }
            is Action.Send -> session?.takeIf { it.ticket == action.ticket && !it.failedStorage }?.let {
                ensureFresh(it)
                if (it.record.conflict == null) send(it) else showConflict(it)
            }
            is Action.Retry -> {
                session?.takeIf { (action.ticket == null || action.ticket == it.ticket) && !it.failedStorage }?.let {
                    ensureFresh(it)
                    if (action.ticket == null) it.terminalFailure = false
                    if (it.record.conflict != null) showConflict(it) else if (it.record.pending != null) send(it) else fetch(it)
                }
                if (action.ticket == null) { terminalOutbox.clear(); attemptedOutbox.clear(); pumpOutbox() }
            }
            is Action.Resolve -> {
                val current = session ?: return
                if (current.failedStorage) return
                ensureFresh(current)
                val conflict = current.record.conflict ?: return
                if (action.local) {
                    val anchor = requireNotNull(current.record.localAnchor)
                    persist(current, DeviceReadingProgress(remote = conflict, pending = mutation(conflict.version, anchor),
                        retryAfterUntil = current.record.retryAfterUntil))
                    send(current)
                } else {
                    persist(current, DeviceReadingProgress(remote = conflict, retryAfterUntil = current.record.retryAfterUntil))
                    (conflict.anchor ?: current.document.anchor(0))?.let { restore(current, it) }
                    mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Synced, localPage = null, serverPage = null)
                }
            }
            is Action.Received -> {
                val current = session?.takeIf { it.ticket == action.ticket && !it.failedStorage } ?: return
                network = null
                ensureFresh(current)
                action.result.fold(onSuccess = { value ->
                    value.anchor?.let(current.document::validate)
                    if (action.mutation == null) {
                        val pending = current.record.pending
                        persist(current, current.record.copy(remote = value))
                        if (pending == null) {
                            value.anchor?.let { restore(current, it) }
                            mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Synced)
                        } else send(current)
                    } else {
                        if (current.record.pending != action.mutation) return@fold
                        val queued = current.record.queuedAnchor?.takeUnless { it == value.anchor }
                        persist(current, DeviceReadingProgress(remote = value, pending = queued?.let { mutation(value.version, it) }))
                        mutableState.value = mutableState.value.copy(phase = if (queued == null) ReadingProgressPhase.Synced else ReadingProgressPhase.Pending)
                        if (queued != null) send(current)
                    }
                }, onFailure = { error ->
                    if (error is ServerReadingProgressRateLimited) {
                        persist(current, current.record.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1_000))
                        waitForRetryDeadline(current)
                    } else if (error is ServerReadingProgressConflict) {
                        error.current.anchor?.let(current.document::validate)
                        persist(current, current.record.copy(conflict = error.current))
                        showConflict(current)
                    } else {
                        val transient = error is TranslationStoreException && error.failure in setOf(
                            TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER)
                        mutableState.value = mutableState.value.copy(phase = if (transient) ReadingProgressPhase.Offline else ReadingProgressPhase.Unavailable)
                        if (transient) schedule(current, 15_000, retry = true) else {
                            current.terminalFailure = true
                            terminalOutbox += current.document.key
                            timer?.cancel(); timer = null
                        }
                    }
                })
            }
            is Action.ScanOutbox -> if (action.ticket == generation) { attemptedOutbox.clear(); pumpOutbox() }
            is Action.OutboxReceived -> {
                if (action.ticket != generation || action.target.accountKey != accountConnection?.accountKey) return
                outboxNetwork = null
                val latest = capture(action.target)
                if (latest != action.expected) changed(action.target, latest)
                val record = latest.canonical ?: DeviceReadingProgress()
                if (record.pending == action.mutation && record.conflict == null) {
                    action.result.fold(onSuccess = { value ->
                        val queued = record.queuedAnchor?.takeUnless { it == value.anchor }
                        val next = DeviceReadingProgress(remote = value, pending = queued?.let { mutation(value.version, it) })
                        persistOutbox(action.target, latest, next)
                    }, onFailure = { error ->
                        if (error is ServerReadingProgressConflict) {
                            persistOutbox(action.target, latest, record.copy(conflict = error.current))
                        } else if (error is ServerReadingProgressRateLimited) {
                            val next = record.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1_000)
                            persistOutbox(action.target, latest, next)
                        } else if (error !is TranslationStoreException || error.failure !in setOf(
                                TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER)) {
                            terminalOutbox += action.target.key
                        }
                        attemptedOutbox += action.target.key
                    })
                }
                pumpOutbox()
            }
            Action.Reevaluate -> {
                session?.takeUnless { it.failedStorage }?.let { current ->
                    ensureFresh(current)
                    if (current.record.conflict != null) showConflict(current)
                    else if (current.record.pending != null) send(current) else fetch(current)
                }
                pumpOutbox()
            }
        }
    }

    private suspend fun pumpOutbox() {
        val connection = accountConnection ?: return
        // pending() is discovery only; it is neither a complete observation nor the durable base.
        // Canonicalize the query scope only. capture() rejects any historical noncanonical row.
        val discovered = withContext(storageDispatcher) { storage.pending(connection.accountKey) }
            .map { it.copy(recordId = it.recordId.lowercase(java.util.Locale.ROOT)) }
        val targets = (outboxBases.values.map { it.target } + discovered).distinctBy { it.key }
            .filter { it.accountKey == connection.accountKey && it.key != session?.document?.key }
        for (target in targets) {
            val latest = capture(target)
            if (outboxBases.containsKey(target.key) && outboxBases[target.key] != latest) changed(target, latest)
            outboxBases[target.key] = latest
        }
        mutableState.value = mutableState.value.copy(pendingDocuments = targets.count { outboxBases.getValue(it.key).canonical?.pending != null })
        if (outboxNetwork != null) return
        var automaticRetryNeeded = false
        for (target in targets) {
            val expected = outboxBases.getValue(target.key)
            val record = expected.canonical ?: DeviceReadingProgress()
            if (target.key in terminalOutbox) continue
            if (record.conflict != null) { attemptedOutbox += target.key; continue }
            val pending = record.pending ?: continue
            automaticRetryNeeded = true
            if (target.key in attemptedOutbox) continue
            if ((record.retryAfterUntil ?: 0) > clock()) continue
            val ticket = generation
            outboxNetwork = scope.launch {
                val result = try { Result.success(connection.client.saveReadingProgress(target.kind, target.recordId, pending)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                actions.send(Action.OutboxReceived(ticket, target, pending, expected, result))
            }
            return
        }
        outboxTimer?.cancel()
        if (automaticRetryNeeded) {
            val ticket = generation
            outboxTimer = scope.launch { delay(15_000); actions.send(Action.ScanOutbox(ticket)) }
        }
    }

    private suspend fun fetch(current: Session) {
        ensureFresh(current)
        if (network != null || current.terminalFailure) return
        if (waitForRetryDeadline(current)) return
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Loading)
        request(current, null)
    }
    private suspend fun send(current: Session) {
        ensureFresh(current)
        if (network != null || current.terminalFailure) return
        if (waitForRetryDeadline(current)) return
        val pending = current.record.pending ?: return
        timer?.cancel()
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Pending, localPage = null, serverPage = null)
        request(current, pending)
    }
    private fun request(current: Session, pending: ServerReadingMutation?) {
        if (pending != null) current.sentMutationId = pending.mutationId
        val ticket = current.ticket
        network = scope.launch {
            val result = try {
                val entry = current.document.source.entry
                Result.success(if (pending == null) current.connection.client.readingProgress(entry.kind.progressKind(), entry.recordId)
                    else current.connection.client.saveReadingProgress(entry.kind.progressKind(), entry.recordId, pending))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            actions.send(Action.Received(ticket, pending, result))
        }
    }
    private suspend fun persist(current: Session, next: DeviceReadingProgress) {
        validate(current, next)
        val expected = requireNotNull(current.durableBase)
        val ticket = current.ticket
        val actor = currentCoroutineContext()
        val accepted = withContext(storageDispatcher) {
            storage.compareAndWrite(current.document.target(), expected, next) {
                actor.ensureActive()
                check(generation == ticket && !current.failedStorage)
            }
        }
        if (!accepted) changed(current.document.target(), capture(current.document.target()))
        // Derive the exact committed base; a recapture here could borrow a subsequent writer's row.
        current.durableBase = committed(expected, next)
        current.record = next
    }
    private suspend fun persistOutbox(target: ServerReadingTarget, expected: ReadingProgressJournalSnapshot, next: DeviceReadingProgress) {
        val ticket = generation
        val actor = currentCoroutineContext()
        val accepted = withContext(storageDispatcher) {
            storage.compareAndWrite(target, expected, next) {
                actor.ensureActive(); check(generation == ticket)
            }
        }
        if (!accepted) changed(target, capture(target))
        outboxBases[target.key] = committed(expected, next)
    }
    private fun committed(expected: ReadingProgressJournalSnapshot, next: DeviceReadingProgress) =
        ReadingProgressJournalSnapshot(expected.target, expected.aliases.filterNot { it.target == expected.target } +
            ReadingProgressJournalObservation(expected.target, next))

    private suspend fun capture(target: ServerReadingTarget): ReadingProgressJournalSnapshot {
        val result = withContext(storageDispatcher) { storage.captureSnapshot(target) }
        // Deliberately conservative: keep historical files intact until an explicit alias policy exists.
        require(result.target == target && result.aliases.all { it.target == target }) {
            "Historical progress aliases require explicit recovery before normal synchronization."
        }
        return result
    }
    private fun validate(current: Session, record: DeviceReadingProgress) {
        listOfNotNull(record.remote?.anchor, record.pending?.anchor, record.queuedAnchor, record.conflict?.anchor)
            .forEach(current.document::validate)
    }
    private fun install(current: Session, snapshot: ReadingProgressJournalSnapshot) {
        val record = snapshot.canonical ?: DeviceReadingProgress()
        validate(current, record)
        current.durableBase = snapshot
        current.record = record
    }
    /** An external change invalidates the action; do not apply its calculated side effects. */
    private suspend fun ensureFresh(current: Session) {
        val latest = capture(current.document.target())
        if (latest != current.durableBase) changed(current.document.target(), latest)
    }
    private fun changed(target: ServerReadingTarget, latest: ReadingProgressJournalSnapshot): Nothing {
        stop()
        terminalOutbox.remove(target.key)
        outboxBases[target.key] = latest
        session?.let { current ->
            current.ticket = generation
            current.restoredPage = null
            if (current.document.target() == target) {
                current.terminalFailure = false
                install(current, latest)
                current.sentMutationId = current.record.pending?.mutationId
            }
        }
        mutableState.value = mutableState.value.copy(restore = null, localPage = null, serverPage = null)
        throw JournalChanged()
    }
    private fun restore(current: Session, anchor: ServerReadingAnchor) {
        current.document.validate(anchor)
        val page = current.document.page(anchor)
        current.restoredPage = page
        mutableState.value = mutableState.value.copy(restore = ReadingProgressRestore(current.document.readerId, page, ++restoreSequence,
            current.pageChangeRevision, current.document.characterOffset(anchor)))
    }
    private fun showConflict(current: Session) {
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Conflict,
            localPage = current.record.localAnchor?.let(current.document::page), serverPage = current.record.conflict?.anchor?.let(current.document::page))
    }
    private fun schedule(current: Session, milliseconds: Long, retry: Boolean) {
        timer?.cancel()
        val ticket = current.ticket
        timer = scope.launch { delay(milliseconds); actions.send(if (retry) Action.Retry(ticket) else Action.Send(ticket)) }
    }
    private fun waitForRetryDeadline(current: Session): Boolean {
        val remaining = (current.record.retryAfterUntil ?: 0) - clock()
        if (remaining <= 0) return false
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.RateLimited)
        schedule(current, remaining, retry = true)
        return true
    }
    private fun mutation(version: Long, anchor: ServerReadingAnchor) = ServerReadingMutation(version, UUID.randomUUID().toString(), anchor)
    private fun stop() {
        generation++; network?.cancel(); network = null; timer?.cancel(); timer = null
        outboxNetwork?.cancel(); outboxNetwork = null; outboxTimer?.cancel(); outboxTimer = null; attemptedOutbox.clear()
    }
}

class ServerReadingProgressViewModel(storage: ServerReadingProgressStore) : ViewModel() {
    val sync = ServerReadingProgressSync(viewModelScope, storage)
    private val mutableDocument = MutableStateFlow<ServerReadingDocument?>(null)
    val document = mutableDocument.asStateFlow()
    fun retainDocument(value: ServerReadingDocument) { mutableDocument.value = value }
    class Factory(private val storage: ServerReadingProgressStore) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerReadingProgressViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return ServerReadingProgressViewModel(storage) as T
        }
    }
}
