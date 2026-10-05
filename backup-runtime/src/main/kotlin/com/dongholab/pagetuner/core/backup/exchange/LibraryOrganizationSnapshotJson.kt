package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONArray
import org.json.JSONObject

/** Passive, explicitly verified metadata. This codec neither resolves nor mutates account state. */
object LibraryOrganizationSnapshotJson {
    const val EXTENSION_KEY = "libraryOrganizationSnapshot"
    private val rootKeys = setOf("version", "identity", "presence", "organization")
    private val organizationKeys = setOf("folder", "tags", "favorite")

    fun encode(value: LibraryOrganizationSnapshot): JSONObject {
        val captured = capture(value)
        val json = JSONObject().put("version", captured.version)
            .put("identity", DocumentIdentityJson.encode(captured.identity))
            .put("presence", captured.presence.name.lowercase())
            .put("organization", captured.organization?.let { organization ->
                JSONObject().put("folder", organization.folder).put("tags", JSONArray(organization.tags))
                    .put("favorite", organization.favorite)
            } ?: JSONObject.NULL)
        checkExtension(json)
        return json
    }

    /** Raw input must use this overload: duplicate keys cannot be recovered from an existing object. */
    fun decode(raw: String): LibraryOrganizationSnapshot = decodeObject(parse(raw))

    /** Objects are copied and validated; callers must not discard raw duplicate keys before this call. */
    fun decode(json: JSONObject): LibraryOrganizationSnapshot {
        checkUnicodeTree(json)
        return decodeObject(parse(json.toString()))
    }

    /** Missing extension is ordinary legacy data and does not require document identity proof. */
    fun fromDocument(document: ExchangeDocument): LibraryOrganizationSnapshot? {
        val extensions = document.extensionsJson?.let(::parse) ?: return null
        if (!extensions.has(EXTENSION_KEY)) return null
        ExchangeJson.validate(document)
        checkDocumentSize(document)
        return readVerified(document, extensions)
    }

    /** Refuse unknown/malformed/conflicting existing data rather than silently replacing it. */
    fun withSnapshot(document: ExchangeDocument, value: LibraryOrganizationSnapshot): ExchangeDocument {
        val source = captureDocument(document)
        val extensions = source.extensionsJson?.let(::parse) ?: JSONObject()
        if (extensions.has(EXTENSION_KEY)) readVerified(source, extensions)
        val captured = capture(value)
        verify(source, captured, extensions)
        extensions.put(EXTENSION_KEY, encode(captured))
        val raw = extensions.toString()
        checkSize(raw)
        return source.copy(extensionsJson = raw).also {
            ExchangeJson.validate(it)
            checkDocumentSize(it)
        }
    }

    /** Every collection is detached before proof checks; nested entries contain only immutable values. */
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

    private fun capture(value: LibraryOrganizationSnapshot): LibraryOrganizationSnapshot = value.copy(
        organization = value.organization?.let {
            require(it.tags.size <= LibraryOrganizationSnapshotValidation.MAX_TAGS) { "Too many organization snapshot tags." }
            it.copy(tags = it.tags.toList())
        },
    ).also(LibraryOrganizationSnapshotValidation::validate)

    private fun readVerified(document: ExchangeDocument, extensions: JSONObject): LibraryOrganizationSnapshot {
        val json = extensions.get(EXTENSION_KEY) as? JSONObject ?: error("Library organization snapshot must be an object.")
        return decodeObject(json).also { verify(document, it, extensions) }
    }

    private fun verify(document: ExchangeDocument, value: LibraryOrganizationSnapshot, extensions: JSONObject) {
        LibraryOrganizationSnapshotValidation.validateDocument(document, value)
        if (extensions.has("documentIdentity")) {
            val json = extensions.get("documentIdentity") as? JSONObject ?: error("Document identity must be an object.")
            require(DocumentIdentityJson.decode(json) == value.identity) {
                "Library organization snapshot conflicts with the document identity extension."
            }
        }
    }

    private fun decodeObject(json: JSONObject): LibraryOrganizationSnapshot {
        exact(json, rootKeys)
        val version = json.get("version")
        require(version is Number && version.toDouble() == 1.0) { "Unsupported library organization snapshot version." }
        val identity = DocumentIdentityJson.decode(json.get("identity") as? JSONObject ?: error("Snapshot identity must be an object."))
        val presence = when (json.string("presence")) {
            "absent" -> LibraryOrganizationSnapshotPresence.ABSENT
            "present" -> LibraryOrganizationSnapshotPresence.PRESENT
            else -> error("Unknown library organization snapshot presence.")
        }
        val organizationValue = json.get("organization")
        val organization = if (organizationValue === JSONObject.NULL) null else {
            val objectValue = organizationValue as? JSONObject ?: error("Snapshot organization must be an object or null.")
            exact(objectValue, organizationKeys)
            val tags = objectValue.get("tags") as? JSONArray ?: error("Snapshot tags must be an array.")
            require(tags.length() <= LibraryOrganizationSnapshotValidation.MAX_TAGS) { "Too many organization snapshot tags." }
            LibraryOrganizationSnapshotValue(objectValue.string("folder"), (0 until tags.length()).map {
                tags.get(it) as? String ?: error("Snapshot tags must be strings.")
            }, objectValue.boolean("favorite"))
        }
        checkExtension(json)
        return LibraryOrganizationSnapshot(identity = identity, presence = presence, organization = organization)
            .also(LibraryOrganizationSnapshotValidation::validate)
    }

    private fun parse(raw: String): JSONObject {
        // Check the original UTF-16 string before any UTF-8 encoding can replace a lone surrogate.
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
        require(raw.length <= LibraryExchangeLimits.EXTENSIONS_BYTES) { "Library organization snapshot exceeds the extensions limit." }
        checkUnicode(raw)
        require(raw.toByteArray(Charsets.UTF_8).size <= LibraryExchangeLimits.EXTENSIONS_BYTES) {
            "Library organization snapshot exceeds the 256 KiB extensions limit. No metadata was removed."
        }
    }

    private fun checkUnicodeTree(value: Any, depth: Int = 0) {
        require(depth <= 16) { "Extensions are nested too deeply." }
        when (value) {
            is JSONObject -> value.keys().forEach { key -> checkUnicode(key); checkUnicodeTree(value.get(key), depth + 1) }
            is JSONArray -> (0 until value.length()).forEach { checkUnicodeTree(value.get(it), depth + 1) }
            is String -> checkUnicode(value)
            is Number, is Boolean -> Unit
            else -> require(value === JSONObject.NULL) { "Organization snapshot metadata must use strict JSON value types." }
        }
    }

    private fun checkUnicode(value: String) {
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (current.isHighSurrogate()) {
                require(index < value.length && value[index++].isLowSurrogate()) { "Malformed Unicode in organization snapshot metadata." }
            } else require(!current.isLowSurrogate()) { "Malformed Unicode in organization snapshot metadata." }
        }
    }

    private fun exact(json: JSONObject, expected: Set<String>) {
        require(json.keys().asSequence().toSet() == expected) { "Unexpected or missing organization snapshot fields." }
    }
}
