package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import org.json.JSONArray
import org.json.JSONObject

/** Lossless cache format for catalogs extracted from HTML-backed sources. */
object RemoteCatalogSnapshotJson {
    const val StorageFormat = "pageturner.structured-catalog.v1"

    fun encode(catalog: PageTurnerCatalog): String {
        return JSONObject()
            .put("version", catalog.version)
            .put("id", catalog.id)
            .put("title", catalog.title)
            .put("updatedAt", catalog.updatedAt)
            .put("items", JSONArray().apply { catalog.items.forEach { put(it.toJson()) } })
            .toString()
    }

    fun decode(rawJson: String): PageTurnerCatalog {
        val root = JSONObject(rawJson)
        val items = root.optJSONArray("items") ?: JSONArray()
        return PageTurnerCatalog(
            version = root.optString("version", PageTurnerWebCatalogParser.Version),
            id = root.getString("id"),
            title = root.getString("title"),
            updatedAt = root.optString("updatedAt").takeIf { it.isNotBlank() },
            items = (0 until items.length()).mapNotNull { index ->
                items.optJSONObject(index)?.toRemoteBookItem()
            },
        )
    }

    private fun RemoteBookItem.toJson(): JSONObject {
        return JSONObject()
            .put("sourceType", identity.sourceType.name)
            .put("accountId", identity.accountId)
            .put("remoteId", identity.remoteId)
            .put("title", title)
            .put("authors", JSONArray(authors))
            .put("format", format.name)
            .put("language", language)
            .put("downloadUrl", downloadUrl)
            .put("contentType", contentType)
            .put("sizeBytes", sizeBytes)
            .put("checksum", checksum)
            .put("updatedAt", updatedAt)
            .put("coverUrl", coverUrl)
            .put("description", description)
            .put("chapterCount", chapterCount)
            .put("tags", JSONArray(tags))
            .put("sourceLanguage", translationHints.sourceLanguage)
            .put("targetLanguages", JSONArray(translationHints.targetLanguages))
            .put("seriesId", seriesId)
            .put("seriesTitle", seriesTitle)
            .put("chapterNumber", chapterNumber)
    }

    private fun JSONObject.toRemoteBookItem(): RemoteBookItem? {
        val remoteId = optString("remoteId")
        val title = optString("title")
        val downloadUrl = optString("downloadUrl")
        if (remoteId.isBlank() || title.isBlank() || downloadUrl.isBlank()) return null
        val sourceType = runCatching { RemoteSourceType.valueOf(optString("sourceType")) }.getOrNull()
            ?: return null
        val format = runCatching { DocumentFormat.valueOf(optString("format")) }
            .getOrDefault(DocumentFormat.TEXT)
        return RemoteBookItem(
            identity = RemoteBookIdentity(sourceType, optString("accountId"), remoteId),
            title = title,
            authors = optJSONArray("authors").toStringList(),
            format = format,
            language = nullableString("language"),
            downloadUrl = downloadUrl,
            contentType = nullableString("contentType"),
            sizeBytes = nullableLong("sizeBytes"),
            checksum = nullableString("checksum"),
            updatedAt = nullableString("updatedAt"),
            coverUrl = nullableString("coverUrl"),
            description = nullableString("description"),
            chapterCount = nullableInt("chapterCount"),
            tags = optJSONArray("tags").toStringList(),
            translationHints = RemoteTranslationHints(
                sourceLanguage = optString("sourceLanguage", "auto"),
                targetLanguages = optJSONArray("targetLanguages").toStringList(),
            ),
            seriesId = nullableString("seriesId"),
            seriesTitle = nullableString("seriesTitle"),
            chapterNumber = nullableInt("chapterNumber"),
        )
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }
    }

    private fun JSONObject.nullableString(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

    private fun JSONObject.nullableInt(name: String): Int? =
        if (isNull(name) || !has(name)) null else optInt(name)

    private fun JSONObject.nullableLong(name: String): Long? =
        if (isNull(name) || !has(name)) null else optLong(name)
}
