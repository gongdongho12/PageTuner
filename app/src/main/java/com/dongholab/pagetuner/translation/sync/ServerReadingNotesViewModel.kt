package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerReadingNoteConflictView(val noteId: String, val local: ServerReadingNoteDesired, val remote: ServerReadingNote)
data class ServerReadingNotesUiState(val readerId: String? = null, val items: List<ServerReadingNote> = emptyList(),
    val conflicts: List<ServerReadingNoteConflictView> = emptyList(), val pendingNoteIds: Set<String> = emptySet(),
    val phase: ReadingProgressPhase = ReadingProgressPhase.Inactive, val pendingDocuments: Int = 0,
    val staleActionRejected: Boolean = false)

/** Explicit item intents and an atomic feed cursor prevent restored lists from becoming delete commands. */
class ServerReadingNotesSync(private val scope: CoroutineScope, private val storage: ServerReadingNotesStore,
    private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Connect(val connection: ServerReadingConnection?) : Action
        data class Open(val document: ServerReadingDocument, val connection: ServerReadingConnection) : Action
        data object Close : Action
        data object Retry : Action
        data class Tick(val ticket: Long) : Action
        data class Create(val readerId: String, val kind: ServerReadingNoteKind, val page: Int, val title: String, val text: String, val characterOffset: Int) : Action
        data class Edit(val readerId: String, val noteId: String, val title: String, val text: String, val base: ServerReadingNote?) : Action
        data class Delete(val readerId: String, val noteId: String, val base: ServerReadingNote?) : Action
        data class Resolve(val readerId: String, val noteId: String, val local: Boolean, val shown: ServerReadingNoteConflictView) : Action
        data class Pulled(val ticket: Long, val target: ServerReadingTarget, val after: Long, val result: Result<ServerReadingNotesPage>) : Action
        data class Pushed(val ticket: Long, val target: ServerReadingTarget, val noteId: String, val mutation: ServerReadingNoteMutation,
            val result: Result<ServerReadingNote>) : Action
    }
    private val queue = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(ServerReadingNotesUiState())
    val state = mutableState.asStateFlow()
    private var connection: ServerReadingConnection? = null
    private var active: ServerReadingDocument? = null
    private val targets = linkedMapOf<String, ServerReadingTarget>()
    private val journals = linkedMapOf<String, DeviceReadingNotes>()
    private val terminal = mutableSetOf<String>()
    private val retryAt = mutableMapOf<String, Long>()
    private var failedStorage = false
    private var ticket = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var pullNeeded = false
    private var watermark: Long? = null
    private var nextPollAt = 0L
    private var staleActionRejected = false

    init { scope.launch {
        for (action in queue) {
            try { handle(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedStorage = true; stop(); publish() }
        }
    } }

    fun connect(connection: ServerReadingConnection?) { queue.trySend(Action.Connect(connection)) }
    fun open(document: ServerReadingDocument, connection: ServerReadingConnection) { queue.trySend(Action.Open(document, connection)) }
    fun close() { queue.trySend(Action.Close) }
    fun retry() { queue.trySend(Action.Retry) }
    fun create(readerId: String, kind: ServerReadingNoteKind, page: Int, title: String, text: String, characterOffset: Int = 0) {
        queue.trySend(Action.Create(readerId, kind, page, title, text, characterOffset)) }
    fun edit(readerId: String, noteId: String, title: String, text: String, base: ServerReadingNote? = null) { queue.trySend(Action.Edit(readerId, noteId, title, text, base)) }
    fun delete(readerId: String, noteId: String, base: ServerReadingNote? = null) { queue.trySend(Action.Delete(readerId, noteId, base)) }
    fun resolve(readerId: String, noteId: String, local: Boolean, shown: ServerReadingNoteConflictView) { queue.trySend(Action.Resolve(readerId, noteId, local, shown)) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (connection?.client === action.connection?.client) return
                configure(action.connection); pump()
            }
            is Action.Open -> {
                if (action.document.accountKey != action.connection.accountKey) return
                if (active?.openId == action.document.openId && connection?.client === action.connection.client) return
                if (connection?.client !== action.connection.client) configure(action.connection) else stop()
                active = action.document; failedStorage = false; staleActionRejected = false
                targets[action.document.key] = action.document.target()
                // Reopening recovers from a failed journal write using the last atomic disk snapshot.
                journals[action.document.key] = withContext(Dispatchers.IO) { storage.read(action.document.target()) }
                terminal.remove(action.document.key); retryAt.remove(action.document.key)
                pullNeeded = true; watermark = null; nextPollAt = 0
                publish(); pump()
            }
            Action.Close -> { stop(); active = null; pullNeeded = false; watermark = null; staleActionRejected = false; publish(); pump() }
            Action.Retry -> { if (failedStorage) return; terminal.clear(); retryAt.clear(); if (active != null) pullNeeded = true; pump() }
            is Action.Tick -> { if (action.ticket != ticket) return; if (active != null && clock() >= nextPollAt) pullNeeded = true; pump() }
            is Action.Create -> {
                val document = active?.takeIf { it.readerId == action.readerId && !failedStorage } ?: return
                staleActionRejected = false
                val content = document.noteContent(action.kind, action.page, action.title, action.text, action.characterOffset)
                change(document, UUID.randomUUID().toString(), ServerReadingNoteDesired(false, content)); pump()
            }
            is Action.Edit -> {
                val document = active?.takeIf { it.readerId == action.readerId && !failedStorage } ?: return
                if (!acceptsLocalBase(document, action.noteId, action.base)) { staleActionRejected = true; publish(); return }
                staleActionRejected = false
                val previous = action.base?.takeIf { it.noteId == action.noteId }?.note ?: journals.getValue(document.key).desired(action.noteId)?.note ?: return
                val content = previous.copy(title = action.title.trim(), text = action.text.trim())
                ServerReadingNotesJson.validate(content)
                change(document, action.noteId, ServerReadingNoteDesired(false, content), action.base?.version); pump()
            }
            is Action.Delete -> {
                val document = active?.takeIf { it.readerId == action.readerId && !failedStorage } ?: return
                if (!acceptsLocalBase(document, action.noteId, action.base)) { staleActionRejected = true; publish(); return }
                staleActionRejected = false
                if (journals.getValue(document.key).desired(action.noteId) == null) return
                change(document, action.noteId, ServerReadingNoteDesired(true, null), action.base?.version); pump()
            }
            is Action.Resolve -> {
                val document = active?.takeIf { it.readerId == action.readerId && !failedStorage } ?: return
                val previous = journals.getValue(document.key)
                val remote = previous.conflicts[action.noteId] ?: return
                val desired = requireNotNull(previous.desired(action.noteId))
                // A choice only approves the two versions actually displayed to the user.
                if (action.shown.noteId != action.noteId || action.shown.remote != remote || !same(action.shown.local, desired)) { staleActionRejected = true; publish(); return }
                staleActionRejected = false
                val items = if (remote.version == 0L) previous.items - action.noteId else merge(previous.items, remote)
                val pending = previous.pending - action.noteId
                persist(document.target(), previous.copy(items = items,
                    pending = if (action.local) pending + (action.noteId to mutation(remote.version, desired)) else pending,
                    queued = previous.queued - action.noteId, conflicts = previous.conflicts - action.noteId))
                terminal.remove(document.key); retryAt.remove(document.key); pump()
            }
            is Action.Pulled -> {
                if (action.ticket != ticket || failedStorage) return
                network = null
                action.result.fold(onSuccess = { page ->
                    val previous = journals.getValue(action.target.key)
                    require(previous.afterRevision == action.after)
                    val document = requireNotNull(active?.takeIf { it.key == action.target.key })
                    var items = previous.items
                    page.items.forEach { item -> item.note?.let(document::noteExcerpt); items = merge(items, item) }
                    val conflicts = previous.conflicts.mapValues { (id, remote) -> items[id]?.takeIf { it.version > remote.version } ?: remote }
                    persist(action.target, previous.copy(items = items, conflicts = conflicts, afterRevision = page.nextAfterRevision))
                    watermark = page.watermark.takeIf { page.hasMore }; pullNeeded = page.hasMore
                    if (!page.hasMore) nextPollAt = clock() + 30_000
                    retryAt.remove(action.target.key)
                }, onFailure = { failure(action.target, it) })
                pump()
            }
            is Action.Pushed -> {
                if (action.ticket != ticket || failedStorage) return
                network = null
                val previous = journals.getValue(action.target.key)
                if (previous.pending[action.noteId] != action.mutation) { pump(); return }
                action.result.fold(onSuccess = { item ->
                    val queued = previous.queued[action.noteId]?.takeUnless { same(it, ServerReadingNoteDesired(item.deleted, item.note)) }
                    val pending = previous.pending - action.noteId
                    persist(action.target, previous.copy(items = merge(previous.items, item),
                        pending = if (queued == null) pending else pending + (action.noteId to mutation(item.version, queued)),
                        queued = previous.queued - action.noteId, conflicts = previous.conflicts - action.noteId))
                    retryAt.remove(action.target.key)
                }, onFailure = { error ->
                    if (error is ServerReadingNoteConflict) {
                        active?.takeIf { it.key == action.target.key }?.let { document -> error.current.note?.let(document::noteExcerpt) }
                        persist(action.target, previous.copy(items = if (error.current.version == 0L) previous.items else merge(previous.items, error.current),
                            conflicts = previous.conflicts + (action.noteId to error.current)))
                    } else failure(action.target, error)
                })
                pump()
            }
        }
    }

    private suspend fun configure(value: ServerReadingConnection?) {
        stop(); connection = value; active = null; journals.clear(); targets.clear(); terminal.clear(); retryAt.clear()
        failedStorage = false; pullNeeded = false; watermark = null; nextPollAt = 0; staleActionRejected = false
        if (value != null) {
            val saved = withContext(Dispatchers.IO) { storage.targets(value.accountKey).map { it to storage.read(it) } }
            saved.forEach { (target, journal) -> targets[target.key] = target; journals[target.key] = journal }
        }
        publish()
    }
    private suspend fun change(document: ServerReadingDocument, id: String, desired: ServerReadingNoteDesired, expectedVersion: Long? = null) {
        ServerReadingNotesJson.uuid(id)
        val previous = journals.getValue(document.key)
        if (previous.desired(id)?.let { same(it, desired) } == true) return
        val pending = previous.pending[id]
        persist(document.target(), if (pending == null) previous.copy(pending = previous.pending + (id to mutation(expectedVersion ?: previous.items[id]?.version ?: 0, desired)))
            else previous.copy(queued = previous.queued + (id to desired)))
    }
    private fun acceptsLocalBase(document: ServerReadingDocument, id: String, base: ServerReadingNote?): Boolean {
        if (base == null) return true
        if (base.noteId != id) return false
        val journal = journals.getValue(document.key)
        // A pending mutation and its later queued edit share a server version. Compare the
        // displayed content too, so an old form cannot overwrite a newer unsent device edit.
        // With no local mutation, retain the observed server version and let server CAS report a conflict.
        return id !in journal.pending || journal.desired(id)?.let { same(it, ServerReadingNoteDesired(base.deleted, base.note)) } == true
    }
    private suspend fun failure(target: ServerReadingTarget, error: Throwable) {
        if (error is ServerReadingNotesRateLimited) {
            persist(target, journals.getValue(target.key).copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1000))
        } else if (error is TranslationStoreException && error.failure in setOf(TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER)) {
            retryAt[target.key] = clock() + 15_000
        } else terminal += target.key
    }
    private suspend fun persist(target: ServerReadingTarget, journal: DeviceReadingNotes) {
        withContext(Dispatchers.IO) { storage.write(target, journal) }
        journals[target.key] = journal
        publish()
    }
    private suspend fun pump() {
        publish()
        val account = connection ?: return
        if (network != null || failedStorage) return
        timer?.cancel(); timer = null
        val cooldown = journals.values.maxOfOrNull { it.retryAfterUntil } ?: 0
        if (cooldown > clock()) { schedule(cooldown); return }
        val document = active
        if (document != null && document.key !in terminal && (retryAt[document.key] ?: 0) <= clock() && pullNeeded) {
            val target = document.target(); val after = journals.getValue(target.key).afterRevision; val until = watermark; val currentTicket = ticket
            network = scope.launch {
                val result = networkResult { account.client.readingNotes(target.kind, target.recordId, after, 50, until) }
                queue.send(Action.Pulled(currentTicket, target, after, result))
            }
            return
        }
        val ordered = targets.values.sortedBy { if (it.key == document?.key) 0 else 1 }
        for (target in ordered) {
            if (target.key in terminal || (retryAt[target.key] ?: 0) > clock()) continue
            val journal = journals.getValue(target.key)
            val entry = journal.pending.entries.firstOrNull { it.key !in journal.conflicts } ?: continue
            val currentTicket = ticket
            network = scope.launch {
                val result = networkResult { account.client.saveReadingNote(target.kind, target.recordId, entry.key, entry.value) }
                queue.send(Action.Pushed(currentTicket, target, entry.key, entry.value, result))
            }
            return
        }
        val times = retryAt.filterKeys { it !in terminal }.values.filter { it > clock() }.toMutableList()
        if (document != null && document.key !in terminal) times += maxOf(nextPollAt, retryAt[document.key] ?: 0, clock() + 1)
        times.minOrNull()?.let(::schedule)
    }
    private fun schedule(at: Long) {
        val currentTicket = ticket
        timer?.cancel(); timer = scope.launch { delay((at - clock()).coerceAtLeast(1)); queue.send(Action.Tick(currentTicket)) }
    }
    private fun publish() {
        val document = active; val journal = document?.let { journals[it.key] }
        val phase = when {
            failedStorage -> ReadingProgressPhase.DeviceError
            document == null || journal == null -> ReadingProgressPhase.Inactive
            document.key in terminal -> ReadingProgressPhase.Unavailable
            journal.conflicts.isNotEmpty() -> ReadingProgressPhase.Conflict
            journals.values.any { it.retryAfterUntil > clock() } -> ReadingProgressPhase.RateLimited
            (retryAt[document.key] ?: 0) > clock() -> ReadingProgressPhase.Offline
            pullNeeded -> ReadingProgressPhase.Loading
            journal.pending.isNotEmpty() -> ReadingProgressPhase.Pending
            else -> ReadingProgressPhase.Synced
        }
        mutableState.value = ServerReadingNotesUiState(document?.readerId, journal?.visible().orEmpty(),
            journal?.conflicts?.map { (id, remote) -> ServerReadingNoteConflictView(id, requireNotNull(journal.desired(id)), remote) }.orEmpty(),
            journal?.pending?.keys.orEmpty(), phase, journals.values.count { it.pending.isNotEmpty() }, staleActionRejected)
    }
    private fun stop() { ticket++; network?.cancel(); network = null; timer?.cancel(); timer = null }
    private fun mutation(version: Long, desired: ServerReadingNoteDesired) = ServerReadingNoteMutation(version, UUID.randomUUID().toString(), desired.deleted, desired.note)
    private suspend fun <T> networkResult(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
}

internal fun same(a: ServerReadingNoteDesired, b: ServerReadingNoteDesired) = a.deleted == b.deleted &&
    comparable(a.note) == comparable(b.note)
internal fun comparable(note: ServerReadingNoteContent?) = note?.copy(excerpt = "", createdAt = ServerReadingNotesJson.timestamp(note.createdAt))
internal fun merge(previous: Map<String, ServerReadingNote>, incoming: ServerReadingNote): Map<String, ServerReadingNote> {
    val old = previous[incoming.noteId]
    if (old != null && old.version > incoming.version) return previous
    if (old != null && old.version == incoming.version) { require(old == incoming); return previous }
    return previous + (incoming.noteId to incoming)
}

class ServerReadingNotesViewModel(store: ServerReadingNotesStore) : ViewModel() {
    val sync = ServerReadingNotesSync(viewModelScope, store)
    class Factory(private val store: ServerReadingNotesStore) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerReadingNotesViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return ServerReadingNotesViewModel(store) as T
        }
    }
}
