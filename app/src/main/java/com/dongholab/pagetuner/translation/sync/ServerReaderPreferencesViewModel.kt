package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

interface ReaderPreferencesDevice {
    val sharedChanges: Flow<SharedReaderPreferences>
    suspend fun readShared(): SharedReaderPreferences
    suspend fun updateShared(patch: ReaderPreferencesPatch)
}

data class ReaderPreferencesChoice(val local: SharedReaderPreferences, val remote: ServerReaderPreferences)
data class ReaderPreferencesUiState(val accountKey: String? = null, val session: Long = 0,
    val phase: ReadingProgressPhase = ReadingProgressPhase.Inactive, val enabled: Boolean = false,
    val overlay: SharedReaderPreferences? = null, val choice: ReaderPreferencesChoice? = null,
    val staleChoiceRejected: Boolean = false)

/** Device defaults remain untouched while the active account supplies an isolated settings overlay. */
class ServerReaderPreferencesSync(private val scope: CoroutineScope, private val storage: ServerReaderPreferencesStore,
    private val device: ReaderPreferencesDevice, private val clock: () -> Long = System::currentTimeMillis) {
    private sealed interface Action {
        data class Connect(val connection: ServerReadingConnection?) : Action
        data class Device(val value: SharedReaderPreferences) : Action
        data class Edit(val session: Long, val patch: ReaderPreferencesPatch) : Action
        data class Choose(val session: Long, val local: Boolean, val shown: ReaderPreferencesChoice) : Action
        data class Retry(val session: Long? = null) : Action
        data class Received(val session: Long, val mutation: ReaderPreferencesMutation?, val result: Result<ServerReaderPreferences>) : Action
    }
    private val queue = Channel<Action>(Channel.UNLIMITED)
    private val mutableState = MutableStateFlow(ReaderPreferencesUiState())
    val state = mutableState.asStateFlow()
    private var connection: ServerReadingConnection? = null
    private var record = DeviceReaderPreferences()
    private var deviceValue: SharedReaderPreferences? = null
    private var session = 0L
    private var network: Job? = null
    private var timer: Job? = null
    private var failedStorage = false
    private var terminal = false
    private var fetchNeeded = false
    private var lastPhase = ReadingProgressPhase.Inactive
    private var stale = false
    private var retryAt = 0L

    init {
        scope.launch { device.sharedChanges.collect { queue.send(Action.Device(it)) } }
        scope.launch {
            for (action in queue) {
                try { handle(action) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    failedStorage = true; stop(); lastPhase = ReadingProgressPhase.DeviceError; publish()
                }
            }
        }
    }
    fun connect(connection: ServerReadingConnection?) { queue.trySend(Action.Connect(connection)) }
    fun edit(session: Long, patch: ReaderPreferencesPatch) { queue.trySend(Action.Edit(session, patch)) }
    fun choose(session: Long, local: Boolean, shown: ReaderPreferencesChoice) { queue.trySend(Action.Choose(session, local, shown)) }
    fun retry() { queue.trySend(Action.Retry()) }

    private suspend fun handle(action: Action) {
        when (action) {
            is Action.Connect -> {
                if (connection?.client === action.connection?.client && !failedStorage) return
                configure(action.connection); pump()
            }
            is Action.Device -> { ServerReaderPreferencesJson.validate(action.value); deviceValue = action.value; publish() }
            is Action.Edit -> {
                if (action.session != session || failedStorage) return
                stale = false
                if (!record.enabled || connection == null) {
                    device.updateShared(action.patch); deviceValue = device.readShared(); publish(); return
                }
                val local = action.patch.apply(requireNotNull(record.local))
                if (local == record.local) return
                if (record.pending == null && record.remote?.version == MaxReadingVersion) {
                    terminal = true; lastPhase = ReadingProgressPhase.Unavailable; publish(); return
                }
                val next = if (record.pending == null) record.copy(pending = mutation(record.remote?.version ?: 0, local))
                    else record.copy(queued = local.takeUnless { it == record.pending!!.preferences })
                persist(next); publish(); pump()
            }
            is Action.Choose -> {
                if (action.session != session || connection == null || failedStorage) return
                // Read persisted device defaults again before approving their upload.
                if (!record.enabled) deviceValue = device.readShared()
                if (choice() != action.shown) { stale = true; publish(); return }
                if (!action.local && action.shown.remote.preferences == null) return
                if (action.local && action.shown.remote.version == MaxReadingVersion) {
                    terminal = true; lastPhase = ReadingProgressPhase.Unavailable; publish(); return
                }
                stale = false
                val remote = action.shown.remote
                persist(DeviceReaderPreferences(enabled = true, remote = remote,
                    pending = if (action.local) mutation(remote.version, action.shown.local) else null,
                    retryAfterUntil = record.retryAfterUntil))
                terminal = false; retryAt = 0; lastPhase = ReadingProgressPhase.Synced; publish(); pump()
            }
            is Action.Retry -> {
                if (action.session != null && action.session != session) return
                if (action.session == null) {
                    if (failedStorage) { configure(connection); pump(); return }
                    terminal = false; retryAt = 0
                }
                fetchNeeded = true; pump()
            }
            is Action.Received -> {
                if (action.session != session || failedStorage) return
                network = null
                action.result.fold(onSuccess = { value ->
                    retryAt = 0
                    if (action.mutation == null) {
                        // A delayed/replica GET may not roll an acknowledged value backwards.
                        val remote = newest(record.remote, value)
                        val conflict = record.conflict?.let { newest(it, value) }
                        persist(record.copy(remote = remote, conflict = conflict))
                        fetchNeeded = false
                    } else if (record.pending == action.mutation) {
                        val latest = newest(record.remote, value)
                        if (latest.version > value.version) {
                            // An idempotent retry may ACK an older accepted mutation. Keep the local intent
                            // visible against the newer account snapshot until the user chooses explicitly.
                            persist(record.copy(remote = latest, conflict = latest))
                        } else {
                            val queued = record.queued?.takeUnless { it == value.preferences }
                            persist(record.copy(remote = latest, pending = queued?.let { mutation(value.version, it) },
                                queued = null, conflict = null))
                        }
                    }
                    lastPhase = ReadingProgressPhase.Synced
                }, onFailure = { error ->
                    when (error) {
                        is ReaderPreferencesConflict -> {
                            val current = newest(record.remote, error.current)
                            persist(record.copy(remote = current, conflict = current)); fetchNeeded = false
                            lastPhase = ReadingProgressPhase.Conflict
                        }
                        is ReaderPreferencesRateLimited -> {
                            persist(record.copy(retryAfterUntil = clock() + error.retryAfterSeconds * 1_000))
                            lastPhase = ReadingProgressPhase.RateLimited
                        }
                        else -> {
                            val transient = error is TranslationStoreException && error.failure in setOf(
                                TranslationStoreFailure.NETWORK, TranslationStoreFailure.TIMEOUT, TranslationStoreFailure.SERVER)
                            terminal = !transient; retryAt = if (transient) clock() + 15_000 else 0
                            lastPhase = if (transient) ReadingProgressPhase.Offline else ReadingProgressPhase.Unavailable
                        }
                    }
                })
                publish(); pump()
            }
        }
    }

    private suspend fun configure(value: ServerReadingConnection?) {
        stop(); connection = value; record = DeviceReaderPreferences(); failedStorage = false; terminal = false; stale = false; retryAt = 0
        deviceValue = device.readShared(); lastPhase = if (value == null) ReadingProgressPhase.Inactive else ReadingProgressPhase.Loading
        publish()
        if (value != null) {
            record = withContext(Dispatchers.IO) { storage.read(value.accountKey) }
            fetchNeeded = true
        } else fetchNeeded = false
        publish()
    }
    private suspend fun persist(value: DeviceReaderPreferences) {
        val account = requireNotNull(connection).accountKey
        withContext(Dispatchers.IO) { storage.write(account, value) }
        record = value
    }
    private fun choice(): ReaderPreferencesChoice? {
        val remote = if (record.enabled) record.conflict else record.remote
        val local = if (record.enabled) record.local else deviceValue
        return if (remote != null && local != null) ReaderPreferencesChoice(local, remote) else null
    }
    private fun publish() {
        val phase = when {
            failedStorage -> ReadingProgressPhase.DeviceError
            connection == null -> ReadingProgressPhase.Inactive
            record.conflict != null -> ReadingProgressPhase.Conflict
            terminal -> ReadingProgressPhase.Unavailable
            record.retryAfterUntil > clock() -> ReadingProgressPhase.RateLimited
            retryAt > clock() -> ReadingProgressPhase.Offline
            record.pending != null -> ReadingProgressPhase.Pending
            network != null -> ReadingProgressPhase.Loading
            else -> lastPhase
        }
        mutableState.value = ReaderPreferencesUiState(connection?.accountKey, session, phase, record.enabled,
            record.local.takeIf { record.enabled && connection != null }, choice(), stale)
    }
    private fun pump() {
        val connection = connection ?: return
        if (failedStorage || terminal || network != null) { publish(); return }
        timer?.cancel(); timer = null
        val wait = maxOf(record.retryAfterUntil, retryAt) - clock()
        if (wait > 0) { publish(); schedule(wait); return }
        val pending = record.pending.takeIf { record.conflict == null }
        if (pending == null && !fetchNeeded) { publish(); schedule(30_000); return }
        val ticket = session
        lastPhase = if (pending == null) ReadingProgressPhase.Loading else ReadingProgressPhase.Pending
        network = scope.launch {
            val result = try { Result.success(if (pending == null) connection.client.readerPreferences()
                else connection.client.saveReaderPreferences(pending)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { Result.failure(error) }
            queue.send(Action.Received(ticket, pending, result))
        }
        publish()
    }
    private fun schedule(delayMillis: Long) {
        val ticket = session
        timer = scope.launch { delay(delayMillis); queue.send(Action.Retry(ticket)) }
    }
    private fun stop() { session++; network?.cancel(); network = null; timer?.cancel(); timer = null }
    private fun newest(previous: ServerReaderPreferences?, received: ServerReaderPreferences) =
        previous?.takeIf { it.version > received.version } ?: received
    private fun mutation(version: Long, preferences: SharedReaderPreferences) =
        ReaderPreferencesMutation(version, UUID.randomUUID().toString(), preferences).also(ServerReaderPreferencesJson::encode)
}

class ServerReaderPreferencesViewModel(storage: ServerReaderPreferencesStore, device: ReaderPreferencesDevice) : ViewModel() {
    val sync = ServerReaderPreferencesSync(viewModelScope, storage, device)
    class Factory(private val storage: ServerReaderPreferencesStore, private val device: ReaderPreferencesDevice) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(ServerReaderPreferencesViewModel::class.java))
            @Suppress("UNCHECKED_CAST") return ServerReaderPreferencesViewModel(storage, device) as T
        }
    }
}
