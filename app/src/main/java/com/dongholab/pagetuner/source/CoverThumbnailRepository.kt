package com.dongholab.pagetuner.source

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded, reusable thumbnail cache. A failed image never fails the catalog itself. */
class CoverThumbnailRepository(
    private val fetch: suspend (String, Int) -> ByteArray = { url, limit ->
        PageTurnerWebCatalogNetwork.fetchBytes(url, limit)
    },
    private val maxImageBytes: Int = 2 * 1024 * 1024,
    private val maxCacheBytes: Int = 8 * 1024 * 1024,
    private val maxEntries: Int = 32,
) {
    init { require(maxImageBytes > 0 && maxCacheBytes > 0 && maxEntries > 0) }
    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, ByteArray>(16, 0.75f, true)
    private var cachedBytes = 0L

    suspend fun load(urls: List<String>): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        // Work is bounded to one catalog page, not an unbounded whole-library image crawl.
        val requested = urls.filter(String::isNotBlank).distinct().take(maxEntries)
        mutex.withLock {
            requested.forEach { url ->
                ensureActive()
                if (cache[url] != null) return@forEach
                val bytes = try {
                    fetch(url, maxImageBytes)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    ensureActive()
                    return@forEach
                }
                // A blocking fetch may finish after cancellation without throwing it itself.
                ensureActive()
                if (bytes.isEmpty() || bytes.size > maxImageBytes || bytes.size > maxCacheBytes) return@forEach
                cache[url] = bytes
                cachedBytes += bytes.size
                while (cache.size > maxEntries || cachedBytes > maxCacheBytes) {
                    val oldest = cache.entries.iterator()
                    cachedBytes -= oldest.next().value.size
                    oldest.remove()
                }
            }
            requested.mapNotNull { url -> cache[url]?.let { url to it } }.toMap()
        }
    }
}
