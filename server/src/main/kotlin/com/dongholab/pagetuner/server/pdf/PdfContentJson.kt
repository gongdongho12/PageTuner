package com.dongholab.pagetuner.server.pdf

import com.dongholab.pagetuner.core.backup.exchange.*
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/** Strict wire/storage decoding; no defaults, unknown members, coercion, or asserted upload proof. */
internal class PdfContentJson(private val json: ObjectMapper) {
    private val reader = json.readerFor(JsonNode::class.java).with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
    fun tree(bytes: ByteArray): JsonNode {
        // Wire byte limits alone still allow millions of tiny values. Bound the object graph
        // with streaming tokens before allocating JsonNodes or copying arrays into DTO lists.
        json.factory.createParser(bytes).use { parser ->
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            val containers = mutableListOf<Int>() // -1: object; otherwise the current array's entry count.
            var values = 0
            while (true) {
                val token = parser.nextToken() ?: break
                if (token.isStructStart || token.isScalarValue) {
                    require(values < 32_768) { "PDF JSON exceeds the total value limit." }; values++
                    if (containers.lastOrNull()?.let { it >= 0 } == true) {
                        val index = containers.lastIndex
                        require(containers[index] < PdfContentValidation.MAX_PARAGRAPHS) { "PDF JSON array exceeds the entry limit." }
                        containers[index]++
                    }
                }
                when (token) {
                    JsonToken.START_OBJECT -> containers.add(-1)
                    JsonToken.START_ARRAY -> containers.add(0)
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> containers.removeAt(containers.lastIndex)
                    else -> Unit
                }
            }
        }
        return reader.readValue(bytes)
    }
    fun upload(bytes: ByteArray): PdfContentUpload {
        val root = tree(bytes); fields(root, "uploadId", "content")
        return PdfContentUpload(string(root["uploadId"]), content(root["content"]))
    }
    fun content(node: JsonNode): PdfContentDocument {
        fields(node, "version", "language", "paragraphs", "assets", "payloads")
        return PdfContentDocument(integer(node["version"]), string(node["language"]), array(node["paragraphs"]).map {
            fields(it, "paragraphId", "text"); ExchangeParagraph(string(it["paragraphId"]), string(it["text"]))
        }, array(node["assets"]).map {
            fields(it, "path", "role", "paragraphId", "alt")
            ExchangeAssetReference(string(it["path"]), string(it["role"]), nullable(it["paragraphId"]), nullable(it["alt"]))
        }, array(node["payloads"]).map {
            fields(it, "path", "mimeType", "base64")
            PdfContentPayload(string(it["path"]), string(it["mimeType"]), string(it["base64"]))
        })
    }
    fun verification(bytes: ByteArray): PortableContentProof {
        val root = tree(bytes); fields(root, "proof"); return proof(root["proof"])
    }
    fun proof(node: JsonNode): PortableContentProof {
        fields(node, "version", "representation", "language", "paragraphHash", "originalFileByteLength", "originalFileSha256", "assets", "sha256")
        return PortableContentProof(integer(node["version"]), PortableRepresentation.valueOf(string(node["representation"])),
            string(node["language"]), string(node["paragraphHash"]), number(node["originalFileByteLength"]), nullable(node["originalFileSha256"]),
            array(node["assets"]).map {
                fields(it, "path", "role", "paragraphId", "alt", "mimeType", "byteLength", "sha256")
                PortableAssetProof(string(it["path"]), string(it["role"]), nullable(it["paragraphId"]), nullable(it["alt"]),
                    string(it["mimeType"]), requireNotNull(number(it["byteLength"])), string(it["sha256"]))
            }, string(node["sha256"]))
    }
    fun bytes(value: Any): ByteArray = json.writeValueAsBytes(value)
    private fun fields(node: JsonNode, vararg names: String) {
        require(node.isObject && node.fieldNames().asSequence().toSet() == names.toSet())
    }
    private fun string(node: JsonNode): String { require(node.isTextual); return node.textValue() }
    private fun nullable(node: JsonNode): String? = if (node.isNull) null else string(node)
    private fun array(node: JsonNode): List<JsonNode> { require(node.isArray); return node.toList() }
    private fun integer(node: JsonNode): Int { require(node.isIntegralNumber && node.canConvertToInt()); return node.intValue() }
    private fun number(node: JsonNode): Long? {
        if (node.isNull) return null
        require(node.isIntegralNumber && node.canConvertToLong()); return node.longValue()
    }
}
