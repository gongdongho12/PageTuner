package com.dongholab.pagetuner.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Warms exactly one following page; never changes UI state or recursively crawls a catalog. */
class CatalogPagePrefetcher(
    private val scope: CoroutineScope,
    private val service: WebCatalogPageService,
    private val delayMillis: Long = 500,
) {
    private var job: Job? = null

    fun schedule(request: WebCatalogPageRequest, current: WebCatalogPageData) {
        cancel()
        if (current.isStale || !current.paging.hasNextPage || current.paging.currentPage == Int.MAX_VALUE) return
        job = scope.launch {
            delay(delayMillis)
            try {
                service.load(request.copy(pageNumber = current.paging.currentPage + 1, forceRefresh = false))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Opportunistic cache warming must not surface as a reading error.
            }
        }
    }

    fun cancel() { job?.cancel(); job = null }
}
