package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.paging.PageMetadata
import com.dongholab.pagetuner.core.paging.metadata
import com.dongholab.pagetuner.source.webnovel.WebNovelSiteAdapterRegistry
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class WebCatalogPageRequest(
    val url: String,
    val accountId: String,
    val pageNumber: Int,
    val forceRefresh: Boolean = false,
)

data class WebCatalogPageData(
    val catalog: PageTurnerCatalog,
    val paging: PageMetadata,
    val providerId: String,
    val fromMemoryCache: Boolean,
    val fromDiskCache: Boolean = false,
    val isStale: Boolean = false,
)

/** UI-free boundary for fetching, parsing, mapping, and retaining one remote catalog page. */
interface WebCatalogPageService {
    suspend fun load(
        request: WebCatalogPageRequest,
        onStep: (RemoteCatalogLoadStep) -> Unit = {},
    ): WebCatalogPageData
}

class DefaultWebCatalogPageService(
    private val adapterRegistry: WebNovelSiteAdapterRegistry = WebNovelSiteAdapterRegistry.default,
    private val sourceFactory: (accountId: String, url: String) -> PaginatedRemoteBookSource =
        { accountId, url -> WebNovelRemoteBookSource(accountId, url) },
    private val pageStore: WebCatalogPageStore? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 15 * 60 * 1000L,
    private val maxMemoryPages: Int = 16,
) : WebCatalogPageService {
    init { require(ttlMillis > 0 && maxMemoryPages > 0) }
    private val memoryPages = LinkedHashMap<String, StoredCatalogPage>(16, 0.75f, true)
    private class InFlightLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val inFlightLocks = mutableMapOf<String, InFlightLock>()

    override suspend fun load(
        request: WebCatalogPageRequest,
        onStep: (RemoteCatalogLoadStep) -> Unit,
    ): WebCatalogPageData = withContext(Dispatchers.Default) {
        require(request.pageNumber > 0 && request.accountId.isNotBlank()) { "A positive page and account are required." }
        val adapter = adapterRegistry.resolve(request.url)
        val pageUrl = adapter.catalogPageUrl(request.url, request.pageNumber)
        // Length-prefix components so account/query separators cannot alias another cache entry.
        val cacheKey = listOf(adapter.id, request.accountId, pageUrl).joinToString("") { "${it.length}:$it" }
        withPageLock(cacheKey) {
            val memory = synchronized(memoryPages) { memoryPages[cacheKey] }
            if (!request.forceRefresh && memory?.isFresh() == true) {
                return@withPageLock memory.data.copy(fromMemoryCache = true, fromDiskCache = false, isStale = false)
            }
            val disk = try { pageStore?.read(cacheKey) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { null }
            if (!request.forceRefresh && disk?.isFresh() == true) {
                remember(cacheKey, disk)
                return@withPageLock disk.data.copy(fromMemoryCache = false, fromDiskCache = true, isStale = false)
            }
            val loaded = try {
                val remote = sourceFactory(request.accountId, request.url).loadCatalogPage(request.pageNumber, onStep)
                ensureActive()
                WebCatalogPageData(
                    PageTurnerCatalog(PageTurnerWebCatalogParser.Version, request.accountId, remote.title, items = remote.items),
                    remote.metadata(), adapter.id, fromMemoryCache = false,
                )
            } catch (offline: IOException) {
                val fallback = listOfNotNull(memory, disk).maxByOrNull { it.fetchedAtMillis }
                if (!request.forceRefresh && fallback != null) {
                    return@withPageLock fallback.data.copy(
                        fromMemoryCache = fallback === memory, fromDiskCache = fallback !== memory, isStale = true,
                    )
                }
                throw offline
            }
            val stored = StoredCatalogPage(nowMillis(), loaded)
            remember(cacheKey, stored)
            try { pageStore?.write(cacheKey, stored) } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) { /* A full/unavailable cache must not hide a successfully loaded page. */ }
            loaded
        }
    }

    private fun StoredCatalogPage.isFresh(): Boolean = (nowMillis() - fetchedAtMillis) in 0 until ttlMillis

    /** Only identical requests wait on each other; unrelated pages never share a network lock. */
    private suspend fun <T> withPageLock(key: String, action: suspend () -> T): T {
        val entry = synchronized(inFlightLocks) {
            inFlightLocks.getOrPut(key) { InFlightLock() }.also { it.users++ }
        }
        return try {
            entry.mutex.withLock { action() }
        } finally {
            synchronized(inFlightLocks) {
                if (--entry.users == 0) inFlightLocks.remove(key)
            }
        }
    }

    private fun remember(key: String, page: StoredCatalogPage) = synchronized(memoryPages) {
        memoryPages[key] = page
        while (memoryPages.size > maxMemoryPages) {
            val iterator = memoryPages.entries.iterator()
            iterator.next()
            iterator.remove()
        }
    }
}
