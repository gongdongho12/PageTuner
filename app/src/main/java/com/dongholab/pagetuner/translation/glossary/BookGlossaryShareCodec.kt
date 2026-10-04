package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.document.DocumentIds
import org.json.JSONArray
import org.json.JSONObject

data class SharedBookGlossary(
    val sourceBookId: String,
    val bookTitle: String,
    val entries: List<BookGlossaryEntry>,
)

/** Versioned, provider-independent package for sharing one work's dictionary. */
object BookGlossaryShareCodec {
    private const val Schema = "pagetuner-book-glossary"
    private const val Version = 1
    private const val MaxEntries = 500
    private const val MaxTermCharacters = 160

    fun encode(glossary: BookGlossary, bookTitle: String): String = JSONObject()
        .put("schema", Schema)
        .put("version", Version)
        .put("sourceBookId", glossary.bookId)
        .put("bookTitle", bookTitle.trim())
        .put("entries", JSONArray().apply {
            glossary.entries.take(MaxEntries).forEach { entry ->
                put(JSONObject()
                    .put("sourceTerm", entry.normalizedSourceTerm)
                    .put("translatedTerm", entry.normalizedTranslatedTerm)
                    .put("displayTerm", entry.normalizedDisplayTerm)
                    .put("kind", entry.kind.name)
                    .put("caseSensitive", entry.caseSensitive)
                    .put("enabled", entry.enabled))
            }
        })
        .toString(2)

    fun decode(raw: String): SharedBookGlossary {
        val root = JSONObject(raw)
        require(root.optString("schema") == Schema) { "Unsupported dictionary file." }
        require(root.optInt("version") == Version) { "Unsupported dictionary version." }
        val entriesJson = root.optJSONArray("entries") ?: JSONArray()
        require(entriesJson.length() <= MaxEntries) { "Dictionary contains too many entries." }
        val entries = buildList {
            for (index in 0 until entriesJson.length()) {
                val item = entriesJson.optJSONObject(index) ?: continue
                val source = item.optString("sourceTerm").trim().take(MaxTermCharacters)
                val translated = item.optString("translatedTerm").trim().take(MaxTermCharacters)
                if (source.isBlank() || translated.isBlank()) continue
                add(BookGlossaryEntry(
                    id = "shared-${DocumentIds.sha256(source.lowercase()).take(16)}",
                    sourceTerm = source,
                    translatedTerm = translated,
                    displayTerm = item.optString("displayTerm").trim().take(MaxTermCharacters),
                    kind = runCatching { GlossaryTermKind.valueOf(item.optString("kind")) }
                        .getOrDefault(GlossaryTermKind.Term),
                    caseSensitive = item.optBoolean("caseSensitive", false),
                    enabled = item.optBoolean("enabled", true),
                ))
            }
        }
        return SharedBookGlossary(
            sourceBookId = root.optString("sourceBookId").trim(),
            bookTitle = root.optString("bookTitle").trim(),
            entries = entries,
        )
    }
}
