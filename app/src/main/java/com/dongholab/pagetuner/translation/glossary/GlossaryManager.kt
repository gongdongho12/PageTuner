package com.dongholab.pagetuner.translation.glossary

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class GlossaryEntry(
    val id: String,
    val sourceTerm: String,
    val targetTerm: String,
    val bookId: String? = null,
)

class GlossaryManager(context: Context) {
    private val glossaryFile = File(context.filesDir, "translation/glossary.json")

    suspend fun getEntries(bookId: String? = null): List<GlossaryEntry> = withContext(Dispatchers.IO) {
        readAll().filter { it.bookId == null || it.bookId == bookId }
    }

    suspend fun saveEntry(entry: GlossaryEntry): List<GlossaryEntry> = withContext(Dispatchers.IO) {
        val all = readAll().filterNot { it.id == entry.id } + entry
        writeAll(all)
        all
    }

    suspend fun deleteEntry(id: String): List<GlossaryEntry> = withContext(Dispatchers.IO) {
        val all = readAll().filterNot { it.id == id }
        writeAll(all)
        all
    }

    fun applyGlossary(text: String, entries: List<GlossaryEntry>): String {
        var result = text
        entries.forEach { entry ->
            if (entry.sourceTerm.isNotBlank() && entry.targetTerm.isNotBlank()) {
                result = result.replace(entry.sourceTerm, entry.targetTerm, ignoreCase = false)
            }
        }
        return result
    }

    private fun readAll(): List<GlossaryEntry> {
        if (!glossaryFile.exists()) return emptyList()
        return runCatching {
            val root = JSONObject(glossaryFile.readText(Charsets.UTF_8))
            val items = root.optJSONArray("entries") ?: JSONArray()
            buildList {
                for (i in 0 until items.length()) {
                    val obj = items.getJSONObject(i)
                    add(
                        GlossaryEntry(
                            id = obj.getString("id"),
                            sourceTerm = obj.getString("sourceTerm"),
                            targetTerm = obj.getString("targetTerm"),
                            bookId = obj.optString("bookId").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun writeAll(entries: List<GlossaryEntry>) {
        glossaryFile.parentFile?.mkdirs()
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("sourceTerm", entry.sourceTerm)
                    put("targetTerm", entry.targetTerm)
                    put("bookId", entry.bookId ?: "")
                },
            )
        }
        val root = JSONObject().put("version", 1).put("entries", array)
        glossaryFile.writeText(root.toString(2), Charsets.UTF_8)
    }
}
