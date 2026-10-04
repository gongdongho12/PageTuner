package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import java.io.Closeable
import kotlinx.coroutines.CancellationException

interface PdfContentClient : Closeable {
    suspend fun upload(request: PdfContentUpload): PdfContentReceipt
    suspend fun get(recordId: String): PdfContentRecord
}

/** A separate, credentials-bound lifetime. PDF IDs never authorize text reading/synchronization. */
class PdfStorageConnection(val accountKey: String, val origin: String, val client: PdfContentClient) : Closeable {
    private val lock = Any()
    private var closed = false
    fun <T> current(block: () -> T): T = synchronized(lock) {
        if (closed) throw CancellationException("PDF connection expired.")
        block()
    }
    override fun close() = synchronized(lock) { closed = true; client.close() }
}
