package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import java.io.OutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Blocking provider IO: caller runs on its storage dispatcher, with this WRITE job's live checks. */
internal suspend fun writePortableExport(tickets: PortableExportTickets, requestId: String,
    currentConnection: () -> ServerReadingConnection?, open: () -> OutputStream?): PortableExportRequest {
    val writerContext = currentCoroutineContext()
    val current = {
        writerContext.ensureActive()
        currentConnection().also { writerContext.ensureActive() }
    }
    // Tickets call current before/after the source guard and provider open, immediately before write.
    // There is no post-write cancellation check that could turn a completed save into a rollback.
    return tickets.write(requestId, current, open)
}
