package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONObject

/** Explicit opt-in interpretation; the ZIP codec continues preserving passive/unknown extensions. */
object DocumentIdentityJson {
    private val common = setOf("version", "kind", "contentProviderId", "bookId", "chapterId", "sourceRevision", "sourceLanguage", "paragraphHash")
    private val translation = setOf("targetLanguage", "translationProviderId", "modelId", "promptRevision", "glossaryRevision", "artifactId", "revision", "payloadHash")

    fun encode(identity: DocumentIdentity): JSONObject {
        DocumentIdentities.validate(identity)
        return JSONObject().put("version", identity.version).put("kind", identity.kind.name).put("contentProviderId", identity.contentProviderId)
            .put("bookId", identity.bookId).put("chapterId", identity.chapterId).put("sourceRevision", identity.sourceRevision)
            .put("sourceLanguage", identity.sourceLanguage).put("paragraphHash", identity.paragraphHash).apply {
                if (identity.kind == DocumentIdentityKind.TRANSLATION) {
                    put("targetLanguage", identity.targetLanguage); put("translationProviderId", identity.translationProviderId)
                    put("modelId", identity.modelId); put("promptRevision", identity.promptRevision); put("glossaryRevision", identity.glossaryRevision)
                    put("artifactId", identity.artifactId); put("revision", identity.revision); put("payloadHash", identity.payloadHash)
                }
            }
    }

    fun decode(json: JSONObject): DocumentIdentity {
        val kind = DocumentIdentityKind.valueOf(json.get("kind") as String)
        require(json.keys().asSequence().toSet() == common + if (kind == DocumentIdentityKind.TRANSLATION) translation else emptySet())
        // The ZIP codec intentionally normalizes JSON numbers to binary64 before org.json parsing.
        // Require numeric one here; the HTTP parser separately rejects fractional literal spelling.
        val version = json.get("version")
        require(version is Number && version.toDouble() == 1.0)
        fun string(name: String) = json.get(name) as String
        fun optional(name: String) = if (kind == DocumentIdentityKind.TRANSLATION) string(name) else null
        return DocumentIdentity(kind = kind, contentProviderId = string("contentProviderId"), bookId = string("bookId"),
            chapterId = string("chapterId"), sourceRevision = string("sourceRevision"), sourceLanguage = string("sourceLanguage"),
            paragraphHash = string("paragraphHash"), targetLanguage = optional("targetLanguage"), translationProviderId = optional("translationProviderId"),
            modelId = optional("modelId"), promptRevision = optional("promptRevision"), glossaryRevision = optional("glossaryRevision"),
            artifactId = optional("artifactId"), revision = optional("revision"), payloadHash = optional("payloadHash")).also(DocumentIdentities::validate)
    }

    /** Missing identity is ordinary legacy data. A supplied but malformed identity fails verification only. */
    fun fromDocument(document: ExchangeDocument): DocumentIdentity? {
        val extensions = document.extensionsJson?.let { ExchangeJson.parseObject(it.toByteArray(Charsets.UTF_8)) } ?: return null
        if (!extensions.has("documentIdentity")) return null
        return decode(extensions.getJSONObject("documentIdentity")).also { DocumentIdentities.validateDocument(document, it) }
    }

    fun withIdentity(document: ExchangeDocument, identity: DocumentIdentity): ExchangeDocument {
        DocumentIdentities.validateDocument(document, identity)
        val extensions = document.extensionsJson?.let { ExchangeJson.parseObject(it.toByteArray(Charsets.UTF_8)) } ?: JSONObject()
        extensions.put("documentIdentity", encode(identity))
        return document.copy(extensionsJson = extensions.toString())
    }
}
