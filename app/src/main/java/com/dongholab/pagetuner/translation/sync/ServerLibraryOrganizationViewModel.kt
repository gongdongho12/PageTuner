package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class LibraryOrganizationChoice(val local: LibraryOrganization, val remote: ServerLibraryOrganization)
data class LibraryOrganizationEditBase(val local: LibraryOrganization?, val remote: ServerLibraryOrganization?)
data class LibraryOrganizationUiState(val accountKey: String? = null, val session: Long = 0,
    val target: ServerReadingTarget? = null, val phase: ReadingProgressPhase = ReadingProgressPhase.Inactive,
    val base: LibraryOrganizationEditBase = LibraryOrganizationEditBase(null, null), val choice: LibraryOrganizationChoice? = null,
    val pendingDocuments: Int = 0, val staleActionRejected: Boolean = false)

/** A per-account actor serializes durable intents, including edits accepted immediately before logout. */
class ServerLibraryOrganizationSync(private val scope: CoroutineScope, private val storage: ServerLibraryOrganizationStore,
    private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Connect(val connection: ServerReadingConnection?) : Action
        data class Open(val target: ServerReadingTarget, val connection: ServerReadingConnection) : Action
        data object Close : Action
        data object Retry : Action
        data class Tick(val session: Long) : Action
        data class Edit(val session: Long, val value: LibraryOrganization, val base: LibraryOrganizationEditBase) : Action
        data class Choose(val session: Long, val local: Boolean, val shown: LibraryOrganizationChoice) : Action
        data class Received(val session: Long, val target: ServerReadingTarget, val mutation: LibraryOrganizationMutation?,
            val result: Result<ServerLibraryOrganization>) : Action
    }
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(LibraryOrganizationUiState())
    val state = mutableState.asStateFlow()
    private var connection: ServerReadingConnection? = null
    private var active: ServerReadingTarget? = null
    private val targets = linkedMapOf<String, ServerReadingTarget>()
    private val records = linkedMapOf<String, DeviceLibraryOrganization>()
    private val terminal = mutableSetOf<String>()
    private val retryAt = mutableMapOf<String, Long>()
    private var session = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var failedStorage = false
    private var fetchNeeded = false
    private var nextPollAt = 0L
    private var stale = false

    init { scope.launch {
        for (action in actions) {
            try { handle(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedStorage = true; stop(); publish() }
        }
    } }
    fun connect(value: ServerReadingConnection?) { actions.trySend(Action.Connect(value)) }
    fun open(target: ServerReadingTarget, connection: ServerReadingConnection) { actions.trySend(Action.Open(target, connection)) }
    fun close() { actions.trySend(Action.Close) }
    fun retry() { actions.trySend(Action.Retry) }
    fun edit(session: Long, value: LibraryOrganization, base: LibraryOrganizationEditBase) { actions.trySend(Action.Edit(session, value, base)) }
    fun choose(session: Long, local: Boolean, shown: LibraryOrganizationChoice) { actions.trySend(Action.Choose(session, local, shown)) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (connection?.client === action.connection?.client && !failedStorage) return
                configure(action.connection); pump()
            }
            is Action.Open -> {
                if (action.target.accountKey != action.connection.accountKey) return
                ServerReadingProgressJson.validateTarget(action.target.kind, action.target.recordId)
                if (connection?.client !== action.connection.client) configure(action.connection) else stop()
                active = action.target; targets[action.target.key] = action.target
                records[action.target.key] = withContext(Dispatchers.IO) { storage.read(action.target) }
                failedStorage = false; stale = false; terminal.remove(action.target.key); retryAt.remove(action.target.key)
                fetchNeeded = true; nextPollAt = 0; pump()
            }
            Action.Close -> { stop(); active = null; fetchNeeded = false; stale = false; pump() }
            Action.Retry -> {
                if (failedStorage) {
                    val previous = active; configure(connection)
                    if (previous != null) {
                        active = previous; targets[previous.key] = previous
                        records[previous.key] = withContext(Dispatchers.IO) { storage.read(previous) }
                    }
                }
                terminal.clear(); retryAt.clear(); stale = false; fetchNeeded = active != null; pump()
            }
            is Action.Tick -> { if (action.session != session) return; if (active != null && clock() >= nextPollAt) fetchNeeded = true; pump() }
            is Action.Edit -> {
                val target = active?.takeIf { action.session == session && !failedStorage } ?: return
                val previous = records.getValue(target.key)
                if (action.base.remote == null || previous.pending != null && previous.local != action.base.local) {
                    stale = true; publish(); return
                }
                ServerLibraryOrganizationJson.validate(action.value)
                if (action.value == previous.local) return
                if (previous.pending == null && action.base.remote.version == MaxReadingVersion) { terminal += target.key; publish(); return }
                stale = false
                persist(target, if (previous.pending == null) previous.copy(pending = mutation(action.base.remote.version, action.value))
                    else previous.copy(queued = action.value.takeUnless { it == previous.pending.organization }))
                pump()
            }
            is Action.Choose -> {
                val target = active?.takeIf { action.session == session && !failedStorage } ?: return
                val previous = records.getValue(target.key)
                if (choice(previous) != action.shown) { stale = true; publish(); return }
                if (action.local && action.shown.remote.version == MaxReadingVersion) { terminal += target.key; publish(); return }
                stale = false
                persist(target, DeviceLibraryOrganization(remote = action.shown.remote,
                    pending = if (action.local) mutation(action.shown.remote.version, action.shown.local) else null,
                    retryAfterUntil = previous.retryAfterUntil))
                terminal.remove(target.key); retryAt.remove(target.key); pump()
            }
            is Action.Received -> {
                if (action.session != session || failedStorage) return
                network = null
                val previous = records.getValue(action.target.key)
                if (action.mutation != null && previous.pending != action.mutation) { pump(); return }
                action.result.fold(onSuccess = { incoming ->
                    val remote = newest(previous.remote, incoming)
                    if (action.mutation == null) {
                        persist(action.target, previous.copy(remote = remote, conflict = previous.conflict?.let { newest(it, incoming) }))
                        fetchNeeded = false; nextPollAt = clock() + 30_000
                    } else if (remote.version > incoming.version) {
                        persist(action.target, previous.copy(remote = remote, conflict = remote))
                    } else {
                        val queued = previous.queued?.takeUnless { it == incoming.organization }
                        // No representable CAS remains at the safe-integer ceiling. Keep intent as a conflict.
                        if (queued != null && incoming.version == MaxReadingVersion) {
                            persist(action.target, previous.copy(remote = incoming, conflict = incoming))
                            terminal += action.target.key
                        } else persist(action.target, previous.copy(remote = incoming,
                            pending = queued?.let { mutation(incoming.version, it) }, queued = null, conflict = null))
                    }
                    retryAt.remove(action.target.key)
                }, onFailure = { error ->
                    when (error) {
                        is LibraryOrganizationConflict -> {
                            val current = newest(previous.remote, error.current)
                            persist(action.target, previous.copy(remote = current, conflict = current))
                        }
                        is LibraryOrganizationRateLimited -> persist(action.target, previous.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1000))
                        else -> if (error is TranslationStoreException && error.failure in setOf(
                            TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER))
                            retryAt[action.target.key] = clock() + 15_000 else terminal += action.target.key
                    }
                })
                pump()
            }
        }
    }
    private suspend fun configure(value: ServerReadingConnection?) {
        stop(); connection = value; active = null; targets.clear(); records.clear(); terminal.clear(); retryAt.clear()
        failedStorage = false; fetchNeeded = false; nextPollAt = 0; stale = false; publish()
        if (value != null) {
            val saved = withContext(Dispatchers.IO) { storage.targets(value.accountKey).map { it to storage.read(it) } }
            saved.forEach { (target, record) -> targets[target.key] = target; records[target.key] = record }
        }
    }
    private suspend fun persist(target: ServerReadingTarget, value: DeviceLibraryOrganization) {
        withContext(Dispatchers.IO) { storage.write(target, value) }; records[target.key] = value; publish()
    }
    private fun pump() {
        publish()
        val account = connection ?: return
        if (failedStorage || network != null) return
        timer?.cancel(); timer = null
        val cooldown = records.values.maxOfOrNull { it.retryAfterUntil } ?: 0
        if (cooldown > clock()) { schedule(cooldown); return }
        for (target in targets.values.sortedBy { if (it == active) 0 else 1 }) {
            if (target.key in terminal || (retryAt[target.key] ?: 0) > clock()) continue
            val previous = records.getValue(target.key)
            val pending = previous.pending.takeIf { previous.conflict == null }
            if (pending == null && !(target == active && fetchNeeded)) continue
            val ticket = session
            network = scope.launch {
                val result = try { Result.success(if (pending == null) account.client.libraryOrganization(target.kind, target.recordId)
                    else account.client.saveLibraryOrganization(target.kind, target.recordId, pending)) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
                actions.send(Action.Received(ticket, target, pending, result))
            }
            publish(); return
        }
        val deadlines = retryAt.filterKeys { it !in terminal }.values.filter { it > clock() }.toMutableList()
        active?.takeIf { it.key !in terminal }?.let { deadlines += maxOf(nextPollAt, retryAt[it.key] ?: 0, clock() + 1) }
        deadlines.minOrNull()?.let(::schedule)
    }
    private fun schedule(at: Long) {
        val ticket = session; timer = scope.launch { delay((at - clock()).coerceAtLeast(1)); actions.send(Action.Tick(ticket)) }
    }
    private fun choice(value: DeviceLibraryOrganization) = value.conflict?.let { remote -> value.local?.let { LibraryOrganizationChoice(it, remote) } }
    private fun publish() {
        val target = active; val record = target?.let { records[it.key] }
        val phase = when {
            failedStorage -> ReadingProgressPhase.DeviceError
            target == null || record == null -> ReadingProgressPhase.Inactive
            record.conflict != null -> ReadingProgressPhase.Conflict
            target.key in terminal -> ReadingProgressPhase.Unavailable
            records.values.any { it.retryAfterUntil > clock() } -> ReadingProgressPhase.RateLimited
            (retryAt[target.key] ?: 0) > clock() -> ReadingProgressPhase.Offline
            record.pending != null -> ReadingProgressPhase.Pending
            fetchNeeded -> ReadingProgressPhase.Loading
            else -> ReadingProgressPhase.Synced
        }
        mutableState.value = LibraryOrganizationUiState(connection?.accountKey, session, target, phase,
            LibraryOrganizationEditBase(record?.local, record?.remote), record?.let(::choice),
            records.values.count { it.pending != null }, stale)
    }
    private fun stop() { session++; network?.cancel(); network = null; timer?.cancel(); timer = null }
    private fun newest(old: ServerLibraryOrganization?, value: ServerLibraryOrganization): ServerLibraryOrganization = when {
        old == null || value.version > old.version -> value
        value.version < old.version -> old
        else -> { require(old == value); old }
    }
    private fun mutation(version: Long, value: LibraryOrganization) = LibraryOrganizationMutation(version, UUID.randomUUID().toString(), value)
}

class ServerLibraryOrganizationViewModel(store: ServerLibraryOrganizationStore) : ViewModel() {
    val sync = ServerLibraryOrganizationSync(viewModelScope, store)
    class Factory(private val store: ServerLibraryOrganizationStore) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerLibraryOrganizationViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return ServerLibraryOrganizationViewModel(store) as T
        }
    }
}
