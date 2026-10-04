package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.glossary.*
import com.dongholab.pagetuner.translation.glossary.*
import org.json.JSONArray
import org.json.JSONObject

/** The original source identity is distinct from a local library ID or server document UUID. */
data class BookGlossaryTarget(val accountKey: String, val identity: BookGlossarySyncIdentity) {
    val key get() = "$accountKey:${identity.providerId.length}:${identity.providerId}${identity.bookId.length}:${identity.bookId}${identity.targetLanguage.length}:${identity.targetLanguage}"
}

/** A wrapper keeps a queued deletion (entries=null) distinct from no queued edit. */
data class BookGlossaryValue(val entries: List<BookGlossaryEntry>?)
data class ServerBookGlossary(val identity: BookGlossarySyncIdentity, val version: Long, val value: BookGlossaryValue, val updatedAt: String?)
data class BookGlossaryMutation(val expectedVersion: Long, val mutationId: String, val value: BookGlossaryValue)
class BookGlossaryConflict(val current: ServerBookGlossary) : Exception("Book glossary conflict")
class BookGlossaryRateLimited(val retryAfterSeconds: Long) : Exception("Book glossary rate limited")

fun ServerReadingDocument.glossaryTarget(targetLanguage: String): BookGlossaryTarget? = runCatching {
    val book = source.sourceContent?.identity?.book ?: source.storedTranslation?.artifact?.chapter?.book ?: return null
    // A saved translation's display aliases belong to its actual language, not a future translation setting.
    val language = source.storedTranslation?.artifact?.targetLanguage?.lowercase(java.util.Locale.ROOT) ?: targetLanguage
    BookGlossaryTarget(accountKey, BookGlossarySyncIdentity(book.providerId, book.bookId, language))
        .also { BookGlossarySyncValidation.validateIdentity(it.identity) }
}.getOrNull()

internal fun BookGlossaryEntry.syncEntry() = BookGlossarySyncEntry(id, sourceTerm, translatedTerm, displayTerm,
    BookGlossarySyncKind.valueOf(kind.name), caseSensitive, enabled)

internal object ServerBookGlossaryJson {
    private fun fields(value: JSONObject, expected: Set<String>) { require(value.keys().asSequence().toSet() == expected) }
    fun validate(value: BookGlossaryValue) { value.entries?.let { BookGlossarySyncValidation.validateEntries(it.map(BookGlossaryEntry::syncEntry)) } }
    fun validateRequest(identity: BookGlossarySyncIdentity, value: BookGlossaryValue) {
        // Reserve the largest version representation so a queued snapshot always fits when promoted.
        encode(identity, BookGlossaryMutation(MaxReadingVersion - 1, "00000000-0000-4000-8000-000000000000", value))
    }
    fun identity(value: JSONObject) = BookGlossarySyncIdentity(value.get("providerId") as String, value.get("bookId") as String,
        value.get("targetLanguage") as String).also(BookGlossarySyncValidation::validateIdentity)
    fun encode(identity: BookGlossarySyncIdentity): JSONObject {
        BookGlossarySyncValidation.validateIdentity(identity)
        return JSONObject().put("providerId", identity.providerId).put("bookId", identity.bookId).put("targetLanguage", identity.targetLanguage)
    }
    fun entry(value: JSONObject): BookGlossaryEntry {
        fields(value, setOf("id", "sourceTerm", "translatedTerm", "displayTerm", "kind", "caseSensitive", "enabled"))
        return BookGlossaryEntry(value.get("id") as String, value.get("sourceTerm") as String, value.get("translatedTerm") as String,
            value.get("displayTerm") as String, GlossaryTermKind.valueOf(value.get("kind") as String), value.get("caseSensitive") as Boolean,
            value.get("enabled") as Boolean).also { BookGlossarySyncValidation.validateEntry(it.syncEntry()) }
    }
    fun encode(entry: BookGlossaryEntry): JSONObject = JSONObject().put("id", entry.id).put("sourceTerm", entry.sourceTerm)
        .put("translatedTerm", entry.translatedTerm).put("displayTerm", entry.displayTerm).put("kind", entry.kind.name)
        .put("caseSensitive", entry.caseSensitive).put("enabled", entry.enabled)
        .also { BookGlossarySyncValidation.validateEntry(entry.syncEntry()) }
    fun value(json: JSONObject): BookGlossaryValue = BookGlossaryValue(if (json.get("entries") == JSONObject.NULL) null else
        json.getJSONArray("entries").let { array -> List(array.length()) { entry(array.getJSONObject(it)) } }).also(::validate)
    fun encode(value: BookGlossaryValue): JSONObject = JSONObject().put("entries",
        value.entries?.let { JSONArray(it.map { entry -> encode(entry) }) } ?: JSONObject.NULL).also { validate(value) }
    fun view(json: JSONObject, expected: BookGlossarySyncIdentity): ServerBookGlossary {
        fields(json, setOf("providerId", "bookId", "targetLanguage", "version", "entries", "updatedAt"))
        require(identity(json) == expected)
        val version = ServerReadingNotesJson.number(json, "version")
        val value = value(json)
        val updated = if (json.get("updatedAt") == JSONObject.NULL) null else ServerReadingNotesJson.timestamp(json.get("updatedAt") as String)
        require(if (version == 0L) value.entries == null && updated == null else updated != null)
        return ServerBookGlossary(expected, version, value, updated)
    }
    fun encode(view: ServerBookGlossary): JSONObject = encode(view.identity).put("version", view.version)
        .put("entries", encode(view.value).get("entries")).put("updatedAt", view.updatedAt ?: JSONObject.NULL)
        .also { this.view(it, view.identity) }
    fun encode(identity: BookGlossarySyncIdentity, mutation: BookGlossaryMutation): JSONObject {
        require(mutation.expectedVersion in 0 until MaxReadingVersion); ServerReadingNotesJson.uuid(mutation.mutationId)
        return encode(identity).put("expectedVersion", mutation.expectedVersion).put("mutationId", mutation.mutationId)
            .put("entries", encode(mutation.value).get("entries"))
            .also { require(it.toString().toByteArray(Charsets.UTF_8).size <= 1_048_576) }
    }
    fun mutation(json: JSONObject, expected: BookGlossarySyncIdentity): BookGlossaryMutation {
        fields(json, setOf("providerId", "bookId", "targetLanguage", "expectedVersion", "mutationId", "entries"))
        require(identity(json) == expected)
        return BookGlossaryMutation(ServerReadingNotesJson.number(json, "expectedVersion"), json.get("mutationId") as String, value(json))
            .also { encode(expected, it) }
    }
}

/** Automatic suggestions may append entries; never reorder, replace or deduplicate accepted data. */
internal fun appendBookGlossaryAliases(value: BookGlossaryValue, suggestions: List<CharacterAliasSuggestion>): BookGlossaryValue {
    val existing = value.entries ?: return value
    val entries = existing.toMutableList()
    suggestions.forEach { suggestion ->
        if (entries.size >= 500 || entries.any { it.sourceTerm.trim().equals(suggestion.sourceTerm.trim(), ignoreCase = true) }) return@forEach
        val entry = BookGlossaryEntry(java.util.UUID.randomUUID().toString(), suggestion.sourceTerm.trim(), suggestion.alias.trim(),
            suggestion.alias.trim(), GlossaryTermKind.Character)
        if (runCatching { BookGlossarySyncValidation.validateEntry(entry.syncEntry()) }.isSuccess) entries += entry
    }
    return BookGlossaryValue(entries)
}
