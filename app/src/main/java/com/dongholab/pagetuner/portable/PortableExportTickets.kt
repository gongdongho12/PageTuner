package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import java.io.OutputStream
import java.util.UUID

data class PortableExportRequest(val id: String, val filename: String, val mimeType: String = "application/zip")

internal class PortableExportExpired : IllegalStateException("This export expired. Select the document and export again.")

/** Device-memory tickets bind a SAF result to one sealed payload, never to the newest prepared bytes. */
internal class PortableExportTickets {
    private enum class Phase { NEW, PREPARING, READY, WRITING }
    private class Payload(val bytes: ByteArray, val guard: () -> Unit)
    private class Pending(val request: PortableExportRequest, val connection: ServerReadingConnection?) {
        var phase = Phase.NEW
        var payload: Payload? = null
    }
    private var pending: Pending? = null

    @Synchronized fun begin(filename: String, connection: ServerReadingConnection? = null,
        mimeType: String = "application/zip"): PortableExportRequest =
        PortableExportRequest(UUID.randomUUID().toString(), filename, mimeType).also { pending = Pending(it, connection) }

    @Synchronized fun connect(value: ServerReadingConnection?) {
        if (pending?.connection?.let { !sameConnection(it, value) } == true) pending = null
    }

    @Synchronized fun cancel(id: String) { if (pending?.request?.id == id) pending = null }

    @Synchronized fun check(request: PortableExportRequest, current: ServerReadingConnection?) {
        val value = pending?.takeIf { it.request == request } ?: throw PortableExportExpired()
        checkConnection(value, current)
    }

    /** Memory-only callback admission: a stale URI must not reset a later screen's busy/status. */
    @Synchronized fun checkReadyId(id: String, current: ServerReadingConnection?): PortableExportRequest {
        val value = pending?.takeIf { it.request.id == id && it.phase == Phase.READY && it.payload != null }
            ?: throw PortableExportExpired()
        checkConnection(value, current)
        return value.request
    }

    /** Reserve once; source IO/lifetime guards and byte copying never hold the ticket monitor. */
    fun prepare(request: PortableExportRequest, bytes: ByteArray, current: ServerReadingConnection?, guard: () -> Unit = {}) {
        val value = synchronized(this) {
            check(request, current)
            requireNotNull(pending).also {
                if (it.phase != Phase.NEW) throw PortableExportExpired()
                it.phase = Phase.PREPARING
            }
        }
        var published = false
        try {
            // Copy before invoking external code, which may mutate its input or replace this request.
            val payload = Payload(bytes.copyOf(), guard)
            guard()
            synchronized(this) {
                if (pending !== value || value.phase != Phase.PREPARING) throw PortableExportExpired()
                checkConnection(value, current)
                value.payload = payload
                value.phase = Phase.READY
                published = true
            }
        } finally {
            if (!published) retire(value)
        }
    }

    /** Source guards run outside the ticket monitor, including a guard's nested ticket checks. */
    fun checkBeforeWrite(id: String, current: () -> ServerReadingConnection?) {
        val (value, payload) = synchronized(this) {
            val value = pending?.takeIf { it.request.id == id && (it.phase == Phase.READY || it.phase == Phase.WRITING) }
                ?: throw PortableExportExpired()
            value to requireNotNull(value.payload)
        }
        validate(value, payload, current)
    }

    /** Claim once, retain the exact payload, and validate before/after a blocking provider open. */
    fun write(id: String, current: () -> ServerReadingConnection?, open: () -> OutputStream?): PortableExportRequest {
        val (value, payload) = synchronized(this) {
            val value = pending?.takeIf { it.request.id == id && it.phase == Phase.READY } ?: throw PortableExportExpired()
            value.phase = Phase.WRITING
            value to requireNotNull(value.payload)
        }
        try {
            validate(value, payload, current)
            (open() ?: error("Unable to save the library package.")).use { output ->
                validate(value, payload, current)
                output.write(payload.bytes)
            }
            return value.request
        } finally { retire(value) }
    }

    private fun validate(value: Pending, payload: Payload, current: () -> ServerReadingConnection?) {
        checkPayload(value, payload, current())
        payload.guard()
        checkPayload(value, payload, current())
    }

    @Synchronized private fun checkPayload(value: Pending, payload: Payload, current: ServerReadingConnection?) {
        if (pending !== value || value.payload !== payload ||
            (value.phase != Phase.READY && value.phase != Phase.WRITING)) throw PortableExportExpired()
        checkConnection(value, current)
    }

    /** Only called with this monitor held; a stale operation cannot retire a later request. */
    private fun checkConnection(value: Pending, current: ServerReadingConnection?) {
        if (value.connection != null && !sameConnection(value.connection, current)) {
            if (pending === value) pending = null
            throw PortableExportExpired()
        }
    }

    @Synchronized private fun retire(value: Pending) { if (pending === value) pending = null }

    companion object {
        internal fun sameConnection(expected: ServerReadingConnection, current: ServerReadingConnection?) =
            current?.accountKey == expected.accountKey && current.client === expected.client
    }
}
