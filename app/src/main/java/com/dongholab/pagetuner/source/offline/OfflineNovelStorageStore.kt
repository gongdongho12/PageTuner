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
    ): OfflineNovelChapter = saveOriginalChapter(
        novelId = item.identity.accountId,
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
    ): OfflineNovelChapter = synchronized(lock) {
        require(contentText.isNotBlank()) { "Offline chapter text cannot be blank." }
        val key = storageKey(novelId, chapterId)
        val previous = loadLocked(key)
        val chapter = OfflineNovelChapter(
            novelId = novelId,
            chapterId = chapterId,
            chapterNumber = chapterNumber,
            chapterTitle = chapterTitle,
            sourceLanguage = sourceLanguage.ifBlank { "auto" },
            originalText = contentText,
            translations = previous?.translations.orEmpty(),
            savedAtMillis = System.currentTimeMillis(),
        )
        persistLocked(key, chapter)
        chapter
    }

    fun saveTranslation(
        item: RemoteBookItem,
        chapterNumber: Int,
        targetLanguage: String,
        translatedText: String,
        providerId: String,
    ): OfflineNovelChapter = synchronized(lock) {
        val key = storageKey(item.identity.accountId, item.identity.remoteId)
        val current = requireNotNull(loadLocked(key)) {
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
        persistLocked(key, updated)
        updated
    }

    fun getOfflineChapter(item: RemoteBookItem): OfflineNovelChapter? = synchronized(lock) {
        loadLocked(storageKey(item.identity.accountId, item.identity.remoteId))
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
        loadLocked(storageKey(novelId, chapterNumber.toString()))?.originalText
    }

    fun isChapterDownloaded(novelId: String, chapterNumber: Int): Boolean {
        return getOfflineChapter(novelId, chapterNumber) != null
    }

    private fun storageKey(novelId: String, chapterId: String): String {
        return DocumentIds.sha256("$novelId\n$chapterId")
    }

    private fun loadLocked(key: String): OfflineNovelChapter? {
        memory[key]?.let { return it }
        val file = rootDirectory?.resolve("$key.json") ?: return null
        if (!file.isFile) return null
        return runCatching { decode(file.readText(Charsets.UTF_8)) }
            .getOrNull()
            ?.also { memory[key] = it }
    }

    private fun persistLocked(key: String, chapter: OfflineNovelChapter) {
        memory[key] = chapter
        val file = rootDirectory?.resolve("$key.json") ?: return
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
            put("version", 2)
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
}
