package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.glossary.*
import org.json.JSONArray
import org.json.JSONObject

/** Explicit interpretation only. Generic ZIP imports retain this extension without adopting any account records. */
object AccountGlossarySnapshotJson {
    const val EXTENSION_KEY = "accountGlossarySnapshots"
    private val snapshotFields = setOf("providerId", "bookId", "targetLanguage", "state", "entries")
    private val entryFields = setOf("id", "sourceTerm", "translatedTerm", "displayTerm", "kind", "caseSensitive", "enabled")
    private fun fields(json: JSONObject, expected: Set<String>) { require(json.keys().asSequence().toSet() == expected) }
    fun encode(value: AccountGlossarySnapshots, source: DocumentIdentity): JSONObject {
        AccountGlossarySnapshotValidation.validate(value, source)
        return JSONObject().put("version", 1).put("snapshots", JSONArray(value.snapshots.map { snapshot ->
            JSONObject().put("providerId", snapshot.providerId).put("bookId", snapshot.bookId).put("targetLanguage", snapshot.targetLanguage)
                .put("state", snapshot.state.name).put("entries", snapshot.entries?.let { entries -> JSONArray(entries.map { entry ->
                    JSONObject().put("id", entry.id).put("sourceTerm", entry.sourceTerm).put("translatedTerm", entry.translatedTerm)
                        .put("displayTerm", entry.displayTerm).put("kind", entry.kind.name)
                        .put("caseSensitive", entry.caseSensitive).put("enabled", entry.enabled)
                }) } ?: JSONObject.NULL)
        }))
    }
    fun decode(json: JSONObject, source: DocumentIdentity): AccountGlossarySnapshots {
        fields(json, setOf("version", "snapshots"))
        val version = json.get("version")
        require(version is Number && version.toDouble() == 1.0)
        val array = json.getJSONArray("snapshots")
        require(array.length() <= AccountGlossarySnapshotValidation.MAX_SNAPSHOTS)
        val snapshots = List(array.length()) { index ->
            val value = array.getJSONObject(index); fields(value, snapshotFields)
            val entries = if (value.get("entries") == JSONObject.NULL) null else value.getJSONArray("entries").let { items ->
                require(items.length() <= 500)
                List(items.length()) { item ->
                    val entry = items.getJSONObject(item); fields(entry, entryFields)
                    BookGlossarySyncEntry(entry.get("id") as String, entry.get("sourceTerm") as String,
                        entry.get("translatedTerm") as String, entry.get("displayTerm") as String,
                        BookGlossarySyncKind.valueOf(entry.get("kind") as String), entry.get("caseSensitive") as Boolean, entry.get("enabled") as Boolean)
                }
            }
            AccountGlossarySnapshot(value.get("providerId") as String, value.get("bookId") as String,
                value.get("targetLanguage") as String, AccountGlossarySnapshotState.valueOf(value.get("state") as String), entries)
        }
        return AccountGlossarySnapshots(snapshots = snapshots).also { AccountGlossarySnapshotValidation.validate(it, source) }
    }
    fun fromDocument(document: ExchangeDocument): AccountGlossarySnapshots? {
        val extensions = extensions(document)
        if (!extensions.has(EXTENSION_KEY)) return null
        val source = requireNotNull(DocumentIdentityJson.fromDocument(document)) { "Original document identity is required." }
        return decode(extensions.getJSONObject(EXTENSION_KEY), source)
    }
    fun withSnapshot(document: ExchangeDocument, snapshot: AccountGlossarySnapshot, selectedTargetLanguage: String): ExchangeDocument {
        val source = requireNotNull(DocumentIdentityJson.fromDocument(document)) { "Original document identity is required." }
        AccountGlossarySnapshotValidation.validateRequested(snapshot, source, selectedTargetLanguage)
        val extensions = extensions(document)
        val previous = if (extensions.has(EXTENSION_KEY)) decode(extensions.getJSONObject(EXTENSION_KEY), source)
            else AccountGlossarySnapshots(snapshots = emptyList())
        val updated = if (previous.snapshots.any { it.identity == snapshot.identity }) previous.snapshots.map {
            if (it.identity == snapshot.identity) snapshot else it
        } else previous.snapshots + snapshot
        extensions.put(EXTENSION_KEY, encode(AccountGlossarySnapshots(snapshots = updated), source))
        val result = extensions.toString()
        require(result.toByteArray(Charsets.UTF_8).size <= LibraryExchangeLimits.EXTENSIONS_BYTES) { "Complete account glossary snapshots exceed the portable extension limit." }
        return document.copy(extensionsJson = result)
    }
    private fun extensions(document: ExchangeDocument): JSONObject {
        val bytes = document.extensionsJson?.toByteArray(Charsets.UTF_8) ?: return JSONObject()
        require(bytes.size <= LibraryExchangeLimits.EXTENSIONS_BYTES)
        return ExchangeJson.parseObject(bytes)
    }
}
