package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.portable.PortableDocumentMapper
import com.dongholab.pagetuner.portable.PortableReaderMapping
import java.net.URI
import java.util.UUID
import org.json.JSONObject

data class ServerReadingAnchor(val paragraphId: String, val characterOffset: Int)
data class ServerReadingProgress(
    val kind: String, val recordId: String, val version: Long,
    val anchor: ServerReadingAnchor?, val updatedAt: String?,
)
data class ServerReadingMutation(val expectedVersion: Long, val mutationId: String, val anchor: ServerReadingAnchor)
class ServerReadingProgressConflict(val current: ServerReadingProgress) : Exception("Reading progress conflict")
class ServerReadingProgressRateLimited(val retryAfterSeconds: Long) : Exception("Reading progress rate limited")

internal const val MaxReadingVersion = 9_007_199_254_740_991L
internal fun ServerLibraryKind.progressKind() = if (this == ServerLibraryKind.Originals) "ORIGINAL" else "TRANSLATION"

/** Account and origin form part of the device key. Credentials never enter the key or stored data. */
internal fun serverReadingAccountKey(endpoint: String, username: String): String {
    val uri = URI(endpoint.trim())
    val port = uri.port.takeUnless { it == -1 || it == 443 && uri.scheme.equals("https", true) || it == 80 && uri.scheme.equals("http", true) }
    val origin = "${uri.scheme.lowercase(java.util.Locale.ROOT)}://${uri.host.lowercase(java.util.Locale.ROOT)}${port?.let { ":$it" }.orEmpty()}"
    return StableContentHash.sha256(origin + "\n" + username.trim().lowercase(java.util.Locale.ROOT))
}

data class ServerReadingConnection(val accountKey: String, val client: HttpTranslationStore)
data class ServerReadingDocument(val accountKey: String, val source: ServerLibraryDocument, val mapping: PortableReaderMapping,
    val openId: String = UUID.randomUUID().toString()) {
    val key get() = "$accountKey:${source.entry.kind.progressKind()}:${source.entry.recordId}"
    val readerId get() = mapping.document.id
    fun anchor(page: Int) = mapping.anchors.getOrNull(page)?.let { ServerReadingAnchor(it.paragraphId, it.startOffset) }
    fun page(anchor: ServerReadingAnchor) = mapping.pageFor(com.dongholab.pagetuner.core.backup.exchange.ExchangeAnchor(anchor.paragraphId, anchor.characterOffset))
    fun validate(anchor: ServerReadingAnchor) {
        ServerReadingProgressJson.validateAnchor(anchor)
        val paragraphs = source.storedTranslation?.artifact?.paragraphs?.map { it.paragraphId to it.text }
            ?: source.sourceContent?.paragraphs?.map { it.paragraphId to it.text } ?: error("Missing paragraphs")
        val text = requireNotNull(paragraphs.firstOrNull { it.first == anchor.paragraphId }?.second)
        val offset = anchor.characterOffset
        require(offset <= text.length && !(offset in 1 until text.length && text[offset - 1].isHighSurrogate() && text[offset].isLowSurrogate()))
    }
    companion object {
        fun create(accountKey: String, source: ServerLibraryDocument): ServerReadingDocument {
            val exported = PortableDocumentMapper.server(source).documents.single()
            val readerId = "server-reading:$accountKey:${source.entry.kind.progressKind()}:${source.entry.recordId}"
            return ServerReadingDocument(accountKey, source, PortableDocumentMapper.reader(exported, emptyList(), readerId))
        }
    }
}

internal object ServerReadingProgressJson {
    fun validateTarget(kind: String, recordId: String) {
        require(kind in setOf("ORIGINAL", "TRANSLATION") && UUID.fromString(recordId).toString() == recordId)
    }
    fun validateAnchor(anchor: ServerReadingAnchor) {
        require(anchor.paragraphId.length in 1..200 && anchor.paragraphId.none(Char::isISOControl) && anchor.characterOffset >= 0)
    }
    fun anchor(value: JSONObject): ServerReadingAnchor = ServerReadingAnchor(value.get("paragraphId") as String,
        integer(value, "characterOffset", Int.MAX_VALUE.toLong()).toInt()).also(::validateAnchor)
    fun encode(anchor: ServerReadingAnchor) = JSONObject().put("paragraphId", anchor.paragraphId).put("characterOffset", anchor.characterOffset)
    fun encode(mutation: ServerReadingMutation): JSONObject {
        require(mutation.expectedVersion in 0 until MaxReadingVersion && UUID.fromString(mutation.mutationId).toString() == mutation.mutationId)
        validateAnchor(mutation.anchor)
        return JSONObject().put("expectedVersion", mutation.expectedVersion).put("mutationId", mutation.mutationId).put("anchor", encode(mutation.anchor))
    }
    fun mutation(value: JSONObject) = ServerReadingMutation(integer(value, "expectedVersion", MaxReadingVersion - 1),
        value.get("mutationId") as String, anchor(value.getJSONObject("anchor"))).also { encode(it) }
    fun decode(value: JSONObject, kind: String, recordId: String): ServerReadingProgress {
        validateTarget(kind, recordId)
        require(value.get("kind") == kind && value.get("recordId") == recordId)
        val version = integer(value, "version", MaxReadingVersion)
        val anchor = if (value.get("anchor") == JSONObject.NULL) null else anchor(value.getJSONObject("anchor"))
        val updated = if (value.get("updatedAt") == JSONObject.NULL) null else (value.get("updatedAt") as String).also {
            require(it.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,9})?Z")))
        }
        require(if (version == 0L) anchor == null && updated == null else anchor != null && updated != null)
        return ServerReadingProgress(kind, recordId, version, anchor, updated)
    }
    fun encode(value: ServerReadingProgress) = JSONObject().put("kind", value.kind).put("recordId", value.recordId).put("version", value.version)
        .put("anchor", value.anchor?.let(::encode) ?: JSONObject.NULL).put("updatedAt", value.updatedAt ?: JSONObject.NULL)
    private fun integer(value: JSONObject, key: String, max: Long): Long {
        val number = value.get(key)
        require(number is Int || number is Long)
        return (number as Number).toLong().also { require(it in 0..max) }
    }
}
