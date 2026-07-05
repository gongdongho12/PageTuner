package com.dongholab.pagetuner.source

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class CachedWebCatalog(
    val url: String,
    val fetchedAtMillis: Long,
    val rawJson: String,
    val catalogId: String,
    val title: String,
    val updatedAt: String?,
    val itemCount: Int,
)

class RemoteCatalogCache(context: Context) {
    private val cacheDir = File(context.applicationContext.filesDir, "remote_sources")
    private val cacheFile = File(cacheDir, "web_catalog_cache.json")

    suspend fun list(): List<CachedWebCatalog> = withContext(Dispatchers.IO) {
        readCachedCatalogs().sortedByDescending { it.fetchedAtMillis }
    }

    suspend fun get(url: String): CachedWebCatalog? = withContext(Dispatchers.IO) {
        readCachedCatalogs().firstOrNull { it.url == url }
    }

    suspend fun save(
        url: String,
        rawJson: String,
        catalog: PageTurnerCatalog,
    ): CachedWebCatalog = withContext(Dispatchers.IO) {
        val cached = CachedWebCatalog(
            url = url,
            fetchedAtMillis = System.currentTimeMillis(),
            rawJson = rawJson,
            catalogId = catalog.id,
            title = catalog.title,
            updatedAt = catalog.updatedAt,
            itemCount = catalog.items.size,
        )
        val catalogs = readCachedCatalogs()
            .filterNot { it.url == url || it.catalogId == catalog.id } + cached
        writeCachedCatalogs(catalogs)
        cached
    }

    private fun readCachedCatalogs(): List<CachedWebCatalog> {
        if (!cacheFile.exists()) return emptyList()
        return runCatching {
            RemoteCatalogCacheJson.decode(cacheFile.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private fun writeCachedCatalogs(catalogs: List<CachedWebCatalog>) {
        cacheDir.mkdirs()
        val tmpFile = File(cacheDir, "${cacheFile.name}.tmp")
        tmpFile.writeText(RemoteCatalogCacheJson.encode(catalogs), Charsets.UTF_8)
        if (!tmpFile.renameTo(cacheFile)) {
            cacheFile.writeText(tmpFile.readText(Charsets.UTF_8), Charsets.UTF_8)
            tmpFile.delete()
        }
    }
}

object RemoteCatalogCacheJson {
    fun encode(catalogs: List<CachedWebCatalog>): String {
        val array = JSONArray()
        catalogs.forEach { catalog ->
            array.put(
                JSONObject()
                    .put("url", catalog.url)
                    .put("fetchedAtMillis", catalog.fetchedAtMillis)
                    .put("rawJson", catalog.rawJson)
                    .put("catalogId", catalog.catalogId)
                    .put("title", catalog.title)
                    .put("updatedAt", catalog.updatedAt)
                    .put("itemCount", catalog.itemCount),
            )
        }
        return JSONObject().put("catalogs", array).toString()
    }

    fun decode(rawJson: String): List<CachedWebCatalog> {
        val root = JSONObject(rawJson)
        val array = root.optJSONArray("catalogs") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.toCachedWebCatalog()
        }
    }

    private fun JSONObject.toCachedWebCatalog(): CachedWebCatalog {
        return CachedWebCatalog(
            url = optString("url"),
            fetchedAtMillis = optLong("fetchedAtMillis"),
            rawJson = optString("rawJson"),
            catalogId = optString("catalogId"),
            title = optString("title"),
            updatedAt = optString("updatedAt").takeIf { it.isNotBlank() },
            itemCount = optInt("itemCount"),
        )
    }
}
