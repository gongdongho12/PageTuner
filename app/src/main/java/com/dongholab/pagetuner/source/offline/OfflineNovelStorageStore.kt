package com.dongholab.pagetuner.source.offline

import android.content.Context
import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.source.RemoteBookItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import org.json.JSONObject

data class OfflineChapterTranslation(
    val language: String,
    val text: String,
    val providerId: String,
    val savedAtMillis: Long,
)

data class OfflineNovelChapter(
    val sourceType: String,
    val sourceAccountId: String,
    val seriesId: String,
    val novelId: String,
    val chapterId: String,
    val chapterNumber: Int,
    val chapterTitle: String,
    val sourceLanguage: String,
    val originalText: String,
    val translations: Map<String, OfflineChapterTranslation>,
    val savedAtMillis: Long,
) {
    fun preferredText(targetLanguage: String?): Pair<String, String> {
        val normalizedTarget = targetLanguage?.trim()?.lowercase().orEmpty()
        val translation = translations[normalizedTarget]
            ?: translations.values.firstOrNull { it.language.equals(normalizedTarget, ignoreCase = true) }
        return if (translation != null) {
            translation.text to translation.language
        } else {
            originalText to sourceLanguage
        }
    }
}

/**
 * Disk-backed airplane-mode storage. Each chapter is an atomic JSON package containing the
 * original text and any downloaded translations, so a partial translation never destroys the
 * usable source chapter.
 */
class OfflineNovelStorageStore private constructor(
    private val rootDirectory: File?,
) {
    constructor(context: Context? = null) : this(
        context?.applicationContext?.filesDir?.resolve("offline_novels"),
    )

    private val lock = Any()
    private val memory = mutableMapOf<String, OfflineNovelChapter>()

    fun saveOriginalChapter(
        item: RemoteBookItem,
        chapterNumber: Int,
        contentText: String,
    ): OfflineNovelChapter = saveOriginalChapterAt(
        location = structuredLocation(item, chapterNumber),
        sourceType = item.identity.sourceType.name,
        sourceAccountId = item.identity.accountId,
        seriesId = item.seriesId?.takeIf(String::isNotBlank) ?: item.identity.accountId,
        novelId = item.seriesId?.takeIf(String::isNotBlank) ?: item.identity.accountId,
        chapterId = item.identity.remoteId,
        chapterNumber = chapterNumber,
        chapterTitle = item.title,
        sourceLanguage = item.language ?: item.translationHints.sourceLanguage,
        contentText = contentText,
    )

    fun saveOriginalChapter(
        novelId: String,
        chapterId: String,
        chapterNumber: Int,
        chapterTitle: String,
        sourceLanguage: String,
        contentText: String,
    ): OfflineNovelChapter = saveOriginalChapterAt(
        location = legacyLocation(storageKey(novelId, chapterId)),
        sourceType = "legacy",
        sourceAccountId = "",
        seriesId = novelId,
        novelId = novelId,
        chapterId = chapterId,
        chapterNumber = chapterNumber,
        chapterTitle = chapterTitle,
        sourceLanguage = sourceLanguage,
        contentText = contentText,
    )

    private fun saveOriginalChapterAt(
        location: StorageLocation,
        sourceType: String,
        sourceAccountId: String,
        seriesId: String,
        novelId: String,
        chapterId: String,
        chapterNumber: Int,
        chapterTitle: String,
        sourceLanguage: String,
        contentText: String,
    ): OfflineNovelChapter = synchronized(lock) {
        require(contentText.isNotBlank()) { "Offline chapter text cannot be blank." }
        val previous = loadLocked(location)
        val chapter = OfflineNovelChapter(
            sourceType = sourceType,
            sourceAccountId = sourceAccountId,
            seriesId = seriesId,
            novelId = novelId,
            chapterId = chapterId,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            sourceLanguage = sourceLanguage.ifBlank { "auto" },
            originalText = contentText,
            translations = previous?.translations.orEmpty(),
            savedAtMillis = System.currentTimeMillis(),
        )
        persistLocked(location, chapter)
        chapter
    }

    fun saveTranslation(
        item: RemoteBookItem,
        chapterNumber: Int,
        targetLanguage: String,
        translatedText: String,
        providerId: String,
    ): OfflineNovelChapter = synchronized(lock) {
        val current = requireNotNull(loadItemLocked(item)) {
            "Save the original chapter before its translation."
        }
        val language = targetLanguage.trim().lowercase().ifBlank { "ko" }
        val updated = current.copy(
            chapterNumber = chapterNumber,
            translations = current.translations + (
                language to OfflineChapterTranslation(
                    language = language,
                    text = translatedText,
                    providerId = providerId,
                    savedAtMillis = System.currentTimeMillis(),
                )
            ),
            savedAtMillis = System.currentTimeMillis(),
        )
        persistLocked(structuredLocation(item, chapterNumber), updated)
        updated
    }

    fun getOfflineChapter(item: RemoteBookItem): OfflineNovelChapter? = synchronized(lock) {
        loadItemLocked(item)
    }

    fun isChapterDownloaded(item: RemoteBookItem): Boolean = getOfflineChapter(item) != null

    fun downloadedLanguages(item: RemoteBookItem): Set<String> {
        val chapter = getOfflineChapter(item) ?: return emptySet()
        return buildSet {
            add(chapter.sourceLanguage)
            addAll(chapter.translations.keys)
        }
    }

    // Compatibility for older call sites and stored chapter numbering.
    fun saveOfflineChapter(novelId: String, chapterNumber: Int, chapterTitle: String, contentText: String) {
        saveOriginalChapter(
            novelId = novelId,
            chapterId = chapterNumber.toString(),
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            sourceLanguage = "auto",
            contentText = contentText,
        )
    }

    fun getOfflineChapter(novelId: String, chapterNumber: Int): String? = synchronized(lock) {
        loadLocked(legacyLocation(storageKey(novelId, chapterNumber.toString())))?.originalText
    }

    fun isChapterDownloaded(novelId: String, chapterNumber: Int): Boolean {
        return getOfflineChapter(novelId, chapterNumber) != null
    }

    private fun storageKey(novelId: String, chapterId: String): String {
        return DocumentIds.sha256("$novelId\n$chapterId")
    }

    private fun legacyStorageKey(item: RemoteBookItem): String {
        val series = item.seriesId?.trim()?.takeIf(String::isNotBlank)
            ?: return storageKey(item.identity.accountId, item.identity.remoteId)
        return DocumentIds.sha256(
            "${item.identity.sourceType}\n${item.identity.accountId}\n$series\n${item.identity.remoteId}",
        )
    }

    private fun structuredLocation(item: RemoteBookItem, chapterNumber: Int): StorageLocation {
        return StorageLocation(
            memoryKey = legacyStorageKey(item),
            relativePath = OfflineNovelStoragePath.relativePath(item, chapterNumber),
            legacyRelativePath = "${legacyStorageKey(item)}.json",
        )
    }

    private fun legacyLocation(key: String): StorageLocation = StorageLocation(
        memoryKey = key,
        relativePath = "$key.json",
    )

    private fun loadItemLocked(item: RemoteBookItem): OfflineNovelChapter? {
        val memoryKey = legacyStorageKey(item)
        memory[memoryKey]?.let { return it }
        val knownNumber = item.chapterNumber
        if (knownNumber != null) {
            loadLocked(structuredLocation(item, knownNumber))?.let { return it }
        }
        // Remote sites occasionally renumber chapters. The stable remote chapter ID remains
        // authoritative, so fall back to its suffix before checking the legacy flat layout.
        val chapterDirectory = rootDirectory
            ?.resolve(OfflineNovelStoragePath.chapterDirectory(item))
        val suffix = OfflineNovelStoragePath.chapterFileSuffix(item)
        val candidate = chapterDirectory
            ?.listFiles { file -> file.isFile && file.name.endsWith(suffix) }
            ?.maxByOrNull(File::lastModified)
        loadFileLocked(memoryKey, candidate)?.let { return it }
        return loadLocked(legacyLocation(memoryKey))
    }

    private fun loadLocked(location: StorageLocation): OfflineNovelChapter? {
        memory[location.memoryKey]?.let { return it }
        val file = rootDirectory?.resolve(location.relativePath)
        loadFileLocked(location.memoryKey, file)?.let { return it }
        val legacyFile = if (rootDirectory != null && location.legacyRelativePath != null) {
            rootDirectory.resolve(location.legacyRelativePath)
        } else {
            null
        }
        return loadFileLocked(location.memoryKey, legacyFile)
    }

    private fun loadFileLocked(memoryKey: String, file: File?): OfflineNovelChapter? {
        if (file?.isFile != true) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            ?.also { memory[memoryKey] = it }
    }

    private fun persistLocked(location: StorageLocation, chapter: OfflineNovelChapter) {
        memory[location.memoryKey] = chapter
        val file = rootDirectory?.resolve(location.relativePath) ?: return
        file.parentFile?.mkdirs()
        file.writeAtomically(encode(chapter).toByteArray(Charsets.UTF_8))
    }

    private fun encode(chapter: OfflineNovelChapter): String {
        val translations = JSONObject()
        chapter.translations.forEach { (language, translation) ->
            translations.put(
                language,
                JSONObject().apply {
                    put("language", translation.language)
                    put("text", translation.text)
                    put("providerId", translation.providerId)
                    put("savedAtMillis", translation.savedAtMillis)
                },
            )
        }
        return JSONObject().apply {
            put("version", 3)
            put("sourceType", chapter.sourceType)
            put("sourceAccountId", chapter.sourceAccountId)
            put("seriesId", chapter.seriesId)
            put("novelId", chapter.novelId)
            put("chapterId", chapter.chapterId)
            put("chapterNumber", chapter.chapterNumber)
            put("chapterTitle", chapter.chapterTitle)
            put("sourceLanguage", chapter.sourceLanguage)
            put("originalText", chapter.originalText)
            put("translations", translations)
            put("savedAtMillis", chapter.savedAtMillis)
        }.toString()
    }

    private fun decode(raw: String): OfflineNovelChapter {
        val json = JSONObject(raw)
        val translationJson = json.optJSONObject("translations") ?: JSONObject()
        val translations = buildMap {
            translationJson.keys().forEach { language ->
                val item = translationJson.getJSONObject(language)
                put(
                    language,
                    OfflineChapterTranslation(
                        language = item.optString("language", language),
                        text = item.getString("text"),
                        providerId = item.optString("providerId", "unknown"),
                        savedAtMillis = item.optLong("savedAtMillis", 0L),
                    ),
                )
            }
        }
        return OfflineNovelChapter(
            sourceType = json.optString("sourceType", "legacy"),
            sourceAccountId = json.optString("sourceAccountId", ""),
            seriesId = json.optString("seriesId", json.getString("novelId")),
            novelId = json.getString("novelId"),
            chapterId = json.getString("chapterId"),
            chapterNumber = json.getInt("chapterNumber"),
            chapterTitle = json.getString("chapterTitle"),
            sourceLanguage = json.optString("sourceLanguage", "auto"),
            originalText = json.getString("originalText"),
            translations = translations,
            savedAtMillis = json.optLong("savedAtMillis", 0L),
        )
    }

    private fun File.writeAtomically(bytes: ByteArray) {
        val temporary = File(requireNotNull(parentFile), "$name.tmp")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            if (!temporary.renameTo(this)) throw IOException("Could not save offline chapter.")
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    companion object {
        @Volatile
        private var installedStore = OfflineNovelStorageStore(null as Context?)

        val globalOfflineStore: OfflineNovelStorageStore
            get() = installedStore

        fun install(context: Context) {
            installedStore = OfflineNovelStorageStore(context.applicationContext)
        }

        internal fun forDirectory(directory: File): OfflineNovelStorageStore {
            return OfflineNovelStorageStore(directory)
        }
    }

    private data class StorageLocation(
        val memoryKey: String,
        val relativePath: String,
        val legacyRelativePath: String? = null,
    )
}
