package com.dongholab.pagetuner.translation

import android.content.Context
import com.dongholab.pagetuner.document.DocumentIds
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class TranslationCacheKey(
    val documentId: String,
    val segmentId: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val providerId: String,
) {
    val id: String = DocumentIds.sha256(
        listOf(documentId, segmentId, sourceLanguage, targetLanguage, providerId).joinToString("|"),
    )
}

data class CachedTranslation(
    val key: TranslationCacheKey,
    val text: String,
    val updatedAtMillis: Long,
)

interface TranslationCache {
    suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation>

    suspend fun putAll(records: List<CachedTranslation>)
}

class JsonFileTranslationCache(
    context: Context,
) : TranslationCache {
    private val lock = Any()
    private val cacheFile: File = File(context.filesDir, "translation-cache/page-turner-cache.json")
    private var memory: MutableMap<String, CachedTranslation>? = null

    override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> {
        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val cache = loadLocked()
                keys.mapNotNull { key -> cache[key.id]?.let { key.id to it } }.toMap()
            }
        }
    }

    override suspend fun putAll(records: List<CachedTranslation>) {
        if (records.isEmpty()) return

        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val cache = loadLocked()
                records.forEach { cache[it.key.id] = it }
                saveLocked(cache)
            }
        }
    }

    private fun loadLocked(): MutableMap<String, CachedTranslation> {
        memory?.let { return it }

        if (!cacheFile.exists()) {
            memory = mutableMapOf()
            return requireNotNull(memory)
        }

        val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
        val records = root.optJSONObject("records") ?: JSONObject()
        val loaded = mutableMapOf<String, CachedTranslation>()

        records.keys().forEach { id ->
            val item = records.getJSONObject(id)
            val key = TranslationCacheKey(
                documentId = item.getString("documentId"),
                segmentId = item.getString("segmentId"),
                sourceLanguage = item.getString("sourceLanguage"),
                targetLanguage = item.getString("targetLanguage"),
                providerId = item.getString("providerId"),
            )
            loaded[id] = CachedTranslation(
                key = key,
                text = item.getString("text"),
                updatedAtMillis = item.getLong("updatedAtMillis"),
            )
        }

        memory = loaded
        return loaded
    }

    private fun saveLocked(records: Map<String, CachedTranslation>) {
        cacheFile.parentFile?.mkdirs()
        val root = JSONObject()
        val items = JSONObject()

        records.forEach { (id, record) ->
            items.put(
                id,
                JSONObject().apply {
                    put("documentId", record.key.documentId)
                    put("segmentId", record.key.segmentId)
                    put("sourceLanguage", record.key.sourceLanguage)
                    put("targetLanguage", record.key.targetLanguage)
                    put("providerId", record.key.providerId)
                    put("text", record.text)
                    put("updatedAtMillis", record.updatedAtMillis)
                },
            )
        }

        root.put("version", 1)
        root.put("records", items)
        cacheFile.writeText(root.toString(), Charsets.UTF_8)
    }
}
