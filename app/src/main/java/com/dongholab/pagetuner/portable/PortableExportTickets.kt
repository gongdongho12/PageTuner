package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import java.io.OutputStream
import java.util.UUID

data class PortableExportRequest(val id: String, val filename: String)

internal class PortableExportExpired : IllegalStateException("This export expired. Select the document and export again.")

/** Device-memory tickets bind a SAF result to exactly one prepared export, never to the newest bytes. */
internal class PortableExportTickets {
    private data class Pending(val request: PortableExportRequest, val connection: ServerReadingConnection?,
        var bytes: ByteArray? = null, var guard: () -> Unit = {}, var writing: Boolean = false)
    private var pending: Pending? = null

    @Synchronized fun begin(filename: String, connection: ServerReadingConnection? = null): PortableExportRequest =
        PortableExportRequest(UUID.randomUUID().toString(), filename).also { pending = Pending(it, connection) }

    @Synchronized fun connect(value: ServerReadingConnection?) {
        if (pending?.connection?.let { !sameConnection(it, value) } == true) pending = null
    }

    @Synchronized fun cancel(id: String) { if (pending?.request?.id == id) pending = null }

    @Synchronized fun check(request: PortableExportRequest, current: ServerReadingConnection?) {
        val value = pending?.takeIf { it.request == request } ?: throw PortableExportExpired()
        if (value.connection != null && !sameConnection(value.connection, current)) {
            pending = null
            throw PortableExportExpired()
        }
    }

    @Synchronized fun prepare(request: PortableExportRequest, bytes: ByteArray, current: ServerReadingConnection?, guard: () -> Unit = {}) {
        check(request, current)
        guard()
        check(request, current)
        requireNotNull(pending).also { it.bytes = bytes.copyOf(); it.guard = guard }
    }

    /** Check before opening/truncating a destination and again after a potentially blocking provider open. */
    fun write(id: String, current: () -> ServerReadingConnection?, open: () -> OutputStream?) {
        val value = synchronized(this) {
            val active = pending?.takeIf { it.request.id == id && !it.writing && it.bytes != null }
                ?: throw PortableExportExpired()
            active.also { it.writing = true }
        }
        fun validate() {
            check(value.request, current())
            value.guard()
            check(value.request, current())
        }
        try {
            validate()
            (open() ?: error("Unable to save the library package.")).use { output ->
                validate()
                output.write(requireNotNull(value.bytes))
            }
        } finally { cancel(id) }
    }

    companion object {
        internal fun sameConnection(expected: ServerReadingConnection, current: ServerReadingConnection?) =
            current?.accountKey == expected.accountKey && current.client === expected.client
    }
}
