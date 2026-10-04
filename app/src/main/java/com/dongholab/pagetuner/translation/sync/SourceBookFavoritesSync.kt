package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SourceFavoriteRow(val desired: SourceFavoriteDesired, val version: Long)
data class SourceFavoriteChoice(val local: SourceFavoriteDesired, val remote: SourceBookFavorite)
data class SourceFavoritesUiState(val accountKey: String? = null, val session: Long = 0,
    val rows: List<SourceFavoriteRow> = emptyList(), val conflicts: List<SourceFavoriteChoice> = emptyList(),
    val pendingKeys: Set<String> = emptySet(), val phase: ReadingProgressPhase = ReadingProgressPhase.Inactive,
    val loaded: Boolean = false, val staleActionRejected: Boolean = false)

/** Account-wide serial actor. UI snapshots are guarded by both account session and displayed value. */
class SourceBookFavoritesSync(private val scope: CoroutineScope, private val storage: SourceBookFavoritesStore,
    private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Connect(val account: String?, val remote: SourceBookFavoritesRemote?) : Action
        data class Change(val session: Long, val desired: SourceFavoriteDesired, val shown: SourceFavoriteRow?) : Action
        data class Resolve(val session: Long, val local: Boolean, val shown: SourceFavoriteChoice) : Action
        data object Retry : Action
        data class Tick(val session: Long) : Action
        data class Pulled(val session: Long, val after: Long, val result: Result<SourceFavoritesPage>) : Action
        data class Pushed(val session: Long, val mutation: SourceFavoriteMutation, val result: Result<SourceBookFavorite>) : Action
    }
    private val actions = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(SourceFavoritesUiState())
    val state = mutableState.asStateFlow()
    private var accountKey: String? = null
    private var remote: SourceBookFavoritesRemote? = null
    private var journal = DeviceSourceFavorites()
    private var session = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var failedStorage = false
    private var terminal = false
    private var retryAt = 0L
    private var nextPollAt = 0L
    private var pullNeeded = false
    private var watermark: Long? = null
    private var loaded = false
    private var stale = false

    init { scope.launch {
        for (action in actions) {
            try { handle(action) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failedStorage = true; stop(); publish() }
        }
    } }
    fun connect(connection: ServerReadingConnection?) = connect(connection?.accountKey, connection?.client)
    fun connect(accountKey: String?, remote: SourceBookFavoritesRemote?) { actions.trySend(Action.Connect(accountKey, remote)) }
    fun retry() { actions.trySend(Action.Retry) }
    /** A null shown value is explicit adoption from the local list, never automatic migration. */
    fun change(session: Long, desired: SourceFavoriteDesired, shown: SourceFavoriteRow? = null) { actions.trySend(Action.Change(session, desired, shown)) }
    fun resolve(session: Long, local: Boolean, shown: SourceFavoriteChoice) { actions.trySend(Action.Resolve(session, local, shown)) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (accountKey == action.account && remote === action.remote && !failedStorage) return
                configure(action.account, action.remote); pump()
            }
            Action.Retry -> {
                if (failedStorage) configure(accountKey, remote)
                terminal = false; retryAt = 0; stale = false; pullNeeded = accountKey != null; pump()
            }
            is Action.Tick -> { if (action.session != session) return; if (clock() >= nextPollAt) pullNeeded = true; pump() }
            is Action.Change -> {
                if (action.session != session || accountKey == null || failedStorage || !loaded) return
                SourceFavoritesJson.validate(action.desired)
                val key = action.desired.identity.key; val current = journal.desired(key)
                if (action.shown != null && (action.shown.desired.identity != action.desired.identity ||
                        key in journal.pending && action.shown.desired != current)) { stale = true; publish(); return }
                if (current == action.desired) return
                stale = false
                val pending = journal.pending[key]
                if (pending != null) {
                    persist(journal.copy(queued = journal.queued + (key to action.desired)))
                } else {
                    val base = action.shown?.version ?: journal.items[key]?.version ?: 0
                    if (base == MaxReadingVersion) { terminal = true; publish(); return }
                    val mutation = mutation(action.desired, base)
                    // Adopting a different device entry cannot silently replace a known account favorite/tombstone.
                    val conflict = journal.items[key]?.takeIf { action.shown == null }
                    persist(journal.copy(pending = journal.pending + (key to mutation),
                        conflicts = if (conflict == null) journal.conflicts else journal.conflicts + (key to conflict)))
                }
                pump()
            }
            is Action.Resolve -> {
                if (action.session != session || accountKey == null || failedStorage) return
                val key = action.shown.local.identity.key
                if (journal.conflicts[key] != action.shown.remote || journal.desired(key) != action.shown.local) {
                    stale = true; publish(); return
                }
                if (action.local && action.shown.remote.version == MaxReadingVersion) { terminal = true; publish(); return }
                stale = false
                val pending = journal.pending - key
                persist(journal.copy(items = merge(journal.items, action.shown.remote),
                    pending = if (action.local) pending + (key to mutation(action.shown.local, action.shown.remote.version)) else pending,
                    queued = journal.queued - key, conflicts = journal.conflicts - key))
                terminal = false; retryAt = 0; pump()
            }
            is Action.Pulled -> {
                if (action.session != session || failedStorage) return
                network = null
                action.result.fold(onSuccess = { page ->
                    require(action.after == journal.afterRevision)
                    var items = journal.items; page.items.forEach { items = merge(items, it) }
                    val conflicts = journal.conflicts.mapValues { (key, conflict) -> items[key]?.takeIf { it.version > conflict.version } ?: conflict }
                    persist(journal.copy(items = items, conflicts = conflicts, afterRevision = page.nextAfterRevision, initialized = journal.initialized || !page.hasMore))
                    watermark = page.watermark.takeIf { page.hasMore }; pullNeeded = page.hasMore
                    if (!page.hasMore) { loaded = true; nextPollAt = clock() + 30_000 }
                    retryAt = 0
                }, onFailure = { failure(it) })
                pump()
            }
            is Action.Pushed -> {
                if (action.session != session || failedStorage) return
                network = null
                val key = action.mutation.desired.identity.key
                if (journal.pending[key] != action.mutation) { pump(); return }
                action.result.fold(onSuccess = { incoming ->
                    require(incoming.identity == action.mutation.desired.identity)
                    val items = merge(journal.items, incoming)
                    val latest = items.getValue(key)
                    val queued = journal.queued[key]?.takeUnless { it == SourceFavoriteDesired(incoming.identity, incoming.deleted, incoming.book) }
                    if (latest.version > incoming.version || queued != null && incoming.version == MaxReadingVersion) {
                        persist(journal.copy(items = items, conflicts = journal.conflicts + (key to latest)))
                    } else {
                        val pending = journal.pending - key
                        persist(journal.copy(items = items, pending = if (queued == null) pending else pending + (key to mutation(queued, incoming.version)),
                            queued = journal.queued - key, conflicts = journal.conflicts - key))
                    }
                    retryAt = 0
                }, onFailure = { error ->
                    if (error is SourceFavoriteConflict) {
                        require(error.current.identity == action.mutation.desired.identity)
                        val items = merge(journal.items, error.current)
                        persist(journal.copy(items = items, conflicts = journal.conflicts + (key to (items[key] ?: error.current))))
                    } else failure(error)
                })
                pump()
            }
        }
    }
    private suspend fun configure(account: String?, api: SourceBookFavoritesRemote?) {
        require((account == null) == (api == null)); account?.let { require(it.matches(Regex("[a-f0-9]{64}"))) }
        stop(); accountKey = account; remote = api; journal = DeviceSourceFavorites(); failedStorage = false; terminal = false
        retryAt = 0; nextPollAt = 0; pullNeeded = account != null; watermark = null; loaded = false; stale = false; publish()
        if (account != null) { journal = withContext(Dispatchers.IO) { storage.read(account) }; loaded = journal.initialized }
    }
    private suspend fun persist(value: DeviceSourceFavorites) {
        val account = requireNotNull(accountKey)
        withContext(Dispatchers.IO) { storage.write(account, value) }; journal = value; publish()
    }
    private suspend fun failure(error: Throwable) {
        when {
            error is SourceFavoritesRateLimited -> persist(journal.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1000))
            error is TranslationStoreException && error.failure in setOf(TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER) -> retryAt = clock() + 15_000
            else -> terminal = true
        }
    }
    private fun pump() {
        publish(); val api = remote ?: return
        if (network != null || failedStorage || terminal) return
        timer?.cancel(); timer = null
        val resumeAt = maxOf(journal.retryAfterUntil, retryAt)
        if (resumeAt > clock()) { schedule(resumeAt); return }
        val currentSession = session
        if (pullNeeded) {
            val after = journal.afterRevision; val until = watermark
            network = scope.launch { actions.send(Action.Pulled(currentSession, after, result { api.sourceBookFavorites(after, 50, until) })) }
            return
        }
        val mutation = journal.pending.entries.firstOrNull { it.key !in journal.conflicts }?.value
        if (mutation != null) {
            network = scope.launch { actions.send(Action.Pushed(currentSession, mutation, result { api.saveSourceBookFavorite(mutation) })) }
            return
        }
        schedule(maxOf(clock() + 1, nextPollAt))
    }
    private fun schedule(at: Long) { val ticket = session; timer = scope.launch { delay((at - clock()).coerceAtLeast(1)); actions.send(Action.Tick(ticket)) } }
    private fun stop() { session++; network?.cancel(); network = null; timer?.cancel(); timer = null }
    private fun publish() {
        val keys = (journal.items.keys + journal.pending.keys).toSet()
        val rows = keys.mapNotNull { key -> journal.desired(key)?.takeUnless { it.deleted }?.let { SourceFavoriteRow(it, journal.items[key]?.version ?: 0) } }
            .sortedWith(compareBy({ it.desired.book?.title }, { it.desired.identity.key }))
        mutableState.value = SourceFavoritesUiState(accountKey, session, rows,
            journal.conflicts.map { (key, remote) -> SourceFavoriteChoice(requireNotNull(journal.desired(key)), remote) }, journal.pending.keys,
            when {
                failedStorage -> ReadingProgressPhase.DeviceError
                accountKey == null -> ReadingProgressPhase.Inactive
                journal.conflicts.isNotEmpty() -> ReadingProgressPhase.Conflict
                terminal -> ReadingProgressPhase.Unavailable
                journal.retryAfterUntil > clock() -> ReadingProgressPhase.RateLimited
                retryAt > clock() -> ReadingProgressPhase.Offline
                journal.pending.isNotEmpty() -> ReadingProgressPhase.Pending
                pullNeeded -> ReadingProgressPhase.Loading
                else -> ReadingProgressPhase.Synced
            }, loaded, stale)
    }
    private fun mutation(desired: SourceFavoriteDesired, version: Long) = SourceFavoriteMutation(desired, version, UUID.randomUUID().toString())
    private fun merge(items: Map<String, SourceBookFavorite>, incoming: SourceBookFavorite): Map<String, SourceBookFavorite> {
        if (incoming.version == 0L) return items
        val old = items[incoming.identity.key]
        if (old != null && old.version >= incoming.version) { require(old.version != incoming.version || old == incoming); return items }
        return items + (incoming.identity.key to incoming)
    }
    private suspend fun <T> result(block: suspend () -> T): Result<T> = try { Result.success(block()) }
        catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { Result.failure(error) }
}

class SourceBookFavoritesViewModel(store: SourceBookFavoritesStore) : ViewModel() {
    val sync = SourceBookFavoritesSync(viewModelScope, store)
    class Factory(private val store: SourceBookFavoritesStore) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(SourceBookFavoritesViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return SourceBookFavoritesViewModel(store) as T
        }
    }
}
