package com.dongholab.pagetuner.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface OfflineDownloadUpdate {
    data object Started : OfflineDownloadUpdate
    data class Progress(val value: BatchDownloadProgress) : OfflineDownloadUpdate
    data class Completed(val result: BatchDownloadResult) : OfflineDownloadUpdate
    data class Failed(val error: Throwable) : OfflineDownloadUpdate
    data object Cancelled : OfflineDownloadUpdate
}

/** Owns only the Android feature's operation lifecycle; the existing downloader owns persistence. */
class OfflineBookDownloadCoordinator(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var generation = 0L
    private var job: Job? = null
    private var activeUpdate: ((OfflineDownloadUpdate) -> Unit)? = null

    fun start(
        download: suspend ((BatchDownloadProgress) -> Unit) -> BatchDownloadResult,
        onUpdate: (OfflineDownloadUpdate) -> Unit,
    ) {
        cancel()
        val request = ++generation
        activeUpdate = onUpdate
        val next = scope.launch(start = CoroutineStart.LAZY) {
            fun publish(update: OfflineDownloadUpdate) {
                if (generation == request) onUpdate(update)
            }
            try {
                publish(OfflineDownloadUpdate.Started)
                val result = coroutineScope {
                    // Non-suspending downloader callbacks can originate on IO. Conflate to avoid
                    // a render backlog, and deliver on the scope's owning dispatcher.
                    val progress = Channel<BatchDownloadProgress>(Channel.CONFLATED)
                    val consumer = launch { for (value in progress) publish(OfflineDownloadUpdate.Progress(value)) }
                    try {
                        withContext(workerDispatcher) { download { progress.trySend(it) } }
                    } finally {
                        progress.close()
                        consumer.join()
                    }
                }
                ensureActive()
                publish(OfflineDownloadUpdate.Completed(result))
            } catch (cancelled: CancellationException) {
                publish(OfflineDownloadUpdate.Cancelled)
                throw cancelled
            } catch (error: Exception) {
                publish(OfflineDownloadUpdate.Failed(error))
            } finally {
                if (generation == request) { job = null; activeUpdate = null }
            }
        }
        job = next
        next.start()
    }

    fun cancel() {
        if (job == null) return
        ++generation
        job?.cancel()
        job = null
        val notify = activeUpdate
        activeUpdate = null
        notify?.invoke(OfflineDownloadUpdate.Cancelled)
    }
}
