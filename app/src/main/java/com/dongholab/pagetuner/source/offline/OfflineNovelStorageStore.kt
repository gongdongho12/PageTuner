package com.dongholab.pagetuner.source.offline

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Airplane Flight Mode Offline Novel Storage Store.
 * Caches chapter text & translated paragraphs 100% offline for in-flight reading.
 */
class OfflineNovelStorageStore(context: Context? = null) {

    private val prefs: SharedPreferences? = context?.getSharedPreferences("offline_novel_storage", Context.MODE_PRIVATE)
    private val inMemoryDownloadedNovels = mutableMapOf<String, String>() // key: novelId_chapterNum, value: formattedText

    fun saveOfflineChapter(novelId: String, chapterNumber: Int, chapterTitle: String, contentText: String) {
        val key = "${novelId}_$chapterNumber"
        inMemoryDownloadedNovels[key] = contentText
        prefs?.let { p ->
            runCatching {
                val json = JSONObject()
                json.put("novelId", novelId)
                json.put("chapterNumber", chapterNumber)
                json.put("chapterTitle", chapterTitle)
                json.put("contentText", contentText)
                json.put("savedAt", System.currentTimeMillis())
                p.edit().putString(key, json.toString()).apply()
            }
        }
    }

    fun getOfflineChapter(novelId: String, chapterNumber: Int): String? {
        val key = "${novelId}_$chapterNumber"
        inMemoryDownloadedNovels[key]?.let { return it }
        val prefs = prefs ?: return null
        return runCatching {
            val jsonStr = prefs.getString(key, "") ?: return null
            if (jsonStr.isBlank()) return null
            val json = JSONObject(jsonStr)
            val text = json.optString("contentText", "")
            if (text.isNotBlank()) {
                inMemoryDownloadedNovels[key] = text
            }
            text.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    fun isChapterDownloaded(novelId: String, chapterNumber: Int): Boolean {
        val key = "${novelId}_$chapterNumber"
        if (inMemoryDownloadedNovels.containsKey(key)) return true
        return prefs?.contains(key) == true
    }

    companion object {
        val globalOfflineStore = OfflineNovelStorageStore(null)
    }
}
