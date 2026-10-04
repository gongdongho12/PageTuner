package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CatalogTranslationProgress(
    val completedItems: Int,
    val totalItems: Int,
    val currentTitle: String,
    val failedItems: Int = 0,
) {
    val fraction: Float
        get() = if (totalItems == 0) 1f else completedItems.toFloat() / totalItems
}

sealed interface CatalogTranslationUpdate {
    data class Running(val progress: CatalogTranslationProgress) : CatalogTranslationUpdate
    data class Completed(val translations: Map<String, CatalogItemTranslation>) : CatalogTranslationUpdate
    data class Failed(val error: Throwable) : CatalogTranslationUpdate
    data object Cancelled : CatalogTranslationUpdate
}

/**
 * Android-free operation lifecycle. Call start/cancel from the scope's owning dispatcher
 * (Main for a ViewModel); service construction and translation run on [workerDispatcher].
 * Updates return to the owning dispatcher and cancelled/replaced requests cannot publish.
 */
class CatalogTranslationCoordinator(
    private val scope: CoroutineScope,
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private var generation = 0L
    private var job: Job? = null
    private var activeUpdate: ((CatalogTranslationUpdate) -> Unit)? = null

    fun start(
        items: List<RemoteBookItem>,
        settings: TranslationSettings,
        createService: () -> RemoteCatalogTranslationService,
        onUpdate: (CatalogTranslationUpdate) -> Unit,
    ): Boolean {
        if (items.isEmpty() || !settings.isProviderConfigured) return false
        cancel()
        val snapshot = items.toList()
        val request = ++generation
        activeUpdate = onUpdate
        val newJob = scope.launch(start = CoroutineStart.LAZY) {
            val ownerContext = currentCoroutineContext()
            fun publish(update: CatalogTranslationUpdate) {
                if (generation == request) onUpdate(update)
            }
            try {
                publish(CatalogTranslationUpdate.Running(CatalogTranslationProgress(0, snapshot.size, snapshot.first().title)))
                val translations = withContext(workerDispatcher) {
                    createService().translate(
                        items = snapshot,
                        settings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH),
                        onProgress = { progress ->
                            withContext(ownerContext) {
                                ensureActive()
                                val completed = (progress.fraction * snapshot.size).toInt().coerceIn(0, snapshot.size)
                                publish(CatalogTranslationUpdate.Running(CatalogTranslationProgress(
                                    completedItems = completed,
                                    totalItems = snapshot.size,
                                    currentTitle = snapshot[completed.coerceAtMost(snapshot.lastIndex)].title,
                                )))
                            }
                        },
                    )
                }
                ensureActive()
                publish(CatalogTranslationUpdate.Completed(translations))
            } catch (cancelled: CancellationException) {
                publish(CatalogTranslationUpdate.Cancelled)
                throw cancelled
            } catch (error: Exception) {
                publish(CatalogTranslationUpdate.Failed(error))
            } finally {
                if (generation == request) {
                    job = null
                    activeUpdate = null
                }
            }
        }
        job = newJob
        newJob.start()
        return true
    }

    fun cancel() {
        if (job == null) return
        ++generation
        job?.cancel()
        job = null
        val notify = activeUpdate
        activeUpdate = null
        notify?.invoke(CatalogTranslationUpdate.Cancelled)
    }
}
