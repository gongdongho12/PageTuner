package com.dongholab.pagetuner.source

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class WebNovelFavoriteStore(
    private val favoritesFile: File,
) {
    fun listFavorites(): List<RemoteBookItem> {
        if (!favoritesFile.exists()) return emptyList()
        return runCatching {
            val jsonStr = favoritesFile.readText(Charsets.UTF_8)
            val jsonArray = JSONObject(jsonStr).getJSONArray("favorites")
            val items = mutableListOf<RemoteBookItem>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                items.add(
                    RemoteBookItem(
                        identity = RemoteBookIdentity(
                            sourceType = RemoteSourceType.valueOf(obj.optString("sourceType", RemoteSourceType.WebNovel.name)),
                            accountId = obj.optString("accountId", "favorite"),
                            remoteId = obj.getString("remoteId"),
                        ),
                        title = obj.getString("title"),
                        authors = listOf(obj.optString("author", "WTR-Lab Author")),
                        format = com.dongholab.pagetuner.document.DocumentFormat.TEXT,
                        language = obj.optString("language", "en"),
                        downloadUrl = obj.getString("downloadUrl"),
                    )
                )
            }
            items
        }.getOrDefault(emptyList())
    }

    fun isFavorite(url: String): Boolean {
        return listFavorites().any { it.downloadUrl.equals(url, ignoreCase = true) }
    }

    fun toggleFavorite(item: RemoteBookItem): List<RemoteBookItem> {
        val current = listFavorites().toMutableList()
        val existingIndex = current.indexOfFirst { it.downloadUrl.equals(item.downloadUrl, ignoreCase = true) }

        if (existingIndex >= 0) {
            current.removeAt(existingIndex)
        } else {
            current.add(0, item)
        }
        writeFavorites(current)
        return current
    }

    private fun writeFavorites(items: List<RemoteBookItem>) {
        favoritesFile.parentFile?.mkdirs()
        val jsonArray = JSONArray()
        items.forEach { item ->
            jsonArray.put(
                JSONObject()
                    .put("remoteId", item.identity.remoteId)
                    .put("sourceType", item.identity.sourceType.name)
                    .put("accountId", item.identity.accountId)
                    .put("title", item.title)
                    .put("author", item.authors.firstOrNull() ?: "")
                    .put("language", item.language)
                    .put("downloadUrl", item.downloadUrl)
            )
        }
        val root = JSONObject().put("version", 1).put("favorites", jsonArray)
        favoritesFile.writeText(root.toString(), Charsets.UTF_8)
    }
}
