package com.dongholab.pagetuner.translation.glossary

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

class BookGlossaryStore(private val rootDirectory: File) {
    constructor(context: Context) : this(context.filesDir.resolve("book-glossaries"))

    private val lock = ReentrantLock()

    fun load(bookId: String): BookGlossary = lock.withLock {
        val safeId = safeBookId(bookId)
        val file = rootDirectory.resolve("$safeId.json")
        if (!file.exists()) return@withLock BookGlossary(bookId)
        runCatching { decode(file.readText()) }.getOrElse { BookGlossary(bookId) }
    }

    fun save(glossary: BookGlossary) = lock.withLock {
        rootDirectory.mkdirs()
        val target = rootDirectory.resolve("${safeBookId(glossary.bookId)}.json")
        val temporary = rootDirectory.resolve("${target.name}.tmp")
        temporary.writeText(encode(glossary))
        check(temporary.renameTo(target) || run {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }) { "Unable to save book glossary." }
    }

    fun mergeCharacterAliases(
        bookId: String,
        suggestions: List<CharacterAliasSuggestion>,
    ): BookGlossary = lock.withLock {
        val current = load(bookId)
        val merged = BookGlossaryMerger.mergeCharacterAliases(current, suggestions)
        if (merged != current) save(merged)
        merged
    }

    fun delete(bookId: String) = lock.withLock {
        rootDirectory.resolve("${safeBookId(bookId)}.json").delete()
    }

    internal fun encode(glossary: BookGlossary): String = JSONObject()
        .put("version", 1)
        .put("bookId", glossary.bookId)
        .put("entries", JSONArray().apply {
            glossary.entries.forEach { entry ->
                put(JSONObject()
                    .put("id", entry.id)
                    .put("sourceTerm", entry.sourceTerm)
                    .put("translatedTerm", entry.translatedTerm)
                    .put("displayTerm", entry.displayTerm)
                    .put("kind", entry.kind.name)
                    .put("caseSensitive", entry.caseSensitive)
                    .put("enabled", entry.enabled))
            }
        })
        .toString(2)

    internal fun decode(raw: String): BookGlossary {
        val root = JSONObject(raw)
        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                val item = entriesJson.optJSONObject(index) ?: continue
                val id = item.optString("id")
                val source = item.optString("sourceTerm")
                val translated = item.optString("translatedTerm")
                if (id.isBlank() || source.isBlank() || translated.isBlank()) continue
                add(BookGlossaryEntry(
                    id = id,
                    sourceTerm = source,
                    translatedTerm = translated,
                    displayTerm = item.optString("displayTerm"),
                    kind = runCatching { GlossaryTermKind.valueOf(item.optString("kind")) }
                        .getOrDefault(GlossaryTermKind.Term),
                    caseSensitive = item.optBoolean("caseSensitive", false),
                    enabled = item.optBoolean("enabled", true),
                ))
            }
        }
        return BookGlossary(bookId = root.optString("bookId"), entries = entries)
    }

    private fun safeBookId(bookId: String): String {
        require(bookId.isNotBlank()) { "Book glossary ID cannot be blank." }
        return com.dongholab.pagetuner.document.DocumentIds.sha256(bookId).take(32)
    }
}
