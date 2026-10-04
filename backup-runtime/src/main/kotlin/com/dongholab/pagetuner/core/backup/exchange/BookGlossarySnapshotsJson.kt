package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncKind
import org.json.JSONArray
import org.json.JSONObject

/** Explicit passive interpretation only. This codec never reads or mutates an account store. */
object BookGlossarySnapshotsJson {
    const val EXTENSION_KEY = "bookGlossarySnapshots"
    private val rootKeys = setOf("version", "snapshots")
    private val snapshotKeys = setOf("providerId", "bookId", "targetLanguage", "presence", "entries")
    private val entryKeys = setOf("id", "sourceTerm", "translatedTerm", "displayTerm", "kind", "caseSensitive", "enabled")

    fun encode(value: BookGlossarySnapshots): JSONObject {
        require(value.snapshots.size <= BookGlossarySnapshotValidation.MAX_SCOPES) { "Too many book glossary snapshot scopes." }
        val captured = value.copy(snapshots = value.snapshots.map { snapshot ->
            val entries = snapshot.entries
            require(entries == null || entries.size <= 500) { "A glossary snapshot can contain at most 500 entries." }
            snapshot.copy(entries = entries?.toList())
        })
        BookGlossarySnapshotValidation.validate(captured)
        val json = JSONObject().put("version", captured.version).put("snapshots", JSONArray().apply {
            captured.snapshots.forEach { snapshot ->
                put(JSONObject().put("providerId", snapshot.identity.providerId).put("bookId", snapshot.identity.bookId)
                    .put("targetLanguage", snapshot.identity.targetLanguage).put("presence", snapshot.presence.name.lowercase())
                    .put("entries", snapshot.entries?.let { entries -> JSONArray().apply {
                        entries.forEach { entry -> put(JSONObject().put("id", entry.id).put("sourceTerm", entry.sourceTerm)
                            .put("translatedTerm", entry.translatedTerm).put("displayTerm", entry.displayTerm)
                            .put("kind", entry.kind.name).put("caseSensitive", entry.caseSensitive).put("enabled", entry.enabled)) }
                    } } ?: JSONObject.NULL))
            }
        })
        checkExtensionSize(json)
        return json
    }

    /** Use this overload for raw input so duplicate keys and non-JSON coercions cannot disappear. */
    fun decode(raw: String): BookGlossarySnapshots = decode(parse(raw))

    /** Callers supplying an object must already have parsed raw JSON strictly. */
    fun decode(json: JSONObject): BookGlossarySnapshots {
        only(json, rootKeys)
        val version = json.get("version")
        require(version is Number && version.toDouble() == 1.0) { "Unsupported book glossary snapshots version." }
        val array = json.get("snapshots") as? JSONArray ?: error("Glossary snapshots must be an array.")
        require(array.length() <= BookGlossarySnapshotValidation.MAX_SCOPES) { "Too many book glossary snapshot scopes." }
        checkExtensionSize(json)
        return BookGlossarySnapshots(snapshots = (0 until array.length()).map { index ->
            val snapshot = array.get(index) as? JSONObject ?: error("A glossary snapshot must be an object.")
            only(snapshot, snapshotKeys)
            val presence = when (string(snapshot, "presence")) {
                "absent" -> BookGlossarySnapshotPresence.ABSENT
                "deleted" -> BookGlossarySnapshotPresence.DELETED
                "present" -> BookGlossarySnapshotPresence.PRESENT
                else -> error("Unknown glossary snapshot presence.")
            }
            val entriesValue = snapshot.get("entries")
            val entries = if (entriesValue === JSONObject.NULL) null else {
                val entryArray = entriesValue as? JSONArray ?: error("Glossary snapshot entries must be null or an array.")
                require(entryArray.length() <= 500) { "A glossary snapshot can contain at most 500 entries." }
                (0 until entryArray.length()).map { entryIndex ->
                    val entry = entryArray.get(entryIndex) as? JSONObject ?: error("A glossary entry must be an object.")
                    only(entry, entryKeys)
                    BookGlossarySyncEntry(string(entry, "id"), string(entry, "sourceTerm"), string(entry, "translatedTerm"),
                        string(entry, "displayTerm"), BookGlossarySyncKind.valueOf(string(entry, "kind")),
                        boolean(entry, "caseSensitive"), boolean(entry, "enabled"))
                }
            }
            BookGlossarySnapshot(BookGlossarySyncIdentity(string(snapshot, "providerId"), string(snapshot, "bookId"),
                string(snapshot, "targetLanguage")), presence, entries)
        }).also(BookGlossarySnapshotValidation::validate)
    }

    /** Missing data is ordinary legacy content; a supplied but invalid snapshot is never an empty glossary. */
    fun fromDocument(document: ExchangeDocument): BookGlossarySnapshots? {
        val extensions = document.extensionsJson?.let(::parse) ?: return null
        if (!extensions.has(EXTENSION_KEY)) return null
        return decode(extensions.get(EXTENSION_KEY) as? JSONObject ?: error("Book glossary snapshots must be an object."))
    }

    /** Replace only this extension, preserving allowed sibling metadata and all legacy document values. */
    fun withSnapshots(document: ExchangeDocument, value: BookGlossarySnapshots): ExchangeDocument {
        val extensions = document.extensionsJson?.let(::parse) ?: JSONObject()
        extensions.put(EXTENSION_KEY, encode(value))
        val json = extensions.toString()
        checkSize(json)
        return document.copy(extensionsJson = json).also(ExchangeJson::validate)
    }

    private fun parse(raw: String): JSONObject {
        checkSize(raw)
        // Parse the original UTF-16 string; encoding it first would replace a malformed surrogate.
        return JSONObject(StrictExchangeJson.normalize(raw)).also(::checkMetadataUnicode)
    }
    private fun checkExtensionSize(value: JSONObject) = checkSize(JSONObject().put(EXTENSION_KEY, value).toString())
    private fun checkSize(json: String) {
        checkUnicode(json)
        require(json.toByteArray(Charsets.UTF_8).size <= LibraryExchangeLimits.EXTENSIONS_BYTES) {
            "Book glossary snapshots exceed the 256 KiB extensions limit. No entries were removed."
        }
    }
    private fun checkMetadataUnicode(value: Any) {
        when (value) {
            is JSONObject -> value.keys().forEach { key -> checkUnicode(key); checkMetadataUnicode(value.get(key)) }
            is JSONArray -> (0 until value.length()).forEach { checkMetadataUnicode(value.get(it)) }
            is String -> checkUnicode(value)
        }
    }
    private fun checkUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (Character.isHighSurrogate(current)) {
                require(index < value.length && Character.isLowSurrogate(value[index++])) { "Malformed Unicode in glossary snapshot metadata." }
            } else require(!Character.isLowSurrogate(current)) { "Malformed Unicode in glossary snapshot metadata." }
        }
    }
    private fun only(json: JSONObject, keys: Set<String>) {
        require(json.keys().asSequence().toSet() == keys) { "Unexpected or missing glossary snapshot fields." }
    }
    private fun string(json: JSONObject, key: String) = json.get(key) as? String ?: error("$key must be a string.")
    private fun boolean(json: JSONObject, key: String) = json.get(key) as? Boolean ?: error("$key must be a boolean.")
}
