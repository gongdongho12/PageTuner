package com.dongholab.pagetuner.translation

import android.content.Context
import com.dongholab.pagetuner.core.translation.TranslationSegmentIdentity
import com.dongholab.pagetuner.storage.replaceFileAtomically
import java.io.File
import java.io.FileOutputStream
import java.lang.ref.WeakReference
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

    /** Atomically writes all records only if existing keys are absent or have identical text. */
    suspend fun putAllIfCompatible(records: List<CachedTranslation>): Boolean {
        throw UnsupportedOperationException("This translation cache does not support atomic conditional writes.")
    }

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

    private val state = sharedState(cacheFile)
    private val lock: Any = state

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
                val cache = loadLocked().toMutableMap()
                records.forEach { cache[it.key.id] = it }
                saveLocked(cache)
                state.memory = cache
            }
        }
    }

    override suspend fun putAllIfCompatible(records: List<CachedTranslation>): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val current = loadLocked()
            if (records.any { record -> current[record.key.id]?.let { it.key != record.key || it.text != record.text } == true }) {
                return@synchronized false
            }
            if (records.isEmpty()) return@synchronized true
            val updated = current.toMutableMap()
            records.forEach { updated[it.key.id] = it }
            saveLocked(updated)
            state.memory = updated
            true
        }
    }

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int {
        if (keys.isEmpty()) return 0

        return withContext(Dispatchers.IO) {
            synchronized(lock) {
                val cache = loadLocked().toMutableMap()
                val deleted = keys.count { key -> cache.remove(key.id) != null }
                if (deleted > 0) {
                    saveLocked(cache)
                    state.memory = cache
                }
                deleted
            }
        }
    }

    private fun loadLocked(): MutableMap<String, CachedTranslation> {
        state.memory?.let { return it }

        if (!cacheFile.exists()) {
            state.memory = mutableMapOf()
            return requireNotNull(state.memory)
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

        state.memory = loaded
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
            replaceFileAtomically(tmpFile, this)
        } finally {
            if (tmpFile.exists()) {
                tmpFile.delete()
            }
        }
    }

    companion object {
        private class CacheState(var memory: MutableMap<String, CachedTranslation>? = null)
        private val states = mutableMapOf<String, WeakReference<CacheState>>()

        /** All live instances for one canonical cache file share both its lock and committed snapshot. */
        private fun sharedState(file: File): CacheState = synchronized(states) {
            states.entries.removeAll { it.value.get() == null }
            val path = file.canonicalPath
            states[path]?.get() ?: CacheState().also { states[path] = WeakReference(it) }
        }

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
