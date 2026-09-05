package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.paging.PageMetadata
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class StoredCatalogPage(val fetchedAtMillis: Long, val data: WebCatalogPageData)

interface WebCatalogPageStore {
    suspend fun read(key: String): StoredCatalogPage?
    suspend fun write(key: String, page: StoredCatalogPage)
}

/** App-owned cache only; atomic per-page files, bounded disk usage, corrupt entries are misses. */
class FileWebCatalogPageStore(
    private val directory: File,
    private val maxPages: Int = 64,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
) : WebCatalogPageStore {
    init { require(maxPages > 0 && maxTotalBytes > 0) }

    override suspend fun read(key: String): StoredCatalogPage? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.isFile || file.length() > MaxEntryBytes) return@withContext null
        try {
            val json = JSONObject(file.readText(Charsets.UTF_8))
            require(json.getInt("version") == 1 && json.getString("key") == key)
            val catalog = RemoteCatalogSnapshotJson.decode(json.getString("catalog"))
            val metadata = json.getJSONObject("paging")
            val paging = PageMetadata(
                currentPage = metadata.getInt("currentPage"),
                totalPages = metadata.nullableInt("totalPages"),
                totalItems = metadata.nullableInt("totalItems"),
                pageItemCount = metadata.getInt("pageItemCount"),
                hasPreviousPage = metadata.getBoolean("hasPreviousPage"),
                hasNextPage = metadata.getBoolean("hasNextPage"),
            )
            require(paging.currentPage > 0 && paging.pageItemCount == catalog.items.size)
            paging.totalItems?.let { total -> require(total >= paging.pageItemCount) }
            paging.totalPages?.let { total ->
                require(total >= 0)
                if (total == 0) {
                    require(paging.currentPage == 1 && catalog.items.isEmpty())
                    require(!paging.hasNextPage && !paging.hasPreviousPage)
                } else {
                    require(paging.currentPage <= total)
                }
            }
            StoredCatalogPage(json.getLong("fetchedAtMillis"), WebCatalogPageData(
                catalog, paging, json.getString("providerId"), fromMemoryCache = false,
            ))
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun write(key: String, page: StoredCatalogPage) = withContext(Dispatchers.IO) {
        val data = page.data
        val paging = data.paging
        val bytes = JSONObject()
            .put("version", 1).put("key", key).put("fetchedAtMillis", page.fetchedAtMillis)
            .put("providerId", data.providerId).put("catalog", RemoteCatalogSnapshotJson.encode(data.catalog))
            .put("paging", JSONObject()
                .put("currentPage", paging.currentPage).put("totalPages", paging.totalPages ?: JSONObject.NULL)
                .put("totalItems", paging.totalItems ?: JSONObject.NULL).put("pageItemCount", paging.pageItemCount)
                .put("hasPreviousPage", paging.hasPreviousPage).put("hasNextPage", paging.hasNextPage))
            .toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > MaxEntryBytes || bytes.size > maxTotalBytes) return@withContext
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create page cache directory")
        val temporary = File.createTempFile("page-", ".tmp", directory)
        try {
            temporary.writeBytes(bytes)
            if (!temporary.renameTo(fileFor(key))) throw IOException("Cannot atomically replace cached page")
        } finally {
            temporary.delete()
        }
        val files = directory.listFiles().orEmpty()
            .filter { it.isFile && it.name.matches(Regex("[a-f0-9]{64}\\.json")) }
            .sortedWith(compareByDescending<File> { it == fileFor(key) }.thenByDescending { it.lastModified() })
        var retainedBytes = 0L
        files.forEachIndexed { index, file ->
            retainedBytes += file.length()
            if (index >= maxPages || retainedBytes > maxTotalBytes) file.delete()
        }
    }

    private fun fileFor(key: String) = File(directory, "${StableContentHash.sha256(key)}.json")
    private fun JSONObject.nullableInt(name: String): Int? = if (isNull(name)) null else getInt(name)
    private companion object { const val MaxEntryBytes = 4 * 1024 * 1024 }
}
