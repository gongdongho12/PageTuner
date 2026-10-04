package com.dongholab.pagetuner.translation

import android.content.Context
import com.dongholab.pagetuner.core.translation.TranslationSegmentIdentity
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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
    val identity: TranslationSegmentIdentity = TranslationSegmentIdentity(
        documentId = documentId,
        segmentId = segmentId,
        sourceLanguage = sourceLanguage,
        targetLanguage = targetLanguage,
        providerId = providerId,
    )

    val id: String = identity.id
}

data class CachedTranslation(
    val key: TranslationCacheKey,
    val text: String,
    val updatedAtMillis: Long,
)

interface TranslationCache {
    suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation>

    suspend fun putAll(records: List<CachedTranslation>)

    suspend fun deleteMany(keys: List<TranslationCacheKey>): Int
}

class JsonFileTranslationCache internal constructor(
    private val cacheFile: File,
) : TranslationCache {
    constructor(context: Context) : this(
        fallbackTranslationCacheFile(context.applicationContext),
    )

    constructor(context: Context, localBookRelativePath: String?) : this(
        translationCacheFileForContext(
            context = context.applicationContext,
            localBookRelativePath = localBookRelativePath,
        ),
    )

    private val lock = Any()
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

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int {
        if (keys.isEmpty()) return 0

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val cache = loadLocked()
                val deleted = keys.count { key -> cache.remove(key.id) != null }
                if (deleted > 0) saveLocked(cache)
                deleted
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
        cacheFile.writeAtomically(root.toString().toByteArray(Charsets.UTF_8))
    }

    private fun File.writeAtomically(bytes: ByteArray) {
        parentFile?.mkdirs()
        val tmpFile = File(requireNotNull(parentFile), "$name.tmp")
        try {
            FileOutputStream(tmpFile).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!tmpFile.renameTo(this)) {
                throw IOException("Could not replace translation cache file.")
            }
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        private fun translationCacheFileForContext(
            context: Context,
            localBookRelativePath: String?,
        ): File {
            if (localBookRelativePath.isNullOrBlank()) {
                return fallbackTranslationCacheFile(context)
            }
            val libraryDir = File(context.filesDir, "local_library")
            return runCatching {
                translationCacheFileForLocalBook(
                    libraryDir = libraryDir,
                    relativePath = localBookRelativePath,
                )
            }.getOrElse {
                fallbackTranslationCacheFile(context)
            }
        }
    }
}

internal fun translationCacheFileForLocalBook(
    libraryDir: File,
    relativePath: String,
): File {
    val root = libraryDir.canonicalFile
    val sourceFile = File(root, relativePath).canonicalFile
    require(sourceFile.path == root.path || sourceFile.path.startsWith(root.path + File.separator)) {
        "Invalid local book path."
    }
    val sourceParent = sourceFile.parentFile ?: root
    val cacheBaseName = sourceFile.nameWithoutExtension
        .ifBlank { sourceFile.name }
        .ifBlank { "book" }
        .sanitizeTranslationCacheFileName()
    return File(File(sourceParent, "translate"), "$cacheBaseName.translations.json")
}

private fun fallbackTranslationCacheFile(context: Context): File {
    return File(context.filesDir, "translation-cache/page-turner-cache.json")
}

private fun String.sanitizeTranslationCacheFileName(): String {
    return replace(Regex("[^A-Za-z0-9._ -]"), "_")
        .trim('.', '_', '-')
        .take(96)
        .ifBlank { "book" }
}
