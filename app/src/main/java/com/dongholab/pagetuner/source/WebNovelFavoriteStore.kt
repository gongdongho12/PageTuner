package com.dongholab.pagetuner.source

import android.util.AtomicFile
import com.dongholab.pagetuner.document.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Device-only list. Account synchronization lives in a separate atomic, account-scoped journal. */
class WebNovelFavoriteStore(private val favoritesFile: File) {
    fun listFavorites(): List<RemoteBookItem> {
        val file = AtomicFile(favoritesFile)
        if (!favoritesFile.exists() && !File(favoritesFile.path + ".bak").exists()) return emptyList()
        val json = file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        val values = JSONObject(json).getJSONArray("favorites")
        return List(values.length()) { index ->
            val obj = values.getJSONObject(index)
            val authors = obj.optJSONArray("authors")?.let { list -> List(list.length()) { list.getString(it) } }
                ?: listOf(obj.optString("author", "")).filter(String::isNotBlank)
            RemoteBookItem(
                identity = RemoteBookIdentity(RemoteSourceType.valueOf(obj.optString("sourceType", RemoteSourceType.WebNovel.name)),
                    obj.optString("accountId", "favorite"), obj.getString("remoteId")),
                title = obj.getString("title"), authors = authors, format = DocumentFormat.TEXT,
                language = obj.optString("language", "auto"), downloadUrl = obj.getString("downloadUrl"),
                sourceProviderId = obj.optional("sourceProviderId"), sourceBookId = obj.optional("sourceBookId"),
                seriesId = obj.optional("sourceBookId"),
            )
        }
    }
    fun isFavorite(url: String) = listFavorites().any { it.downloadUrl == url }
    fun toggleFavorite(item: RemoteBookItem): List<RemoteBookItem> {
        val current = listFavorites().toMutableList()
        val index = current.indexOfFirst { sameFavorite(it, item) }
        if (index >= 0) current.removeAt(index) else current.add(0, item)
        writeFavorites(current); return current
    }
    /** Only refresh an already saved item after the same detail URL has actually been parsed. */
    fun refreshMetadata(item: RemoteBookItem): List<RemoteBookItem> {
        val current = listFavorites()
        if (item.sourceProviderId == null || item.sourceBookId == null) return current
        val updated = current.map { if (sameFavorite(it, item)) item else it }
        if (updated != current) writeFavorites(updated)
        return updated
    }
    private fun writeFavorites(items: List<RemoteBookItem>) {
        favoritesFile.parentFile?.let { check(it.isDirectory || it.mkdirs()) }
        val values = JSONArray()
        items.forEach { item -> values.put(JSONObject().put("remoteId", item.identity.remoteId)
            .put("sourceType", item.identity.sourceType.name).put("accountId", item.identity.accountId)
            .put("title", item.title).put("authors", JSONArray(item.authors)).put("language", item.language)
            .put("downloadUrl", item.downloadUrl).put("sourceProviderId", item.sourceProviderId).put("sourceBookId", item.sourceBookId)) }
        val file = AtomicFile(favoritesFile); val stream = file.startWrite()
        try { stream.write(JSONObject().put("version", 2).put("favorites", values).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    private fun JSONObject.optional(key: String) = if (!has(key) || isNull(key)) null else getString(key)
}

fun sameFavorite(a: RemoteBookItem, b: RemoteBookItem): Boolean =
    if (a.sourceProviderId != null && a.sourceBookId != null && b.sourceProviderId != null && b.sourceBookId != null)
        a.sourceProviderId == b.sourceProviderId && a.sourceBookId == b.sourceBookId
    else a.downloadUrl == b.downloadUrl
