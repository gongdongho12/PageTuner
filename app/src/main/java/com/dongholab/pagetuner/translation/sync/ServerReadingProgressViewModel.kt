package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReadingProgressPhase { Inactive, Loading, Synced, Pending, Offline, RateLimited, Conflict, Unavailable, DeviceError }
data class ReadingProgressRestore(val readerId: String, val pageIndex: Int, val sequence: Long, val expectedPageChangeRevision: Long)
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
        sync.pageChanged(current.document.id, current.safePageIndex, current.pageChangeRevision)
        return false
    }
    reader.changePage(restore.pageIndex, userInitiated = false)
    return true
}

/** A serialized device journal keeps late responses and offline writes out of another account/session. */
class ServerReadingProgressSync(private val scope: CoroutineScope, private val storage: ServerReadingProgressStore,
    private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Open(val document: ServerReadingDocument, val connection: ServerReadingConnection, val page: Int, val revision: Long) : Action
        data class Connect(val connection: ServerReadingConnection?) : Action
        data object Close : Action
        data class Page(val readerId: String, val page: Int, val revision: Long?) : Action
        data class Retry(val ticket: Long? = null) : Action
        data class Resolve(val local: Boolean) : Action
        data class Received(val ticket: Long, val mutation: ServerReadingMutation?, val result: Result<ServerReadingProgress>) : Action
        data class Send(val ticket: Long) : Action
        data class ScanOutbox(val ticket: Long) : Action
        data class OutboxReceived(val ticket: Long, val target: ServerReadingTarget, val mutation: ServerReadingMutation,
            val result: Result<ServerReadingProgress>) : Action
    }
    private data class Session(
        val ticket: Long, val document: ServerReadingDocument, val connection: ServerReadingConnection,
        var record: DeviceReadingProgress, var page: Int, var restoredPage: Int? = null, var failedStorage: Boolean = false,
        var sentMutationId: String? = null,
        var pageChangeRevision: Long = 0,
        var terminalFailure: Boolean = false,
    )
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(ReadingProgressUiState())
    val state = mutableState.asStateFlow()
    private var session: Session? = null
    private var generation = 0L
    private var restoreSequence = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var accountConnection: ServerReadingConnection? = null
    private var outboxNetwork: Job? = null
    private var outboxTimer: Job? = null
    private val attemptedOutbox = mutableSetOf<String>()
    private val terminalOutbox = mutableSetOf<String>()

    init { scope.launch {
        for (action in actions) {
            try { handle(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                session?.failedStorage = true
                stop()
                mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.DeviceError)
            }
        }
    } }

    fun open(document: ServerReadingDocument, connection: ServerReadingConnection, page: Int, revision: Long = 0) { actions.trySend(Action.Open(document, connection, page, revision)) }
    fun connect(connection: ServerReadingConnection?) { actions.trySend(Action.Connect(connection)) }
    fun close() { actions.trySend(Action.Close) }
    fun pageChanged(readerId: String, page: Int, revision: Long? = null) { actions.trySend(Action.Page(readerId, page, revision)) }
    fun retry() { actions.trySend(Action.Retry()) }
    fun chooseLocal() { actions.trySend(Action.Resolve(true)) }
    fun chooseServer() { actions.trySend(Action.Resolve(false)) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (accountConnection?.client === action.connection?.client) return
                stop(); session = null
                terminalOutbox.clear()
                accountConnection = action.connection
                mutableState.value = ReadingProgressUiState()
                pumpOutbox()
            }
            is Action.Open -> {
                if (action.document.accountKey != action.connection.accountKey) return
                val current = session
                if (current?.document?.openId == action.document.openId && current.connection.client === action.connection.client) return
                stop()
                if (accountConnection?.client !== action.connection.client) terminalOutbox.clear()
                accountConnection = action.connection
                terminalOutbox.remove(action.document.key)
                val next = Session(generation, action.document, action.connection, DeviceReadingProgress(), action.page)
                next.pageChangeRevision = action.revision
                session = next
                mutableState.value = ReadingProgressUiState(action.document.readerId, ReadingProgressPhase.Loading)
                next.record = withContext(Dispatchers.IO) { storage.read(action.document) }
                // A persisted mutation may have reached the server before the previous process exited.
                next.sentMutationId = next.record.pending?.mutationId
                next.record.localAnchor?.let { restore(next, it) }
                if (next.record.conflict != null) showConflict(next) else if (next.record.pending != null) send(next) else fetch(next)
                pumpOutbox()
            }
            Action.Close -> { stop(); session = null; mutableState.value = ReadingProgressUiState(); pumpOutbox() }
            is Action.Page -> {
                val current = session ?: return
                if (current.document.readerId != action.readerId || current.failedStorage ||
                    action.page == current.page && (action.revision == null || action.revision == current.pageChangeRevision)) return
                if (action.revision != null && action.revision < current.pageChangeRevision) return
                val userMoved = action.revision != null && action.revision != current.pageChangeRevision
                if (action.revision != null) current.pageChangeRevision = action.revision
                current.page = action.page
                if (action.revision != null && !userMoved) {
                    if (action.page == current.restoredPage) current.restoredPage = null
                    return
                }
                if (action.page == current.restoredPage && !userMoved) { current.restoredPage = null; return }
                current.restoredPage = null
                val anchor = current.document.anchor(action.page) ?: return
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
            is Action.Send -> session?.takeIf { it.ticket == action.ticket && it.record.conflict == null && !it.failedStorage }?.let { send(it) }
            is Action.Retry -> {
                session?.takeIf { (action.ticket == null || action.ticket == it.ticket) && !it.failedStorage }?.let {
                    if (action.ticket == null) it.terminalFailure = false
                    if (it.record.conflict != null) showConflict(it) else if (it.record.pending != null) send(it) else fetch(it)
                }
                if (action.ticket == null) { terminalOutbox.clear(); attemptedOutbox.clear(); pumpOutbox() }
            }
            is Action.Resolve -> {
                val current = session ?: return
                if (current.failedStorage) return
                val conflict = current.record.conflict ?: return
                if (action.local) {
                    val anchor = requireNotNull(current.record.localAnchor)
                    persist(current, DeviceReadingProgress(remote = conflict, pending = mutation(conflict.version, anchor)))
                    send(current)
                } else {
                    persist(current, DeviceReadingProgress(remote = conflict))
                    (conflict.anchor ?: current.document.anchor(0))?.let { restore(current, it) }
                    mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Synced, localPage = null, serverPage = null)
                }
            }
            is Action.Received -> {
                val current = session?.takeIf { it.ticket == action.ticket && !it.failedStorage } ?: return
                network = null
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
                val record = withContext(Dispatchers.IO) { storage.read(action.target) }
                if (record.pending == action.mutation && record.conflict == null) {
                    action.result.fold(onSuccess = { value ->
                        val queued = record.queuedAnchor?.takeUnless { it == value.anchor }
                        val next = DeviceReadingProgress(remote = value, pending = queued?.let { mutation(value.version, it) })
                        withContext(Dispatchers.IO) { storage.write(action.target, next) }
                    }, onFailure = { error ->
                        attemptedOutbox += action.target.key
                        if (error is ServerReadingProgressConflict) {
                            withContext(Dispatchers.IO) { storage.write(action.target, record.copy(conflict = error.current)) }
                        } else if (error is ServerReadingProgressRateLimited) {
                            val next = record.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1_000)
                            withContext(Dispatchers.IO) { storage.write(action.target, next) }
                        } else if (error !is TranslationStoreException || error.failure !in setOf(
                                TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER)) {
                            terminalOutbox += action.target.key
                        }
                    })
                }
                pumpOutbox()
            }
        }
    }

    private suspend fun pumpOutbox() {
        val connection = accountConnection ?: return
        if (outboxNetwork != null) return
        val targets = withContext(Dispatchers.IO) { storage.pending(connection.accountKey) }
            .filter { it.key != session?.document?.key }
        mutableState.value = mutableState.value.copy(pendingDocuments = targets.size)
        var automaticRetryNeeded = false
        for (target in targets) {
            if (target.key in terminalOutbox) continue
            val record = withContext(Dispatchers.IO) { storage.read(target) }
            if (record.conflict != null) { attemptedOutbox += target.key; continue }
            automaticRetryNeeded = true
            if (target.key in attemptedOutbox) continue
            if ((record.retryAfterUntil ?: 0) > clock()) continue
            val pending = record.pending ?: continue
            val ticket = generation
            outboxNetwork = scope.launch {
                val result = try { Result.success(connection.client.saveReadingProgress(target.kind, target.recordId, pending)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                actions.send(Action.OutboxReceived(ticket, target, pending, result))
            }
            return
        }
        outboxTimer?.cancel()
        if (automaticRetryNeeded) {
            val ticket = generation
            outboxTimer = scope.launch { delay(15_000); actions.send(Action.ScanOutbox(ticket)) }
        }
    }

    private fun fetch(current: Session) {
        if (network != null || current.terminalFailure) return
        if (waitForRetryDeadline(current)) return
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Loading)
        request(current, null)
    }
    private fun send(current: Session) {
        if (network != null || current.terminalFailure) return
        if (waitForRetryDeadline(current)) return
        val pending = current.record.pending ?: return
        timer?.cancel()
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Pending, localPage = null, serverPage = null)
        request(current, pending)
    }
    private fun request(current: Session, pending: ServerReadingMutation?) {
        if (pending != null) current.sentMutationId = pending.mutationId
        network = scope.launch {
            val result = try {
                val entry = current.document.source.entry
                Result.success(if (pending == null) current.connection.client.readingProgress(entry.kind.progressKind(), entry.recordId)
                    else current.connection.client.saveReadingProgress(entry.kind.progressKind(), entry.recordId, pending))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            actions.send(Action.Received(current.ticket, pending, result))
        }
    }
    private suspend fun persist(current: Session, next: DeviceReadingProgress) {
        withContext(Dispatchers.IO) { storage.write(current.document, next) }
        current.record = next
    }
    private fun restore(current: Session, anchor: ServerReadingAnchor) {
        current.document.validate(anchor)
        val page = current.document.page(anchor)
        current.restoredPage = page
        mutableState.value = mutableState.value.copy(restore = ReadingProgressRestore(current.document.readerId, page, ++restoreSequence, current.pageChangeRevision))
    }
    private fun showConflict(current: Session) {
        mutableState.value = mutableState.value.copy(phase = ReadingProgressPhase.Conflict,
            localPage = current.record.localAnchor?.let(current.document::page), serverPage = current.record.conflict?.anchor?.let(current.document::page))
    }
    private fun schedule(current: Session, milliseconds: Long, retry: Boolean) {
        timer?.cancel()
        timer = scope.launch { delay(milliseconds); actions.send(if (retry) Action.Retry(current.ticket) else Action.Send(current.ticket)) }
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
