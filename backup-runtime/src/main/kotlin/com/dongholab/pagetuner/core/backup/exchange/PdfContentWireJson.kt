package com.dongholab.pagetuner.core.backup.exchange

import org.json.JSONArray
import org.json.JSONObject

/** Strict bounded HTTP DTO codec. It grants no binding, provenance, or synchronization authority. */
object PdfContentWireJson {
    fun decodeCsrf(bytes: ByteArray): Pair<String, String> = invalidInput {
        val root = parse(bytes, 64 * 1024); exact(root, "headerName", "token")
        val header = root.string("headerName"); val token = root.string("token")
        require(header.equals("X-CSRF-TOKEN", true) || header.equals("X-XSRF-TOKEN", true))
        require(token.isNotEmpty() && token.length <= 4096 && token.none(Char::isISOControl))
        header to token
    }

    fun decodeProblemCode(bytes: ByteArray): String? = invalidInput {
        val root = parse(bytes, 64 * 1024)
        require(root.keys().asSequence().all { it in setOf("type", "title", "status", "detail", "instance", "code") })
        listOf("type", "title", "detail", "instance").filter(root::has).forEach { root.string(it) }
        if (root.has("status")) require(integer(root, "status", 599) >= 100)
        if (!root.has("code")) null else root.string("code").also { require(Regex("[A-Z][A-Z0-9_]{0,79}").matches(it)) }
    }

    fun encodeUpload(value: PdfContentUpload): ByteArray {
        val checked = PdfContentValidation.validate(value)
        return bytes(JSONObject().put("uploadId", checked.uploadId).put("content", contentJson(checked.content)), PdfContentValidation.MAX_REQUEST_BYTES)
    }

    fun encodeVerification(proof: PortableContentProof): ByteArray {
        val checked = snapshotProof(proof)
        return bytes(JSONObject().put("proof", proofJson(checked)), PdfContentValidation.MAX_VERIFY_BYTES)
    }

    fun decodeReceipt(bytes: ByteArray, expectedProof: PortableContentProof): PdfContentReceipt = invalidInput {
        val expected = snapshotProof(expectedProof)
        val root = parse(bytes, PdfContentValidation.MAX_VERIFY_BYTES)
        exact(root, "recordId", "createdAt", "proof")
        val result = PdfContentReceipt(uuid(root, "recordId"), timestamp(root), proof(root.objectValue("proof")))
        require(result.proof == expected) { "Stored PDF proof differs from the uploaded bytes." }
        result
    }

    fun decodeRecord(bytes: ByteArray, expectedRecordId: String): PdfContentRecord = invalidInput {
        PdfContentValidation.validateUuid(expectedRecordId)
        val root = parse(bytes, PdfContentValidation.MAX_RESPONSE_BYTES)
        exact(root, "recordId", "createdAt", "content", "proof")
        val recordId = uuid(root, "recordId"); require(recordId == expectedRecordId)
        val createdAt = timestamp(root)
        val claimed = proof(root.objectValue("proof"))
        val actual = PdfContentValidation.validateContent(content(root.objectValue("content")))
        require(claimed == actual.proof) { "Stored PDF proof differs from its actual bytes." }
        PdfContentRecord(recordId, createdAt, actual.content, actual.proof)
    }

    fun decodeVerification(bytes: ByteArray, expectedRecordId: String, expectedProof: PortableContentProof): PdfContentVerification = invalidInput {
        PdfContentValidation.validateUuid(expectedRecordId)
        val expected = snapshotProof(expectedProof)
        val root = parse(bytes, PdfContentValidation.MAX_VERIFY_BYTES)
        exact(root, "recordId", "verified", "proof")
        val recordId = uuid(root, "recordId"); require(recordId == expectedRecordId && root.get("verified") == true)
        val checked = proof(root.objectValue("proof")); require(checked == expected)
        PdfContentVerification(recordId, true, checked)
    }

    private fun parse(bytes: ByteArray, limit: Int): JSONObject {
        require(bytes.size in 1..limit) { "PDF response exceeds the wire byte limit." }
        val raw = ExchangeJson.utf8(bytes)
        // Unlike ZIP extensions, the HTTP schema rejects fractional/exponent literal spellings.
        // The full legal GET profile needs fewer than 15,000 values. Bound the lexical object
        // graph as well as wire bytes, including malformed nested arrays, before org.json parses it.
        StrictExchangeJson.validate(raw, integerNumbersOnly = true,
            maxArrayEntries = PdfContentValidation.MAX_PARAGRAPHS, maxValues = 32_768)
        return JSONObject(raw)
    }

    private fun snapshotProof(proof: PortableContentProof): PortableContentProof {
        require(proof.assets.size in 1..PdfContentValidation.MAX_REFERENCES)
        return proof.copy(assets = proof.assets.map { it.copy() }).also(PdfContentValidation::validateProof)
    }

    private fun content(value: JSONObject): PdfContentDocument {
        exact(value, "version", "language", "paragraphs", "assets", "payloads")
        return PdfContentDocument(integer(value, "version", 1).toInt(), value.string("language"),
            value.objects("paragraphs", PdfContentValidation.MAX_PARAGRAPHS) {
                exact(it, "paragraphId", "text"); ExchangeParagraph(it.string("paragraphId"), it.string("text"))
            }, value.objects("assets", PdfContentValidation.MAX_REFERENCES) {
                exact(it, "path", "role", "paragraphId", "alt")
                ExchangeAssetReference(it.string("path"), it.string("role"), it.nullable("paragraphId"), it.nullable("alt"))
            }, value.objects("payloads", PdfContentValidation.MAX_PAYLOADS) {
                exact(it, "path", "mimeType", "base64"); PdfContentPayload(it.string("path"), it.string("mimeType"), it.string("base64"))
            })
    }

    private fun proof(value: JSONObject): PortableContentProof {
        exact(value, "version", "representation", "language", "paragraphHash", "originalFileByteLength", "originalFileSha256", "assets", "sha256")
        require(value.string("representation") == "PDF")
        return PortableContentProof(integer(value, "version", 1).toInt(), PortableRepresentation.PDF,
            value.string("language"), value.string("paragraphHash"), integer(value, "originalFileByteLength", PdfContentValidation.MAX_PAYLOAD_BYTES.toLong()),
            value.string("originalFileSha256"), value.objects("assets", PdfContentValidation.MAX_REFERENCES) {
                exact(it, "path", "role", "paragraphId", "alt", "mimeType", "byteLength", "sha256")
                PortableAssetProof(it.string("path"), it.string("role"), it.nullable("paragraphId"), it.nullable("alt"), it.string("mimeType"),
                    integer(it, "byteLength", PdfContentValidation.MAX_PAYLOAD_BYTES.toLong()), it.string("sha256"))
            }, value.string("sha256")).also(PdfContentValidation::validateProof)
    }

    private fun contentJson(value: PdfContentDocument) = JSONObject().put("version", value.version).put("language", value.language)
        .put("paragraphs", array(value.paragraphs) { JSONObject().put("paragraphId", it.paragraphId).put("text", it.text) })
        .put("assets", array(value.assets) { JSONObject().put("path", it.path).put("role", it.role)
            .put("paragraphId", it.paragraphId ?: JSONObject.NULL).put("alt", it.alt ?: JSONObject.NULL) })
        .put("payloads", array(value.payloads) { JSONObject().put("path", it.path).put("mimeType", it.mimeType).put("base64", it.base64) })

    private fun proofJson(value: PortableContentProof) = JSONObject().put("version", value.version).put("representation", value.representation.name)
        .put("language", value.language).put("paragraphHash", value.paragraphHash).put("originalFileByteLength", value.originalFileByteLength)
        .put("originalFileSha256", value.originalFileSha256).put("assets", array(value.assets) { JSONObject().put("path", it.path).put("role", it.role)
            .put("paragraphId", it.paragraphId ?: JSONObject.NULL).put("alt", it.alt ?: JSONObject.NULL).put("mimeType", it.mimeType)
            .put("byteLength", it.byteLength).put("sha256", it.sha256) }).put("sha256", value.sha256)

    private fun bytes(value: JSONObject, limit: Int) = value.toString().toByteArray(Charsets.UTF_8).also {
        require(it.size <= limit) { "PDF request exceeds the wire byte limit." }
    }
    private fun uuid(value: JSONObject, key: String) = value.string(key).also(PdfContentValidation::validateUuid)
    private fun exact(value: JSONObject, vararg keys: String) { require(value.keys().asSequence().toSet() == keys.toSet()) { "Unexpected PDF JSON fields." } }
    private fun JSONObject.string(key: String): String = (get(key) as? String ?: throw IllegalArgumentException("Expected a string.")).also {
        var index = 0
        while (index < it.length) {
            val char = it[index++]
            require(!char.isLowSurrogate()) { "Invalid Unicode string." }
            if (char.isHighSurrogate()) require(index < it.length && it[index++].isLowSurrogate()) { "Invalid Unicode string." }
        }
    }
    private fun JSONObject.nullable(key: String): String? = if (get(key) === JSONObject.NULL) null else string(key)
    private fun JSONObject.objectValue(key: String) = get(key) as? JSONObject ?: throw IllegalArgumentException("Expected an object.")
    private fun integer(value: JSONObject, key: String, maximum: Long): Long {
        val number = value.get(key)
        require(number is Int || number is Long) { "Expected an integer literal." }
        return (number as Number).toLong().also { require(it in 1..maximum) }
    }
    private fun <T> JSONObject.objects(key: String, maximum: Int, convert: (JSONObject) -> T): List<T> {
        val values = get(key) as? JSONArray ?: throw IllegalArgumentException("Expected an array.")
        require(values.length() <= maximum)
        return (0 until values.length()).map { index -> convert(values.get(index) as? JSONObject ?: throw IllegalArgumentException("Expected an object.")) }
    }
    private fun <T> array(values: List<T>, convert: (T) -> JSONObject) = JSONArray().also { result -> values.forEach { result.put(convert(it)) } }
    private fun timestamp(value: JSONObject): String = value.string("createdAt").also(ExchangeJson::timestamp)
    private inline fun <T> invalidInput(block: () -> T): T = try { block() }
        catch (error: Exception) { throw IllegalArgumentException("Invalid PDF content response.", error) }
}
