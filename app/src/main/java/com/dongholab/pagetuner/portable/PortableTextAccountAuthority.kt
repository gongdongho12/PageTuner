package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection

/** One immutable account publication, created only after the owner's authenticated verification. */
internal class PortableTextAccountAuthority internal constructor(val connection: ServerReadingConnection) {
    private val lifetime = PortableTextAccountLifetime(connection).also { it.activate() }

    /** A captured old authority cannot borrow a later account generation, even with equal client/key. */
    fun operation(selectionKey: String): PortableTextOperationLease {
        lifetime.check(connection)
        return PortableTextOperationLease(lifetime, connection, selectionKey)
    }

    internal fun close() = lifetime.close()
}
