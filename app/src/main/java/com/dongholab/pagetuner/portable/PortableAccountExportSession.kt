package com.dongholab.pagetuner.portable

import java.util.concurrent.atomic.AtomicReference

/** One fixed account/selection capability; never looks up a later UI or account owner. */
internal class PortableAccountExportGrant internal constructor(
    val authority: PortableTextAccountAuthority,
    val ticket: PortableTextOperationTicket,
    private val current: () -> Unit,
) {
    /** Memory-only: safe in the final canonical/binding/journal scope. */
    fun checkCurrent() = current()
}

/** A committed panel/options lifetime. Constructing a render never issues an account operation. */
internal class PortableAccountExportSession(
    private val authority: PortableTextAccountAuthority?,
    private val selectionKey: String,
) {
    private enum class State { NEW, ACTIVE, CLOSED }
    private val state = AtomicReference(State.NEW)
    private val activation = Any()
    @Volatile private var operation: PortableTextOperationLease? = null
    @Volatile private var committed: PortableTextOperationTicket? = null

    init { require(selectionKey.isNotEmpty()) }

    /** Called only from committed DisposableEffect; stale owners fail closed without crashing render. */
    fun activate() = synchronized(activation) {
        if (state.get() != State.NEW) return@synchronized
        val captured = authority
        if (captured == null) {
            state.set(State.CLOSED)
            return@synchronized
        }
        var created: PortableTextOperationLease? = null
        try {
            created = captured.operation(selectionKey)
            operation = created
            created.activate()
            committed = created.issue(captured.connection, selectionKey)
            if (!state.compareAndSet(State.NEW, State.ACTIVE)) created.close()
        } catch (_: PortableTextOperationExpired) {
            state.set(State.CLOSED)
            created?.close()
        }
    }

    /** Publish revocation before waiting on an in-flight gate; never hold activation while closing. */
    fun close() {
        state.set(State.CLOSED)
        operation?.close()
    }

    /** No monitors or latest-owner callbacks: only this committed generation's memory state. */
    fun check() {
        if (state.get() != State.ACTIVE) throw PortableExportExpired()
        requireNotNull(authority).check(committed ?: throw PortableExportExpired())
        if (state.get() != State.ACTIVE) throw PortableExportExpired()
    }

    /** An old begin callback can issue only from its captured, still-committed generation. */
    fun issue(): PortableAccountExportGrant {
        check()
        val captured = requireNotNull(authority)
        val ticket = (operation ?: throw PortableExportExpired()).issue(captured.connection, selectionKey)
        val grant = PortableAccountExportGrant(captured, ticket) {
            if (state.get() != State.ACTIVE) throw PortableExportExpired()
            captured.check(ticket)
            if (state.get() != State.ACTIVE) throw PortableExportExpired()
        }
        grant.checkCurrent()
        return grant
    }
}
