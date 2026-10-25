package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import com.dongholab.pagetuner.translation.sync.ServerReadingTarget
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class PortableOrganizationAdoptionUiState(
    val active: Boolean = false,
    val comparison: PortableOrganizationComparison? = null,
    val busy: Boolean = false,
    val committed: Boolean = false,
    val error: String? = null,
)

/** One rendered selection. Old callbacks only reach this terminal owner, never a newer VM getter. */
internal class PortableOrganizationAdoptionSession(
    private val scope: CoroutineScope,
    private val authority: PortableTextAccountAuthority,
    selectionKey: String,
    private val create: (PortableTextOperationTicket) -> PortableOrganizationAdoptionCommand,
    private val captureDelivery: (ServerReadingConnection) -> ((ServerReadingTarget) -> Unit)?,
) {
    private val lease = runCatching { authority.operation(selectionKey) }.getOrNull()
    private val key = selectionKey
    private val mutableState = MutableStateFlow(PortableOrganizationAdoptionUiState())
    val state = mutableState.asStateFlow()
    @Volatile private var closed = false
    private var ticket: PortableTextOperationTicket? = null
    private var command: PortableOrganizationAdoptionCommand? = null
    private var operation: Job? = null

    /** Called only from the committed Compose effect, never during speculative rendering. */
    fun activate() {
        if (closed || ticket != null) return
        try {
            val active = requireNotNull(lease)
            active.activate()
            val issued = active.issue(authority.connection, key)
            ticket = issued
            command = create(issued)
            mutableState.value = state.value.copy(active = true)
        } catch (error: Exception) {
            close()
            mutableState.value = mutableState.value.copy(error = error.message)
        }
    }

    fun current(): Boolean = !closed && ticket?.let { runCatching { it.check() }.isSuccess } == true

    fun close() {
        if (closed) return
        closed = true
        // Closing the gate waits for a synchronous commit. No network await owns this lock.
        lease?.close()
        command?.close()
        operation?.cancel()
    }

    fun compare() {
        if (!current() || state.value.busy || state.value.committed) return
        val captured = command ?: return
        mutableState.value = state.value.copy(comparison = null, busy = true, error = null)
        operation = scope.launch {
            try {
                val comparison = captured.compare()
                if (current()) mutableState.value = state.value.copy(comparison = comparison, busy = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed) mutableState.value = state.value.copy(busy = false, error = error.message)
            }
        }
    }

    fun confirm(id: String) {
        if (!current() || state.value.busy || state.value.committed || state.value.comparison?.id != id) return
        val captured = command ?: return
        val issued = ticket ?: return
        // Capture the normal actor's account/client/session BEFORE any async confirmation/commit.
        val delivery = runCatching { captureDelivery(authority.connection) }.getOrNull()
        mutableState.value = state.value.copy(busy = true, error = null)
        operation = scope.launch {
            try {
                val result = captured.confirm(id)
                // Durable success survives disposal, cancellation and a rejected delivery ticket.
                mutableState.value = state.value.copy(busy = false, committed = true)
                runCatching {
                    issued.withCurrent { connection ->
                        check(connection.accountKey == result.connection.accountKey && connection.client === result.connection.client)
                        delivery?.invoke(result.target)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!closed) mutableState.value = state.value.copy(comparison = null, busy = false, error = error.message)
            }
        }
    }
}
