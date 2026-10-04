package com.dongholab.pagetuner.translation.glossary

import android.content.Context
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.json.JSONArray
import org.json.JSONObject

class BookGlossaryStore(private val rootDirectory: File) {
    constructor(context: Context) : this(context.filesDir.resolve("book-glossaries"))

    private val lock = Locks.getOrPut(rootDirectory.absoluteFile.normalize().path) { ReentrantLock() }

    fun load(bookId: String): BookGlossary = lock.withLock {
        val safeId = safeBookId(bookId)
        val file = rootDirectory.resolve("$safeId.json")
        recover(file)
        if (!file.exists()) return@withLock BookGlossary(bookId)
        require(file.length() <= 8_388_608) { "Dictionary file is too large." }
        decode(file.readText()).also { require(it.bookId == bookId) { "Dictionary identity mismatch." } }
    }

    fun save(glossary: BookGlossary) = lock.withLock {
        rootDirectory.mkdirs()
        val target = rootDirectory.resolve("${safeBookId(glossary.bookId)}.json")
        recover(target)
        val temporary = rootDirectory.resolve("${target.name}.tmp")
        val backup = rootDirectory.resolve("${target.name}.bak")
        val bytes = encode(glossary).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 8_388_608)
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        if (target.exists()) check(target.renameTo(backup)) { "Unable to retain dictionary backup." }
        try {
            check(temporary.renameTo(target)) { "Unable to save book glossary." }
            check(!backup.exists() || backup.delete()) { "Unable to finish dictionary save." }
        } catch (error: Exception) {
            if (backup.exists()) { if (target.exists()) target.delete(); backup.renameTo(target) }
            throw error
        }
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
        val target = rootDirectory.resolve("${safeBookId(bookId)}.json")
        recover(target)
        !target.exists() || target.delete()
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
        require(root.get("version") == 1)
        val entriesJson = root.getJSONArray("entries")
        val entries = List(entriesJson.length()) { index ->
            val item = entriesJson.getJSONObject(index)
            BookGlossaryEntry(id = item.get("id") as String,
                sourceTerm = item.get("sourceTerm") as String, translatedTerm = item.get("translatedTerm") as String,
                displayTerm = item.get("displayTerm") as String,
                kind = GlossaryTermKind.valueOf(item.get("kind") as String),
                caseSensitive = item.get("caseSensitive") as Boolean, enabled = item.get("enabled") as Boolean)
                .also { require(it.id.isNotBlank() && it.sourceTerm.isNotBlank() && it.translatedTerm.isNotBlank()) }
        }
        require(entries.map { it.id }.distinct().size == entries.size)
        return BookGlossary(bookId = root.get("bookId") as String, entries = entries)
    }

    private fun recover(target: File) {
        val backup = File(target.path + ".bak")
        if (backup.exists()) {
            check(!target.exists() || target.delete()) { "Unable to restore dictionary." }
            check(backup.renameTo(target)) { "Unable to restore dictionary." }
        }
    }

    private companion object {
        val Locks = java.util.concurrent.ConcurrentHashMap<String, ReentrantLock>()
    }

    private fun safeBookId(bookId: String): String {
        require(bookId.isNotBlank()) { "Book glossary ID cannot be blank." }
        return com.dongholab.pagetuner.document.DocumentIds.sha256(bookId).take(32)
    }
}
