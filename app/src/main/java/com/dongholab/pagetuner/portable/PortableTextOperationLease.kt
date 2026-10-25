package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection

internal class PortableTextOperationExpired : IllegalStateException("This text account or selection expired.")

private enum class TextLeaseState { NEW, ACTIVE, CLOSED }

/**
 * One committed text account lifetime. Closing revokes operations, not the shared HTTP client.
 * The owner must close this instance on logout/account/client replacement and create a new instance.
 * A connection value alone is not proof of account authority; this primitive does not issue ZIP rights.
 */
internal class PortableTextAccountLifetime(private val connection: ServerReadingConnection) {
    private val monitor = Any()
    @Volatile private var state = TextLeaseState.NEW

    init { require(connection.accountKey.matches(Regex("[a-f0-9]{64}"))) }

    fun activate() = synchronized(monitor) {
        if (state == TextLeaseState.NEW) state = TextLeaseState.ACTIVE
        Unit
    }

    fun close() = synchronized(monitor) { state = TextLeaseState.CLOSED }

    internal fun check(expected: ServerReadingConnection) {
        if (state != TextLeaseState.ACTIVE || expected.accountKey != connection.accountKey || expected.client !== connection.client)
            throw PortableTextOperationExpired()
    }

    internal fun <T> withCurrent(expected: ServerReadingConnection, block: (ServerReadingConnection) -> T): T = synchronized(monitor) {
        check(expected)
        // No post-block guard: a callback which committed cannot be converted into a rollback.
        block(connection)
    }
}

/**
 * One committed selection/options epoch, represented by this terminal instance rather than a reusable key.
 * Retained issuer callbacks must capture this object; they must never look up a newer owner's selection.
 */
internal class PortableTextOperationLease(
    private val account: PortableTextAccountLifetime,
    private val connection: ServerReadingConnection,
    private val selectionKey: String,
) {
    private val gate = Any()
    @Volatile private var state = TextLeaseState.NEW

    init { require(selectionKey.isNotEmpty()) }

    fun activate() = synchronized(gate) {
        if (state == TextLeaseState.NEW) account.withCurrent(connection) { state = TextLeaseState.ACTIVE }
        Unit
    }

    fun close() = synchronized(gate) { state = TextLeaseState.CLOSED }

    /** Issue only from the same committed account/client/selection captured by this callback. */
    fun issue(expected: ServerReadingConnection, expectedSelection: String): PortableTextOperationTicket = synchronized(gate) {
        check(expected, expectedSelection)
        account.withCurrent(expected) { PortableTextOperationTicket(this, expected, expectedSelection) }
    }

    internal fun check(expected: ServerReadingConnection, expectedSelection: String) {
        if (state != TextLeaseState.ACTIVE || expectedSelection != selectionKey ||
            expected.accountKey != connection.accountKey || expected.client !== connection.client) throw PortableTextOperationExpired()
        account.check(expected)
        if (state != TextLeaseState.ACTIVE) throw PortableTextOperationExpired()
    }

    internal fun <T> withCurrent(expected: ServerReadingConnection, expectedSelection: String,
        block: (ServerReadingConnection) -> T): T = synchronized(gate) {
        check(expected, expectedSelection)
        account.withCurrent(expected) { current ->
            check(expected, expectedSelection)
            // Keep gate -> account held while the caller acquires library -> binding -> journal.
            block(current)
        }
    }
}

/** Captured capability for a single terminal lease; it cannot be rebound to a newer account/selection. */
internal class PortableTextOperationTicket internal constructor(
    private val lease: PortableTextOperationLease,
    private val connection: ServerReadingConnection,
    private val selectionKey: String,
) {
    /** Memory-only final guard: safe while outer lifetime locks and a journal lock are already held. */
    fun check() = lease.check(connection, selectionKey)

    /** Serializes invalidation with the actual synchronous commit callback, never with network awaits. */
    fun <T> withCurrent(block: (ServerReadingConnection) -> T): T = lease.withCurrent(connection, selectionKey, block)
}
