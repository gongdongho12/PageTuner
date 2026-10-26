package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONArray
import org.json.JSONObject

/** Strict passive metadata codec; no network, binding, local position fallback or mutation authority. */
object ReadingProgressSnapshotJson {
    const val EXTENSION_KEY = "readingProgressSnapshot"
    private val rootKeys = setOf("version", "identity", "presence", "anchor")
    private val anchorKeys = setOf("paragraphId", "characterOffset")

    fun encode(value: ReadingProgressSnapshot): JSONObject {
        ReadingProgressSnapshotValidation.validate(value)
        val json = JSONObject().put("version", value.version)
            .put("identity", DocumentIdentityJson.encode(value.identity))
            .put("presence", value.presence.name.lowercase())
            .put("anchor", value.anchor?.let {
                JSONObject().put("paragraphId", it.paragraphId).put("characterOffset", it.characterOffset)
            } ?: JSONObject.NULL)
        checkExtension(json)
        return json
    }

    /** Raw duplicate keys must not be lost through JSONObject construction before calling this. */
    fun decode(raw: String): ReadingProgressSnapshot = decodeObject(parse(raw))

    fun decode(json: JSONObject): ReadingProgressSnapshot {
        checkUnicodeTree(json)
        return decodeObject(parse(json.toString()))
    }

    /** Missing extension is legacy data, not an absent server observation. */
    fun fromDocument(document: ExchangeDocument): ReadingProgressSnapshot? {
        val extensions = document.extensionsJson?.let(::parse) ?: return null
        if (!extensions.has(EXTENSION_KEY)) return null
        ExchangeJson.validate(document)
        checkDocumentSize(document)
        return readVerified(document, extensions)
    }

    /** Existing unsupported or inconsistent own data is preserved by refusing replacement. */
    fun withSnapshot(document: ExchangeDocument, value: ReadingProgressSnapshot): ExchangeDocument {
        val source = captureDocument(document)
        ExchangeJson.validate(source)
        checkDocumentSize(source)
        val extensions = source.extensionsJson?.let(::parse) ?: JSONObject()
        if (extensions.has(EXTENSION_KEY)) readVerified(source, extensions)
        verify(source, value, extensions)
        extensions.put(EXTENSION_KEY, encode(value))
        val raw = extensions.toString()
        checkSize(raw)
        return source.copy(extensionsJson = raw).also {
            ExchangeJson.validate(it)
            checkDocumentSize(it)
        }
    }

    private fun captureDocument(value: ExchangeDocument): ExchangeDocument {
        require(value.paragraphs.size <= LibraryExchangeLimits.MAX_PARAGRAPHS &&
            value.outline.size <= LibraryExchangeLimits.MAX_PARAGRAPHS &&
            value.notes.size <= LibraryExchangeLimits.MAX_NOTES &&
            value.glossary.size <= LibraryExchangeLimits.MAX_GLOSSARY &&
            value.assets.size <= LibraryExchangeLimits.MAX_ENTRIES && value.organization.tags.size <= 100) {
            "Document collections exceed the exchange limits."
        }
        return value.copy(paragraphs = value.paragraphs.toList(), outline = value.outline.toList(), notes = value.notes.toList(),
            organization = value.organization.copy(tags = value.organization.tags.toList()), glossary = value.glossary.toList(),
            assets = value.assets.toList())
    }

    private fun checkDocumentSize(document: ExchangeDocument) {
        require(ExchangeJson.encode(document).size <= LibraryExchangeLimits.DOCUMENT_BYTES) { "Document JSON exceeds 8 MiB." }
    }

    private fun readVerified(document: ExchangeDocument, extensions: JSONObject): ReadingProgressSnapshot {
        val json = extensions.get(EXTENSION_KEY) as? JSONObject ?: error("Reading progress snapshot must be an object.")
        return decodeObject(json).also { verify(document, it, extensions) }
    }

    private fun verify(document: ExchangeDocument, value: ReadingProgressSnapshot, extensions: JSONObject) {
        ReadingProgressSnapshotValidation.validateDocument(document, value)
        if (extensions.has("documentIdentity")) {
            val json = extensions.get("documentIdentity") as? JSONObject ?: error("Document identity must be an object.")
            require(DocumentIdentityJson.decode(json) == value.identity) { "Reading progress snapshot conflicts with document identity." }
        }
    }

    private fun decodeObject(json: JSONObject): ReadingProgressSnapshot {
        exact(json, rootKeys)
        val version = json.get("version")
        require(version is Number && version.toDouble() == 1.0) { "Unsupported reading progress snapshot version." }
        val identity = DocumentIdentityJson.decode(json.get("identity") as? JSONObject ?: error("Snapshot identity must be an object."))
        val presence = when (json.string("presence")) {
            "absent" -> ReadingProgressSnapshotPresence.ABSENT
            "present" -> ReadingProgressSnapshotPresence.PRESENT
            else -> error("Unknown reading progress snapshot presence.")
        }
        val anchorValue = json.get("anchor")
        val anchor = if (anchorValue === JSONObject.NULL) null else {
            val objectValue = anchorValue as? JSONObject ?: error("Snapshot anchor must be an object or null.")
            exact(objectValue, anchorKeys)
            val offset = objectValue.get("characterOffset")
            require(offset is Number && offset.toDouble().isFinite() && offset.toDouble() in 0.0..Int.MAX_VALUE.toDouble() &&
                offset.toDouble() == offset.toInt().toDouble()) { "Snapshot offset must be an integer in 0..2147483647." }
            ReadingProgressSnapshotAnchor(objectValue.string("paragraphId"), offset.toInt())
        }
        checkExtension(json)
        return ReadingProgressSnapshot(identity = identity, presence = presence, anchor = anchor)
            .also(ReadingProgressSnapshotValidation::validate)
    }

    private fun parse(raw: String): JSONObject {
        checkSize(raw)
        val json = JSONObject(StrictExchangeJson.normalize(raw))
        checkUnicodeTree(json)
        ExchangeJson.validateExtensions(raw)
        return json
    }

    private fun checkExtension(json: JSONObject) {
        checkUnicodeTree(json)
        val wrapped = JSONObject().put(EXTENSION_KEY, json).toString()
        checkSize(wrapped)
        ExchangeJson.validateExtensions(wrapped)
    }

    private fun checkSize(raw: String) {
        require(raw.length <= LibraryExchangeLimits.EXTENSIONS_BYTES) { "Reading progress snapshot exceeds the extensions limit." }
        checkUnicode(raw)
        require(raw.toByteArray(Charsets.UTF_8).size <= LibraryExchangeLimits.EXTENSIONS_BYTES) {
            "Reading progress snapshot exceeds the 256 KiB extensions limit. No metadata was removed."
        }
    }

    private fun checkUnicodeTree(value: Any, depth: Int = 0) {
        require(depth <= 16) { "Extensions are nested too deeply." }
        when (value) {
            is JSONObject -> value.keys().forEach { key -> checkUnicode(key); checkUnicodeTree(value.get(key), depth + 1) }
            is JSONArray -> (0 until value.length()).forEach { checkUnicodeTree(value.get(it), depth + 1) }
            is String -> checkUnicode(value)
            is Number, is Boolean -> Unit
            else -> require(value === JSONObject.NULL) { "Reading progress snapshot requires strict JSON values." }
        }
    }

    private fun checkUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (current.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate()) { "Malformed Unicode in reading progress snapshot." }
            else require(!current.isLowSurrogate()) { "Malformed Unicode in reading progress snapshot." }
        }
    }

    private fun exact(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "Unexpected or missing reading progress snapshot fields." }
    }
}
