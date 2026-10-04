package com.dongholab.pagetuner.core.backup.exchange

import java.util.UUID
import java.security.MessageDigest

/** A bounded storage transport, separate from provenance identity and library-exchange ZIP metadata. */
data class PdfContentPayload(val path: String, val mimeType: String, val base64: String)
data class PdfContentDocument(val version: Int = 1, val language: String,
    val paragraphs: List<ExchangeParagraph>, val assets: List<ExchangeAssetReference>, val payloads: List<PdfContentPayload>)
data class PdfContentUpload(val uploadId: String, val content: PdfContentDocument)
data class ValidatedPdfContent(val content: PdfContentDocument, val assets: List<ExchangeAsset>, val proof: PortableContentProof,
    val requestFingerprint: String)
data class ValidatedPdfContentUpload(val uploadId: String, val content: PdfContentDocument,
    val assets: List<ExchangeAsset>, val proof: PortableContentProof, val requestFingerprint: String)

/** Validates actual bytes only. This does not decode PDF, attest page count, or grant any account binding. */
object PdfContentValidation {
    const val MAX_REQUEST_BYTES = 8 * 1024 * 1024
    const val MAX_VERIFY_BYTES = 2 * 1024 * 1024
    const val MAX_RESPONSE_BYTES = 12 * 1024 * 1024
    const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024
    const val MAX_PAYLOADS = 64
    const val MAX_REFERENCES = 128
    const val MAX_PARAGRAPHS = 4096
    const val MAX_METADATA_CHARACTERS = 262144
    const val MAX_BASE64_CHARACTERS = ((MAX_PAYLOAD_BYTES + 2) / 3) * 4
    private val uuidPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    private val pathPattern = Regex("assets/[0-9a-f]{64}")

    fun validateUuid(value: String) {
        require(uuidPattern.matches(value) && UUID.fromString(value).toString() == value) { "A canonical lowercase UUID is required." }
    }

    fun validate(upload: PdfContentUpload): ValidatedPdfContentUpload {
        validateUuid(upload.uploadId)
        val value = validateContent(upload.content)
        return ValidatedPdfContentUpload(upload.uploadId, value.content, value.assets, value.proof, value.requestFingerprint)
    }

    fun validateContent(input: PdfContentDocument): ValidatedPdfContent {
        require(input.version == 1)
        require(input.payloads.size in 1..MAX_PAYLOADS && input.assets.size in 1..MAX_REFERENCES)
        require(input.paragraphs.size <= MAX_PARAGRAPHS)
        val metadataSize = input.language.length.toLong() + input.paragraphs.sumOf { it.paragraphId.length.toLong() + it.text.length } +
            input.assets.sumOf { (it.paragraphId?.length?.toLong() ?: 0L) + (it.alt?.length ?: 0) }
        require(metadataSize <= MAX_METADATA_CHARACTERS) { "Content metadata exceeds the storage profile limit." }
        // Copy mutable lists before decoding/hashing; immutable strings and value objects retain exact spelling.
        val content = input.copy(paragraphs = input.paragraphs.map { it.copy() }, assets = input.assets.map { it.copy() },
            payloads = input.payloads.map { it.copy() })
        val lengths = content.payloads.map { payload ->
            require(pathPattern.matches(payload.path) && payload.mimeType in LibraryExchangeLimits.ASSET_MIME_TYPES)
            PdfContentBase64.decodedLength(payload.base64)
        }
        require(lengths.sumOf(Int::toLong) <= MAX_PAYLOAD_BYTES) { "Decoded content payloads exceed 4 MiB." }
        require(content.payloads.map { it.path }.distinct().size == content.payloads.size) { "Duplicate payload path." }
        val assets = content.payloads.map { payload ->
            val bytes = PdfContentBase64.decode(payload.base64)
            ExchangeAsset(bytes, payload.mimeType).also { require(it.path == payload.path) { "Payload bytes do not match their path." } }
        }
        val originalReference = content.assets.singleOrNull { it.role == "pdf" }
        require(originalReference != null) { "Exactly one original PDF reference is required." }
        val original = assets.singleOrNull { it.path == originalReference.path }
        require(original?.mimeType == "application/pdf") { "The original PDF payload is missing." }
        require(requireNotNull(original).bytes.size >= 5 && original.bytes.take(5).toByteArray().contentEquals("%PDF-".toByteArray(Charsets.US_ASCII))) {
            "The original payload must start with the PDF signature."
        }
        val document = ExchangeDocument("pdf-content", "", "", content.language, "local", content.paragraphs, assets = content.assets)
        val proof = PortableContentProofs.compute(PortableRepresentation.PDF, document, assets, original.bytes)
        validateProof(proof)
        return ValidatedPdfContent(content, assets, proof, fingerprint(content))
    }

    /** Full ordered request content, unlike proof which deliberately excludes payload-array order. */
    fun requestFingerprint(content: PdfContentDocument): String = validateContent(content).requestFingerprint

    private fun fingerprint(content: PdfContentDocument): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(bytes)
        }
        fun nullable(value: String?) { frame(if (value == null) "0" else "1"); if (value != null) frame(value) }
        frame("pageturner.pdf-content-upload.v1"); frame(content.version.toString()); frame(content.language)
        frame(content.paragraphs.size.toString())
        content.paragraphs.forEach { frame(it.paragraphId); frame(it.text) }
        frame(content.assets.size.toString())
        content.assets.forEach { frame(it.path); frame(it.role); nullable(it.paragraphId); nullable(it.alt) }
        frame(content.payloads.size.toString())
        content.payloads.forEach { frame(it.path); frame(it.mimeType); frame(it.base64) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The verify endpoint compares this complete validated DTO with a proof recomputed from owned storage. */
    fun validateProof(proof: PortableContentProof) {
        PortableContentProofs.validate(proof)
        require(proof.representation == PortableRepresentation.PDF)
        require(proof.assets.size in 1..MAX_REFERENCES)
        val unique = proof.assets.distinctBy { it.path }
        require(unique.size in 1..MAX_PAYLOADS && unique.sumOf { it.byteLength } <= MAX_PAYLOAD_BYTES)
        require(requireNotNull(proof.originalFileByteLength) <= MAX_PAYLOAD_BYTES)
        require(proof.language.length.toLong() + proof.assets.sumOf { (it.paragraphId?.length?.toLong() ?: 0L) + (it.alt?.length ?: 0) } <= MAX_METADATA_CHARACTERS)
    }

}

/** RFC 4648 standard base64 without API-26 java.util.Base64: shared core also runs on Android API 23. */
object PdfContentBase64 {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        require(bytes.size in 1..PdfContentValidation.MAX_PAYLOAD_BYTES)
        val encoded = CharArray((bytes.size + 2) / 3 * 4) { '=' }
        var offset = 0; var output = 0
        while (offset < bytes.size) {
            val first = bytes[offset++].toInt() and 255
            val second = if (offset < bytes.size) bytes[offset++].toInt() and 255 else -1
            val third = if (offset < bytes.size) bytes[offset++].toInt() and 255 else -1
            encoded[output++] = alphabet[first ushr 2]
            encoded[output++] = alphabet[((first and 3) shl 4) or (if (second >= 0) second ushr 4 else 0)]
            if (second >= 0) encoded[output] = alphabet[((second and 15) shl 2) or (if (third >= 0) third ushr 6 else 0)]
            output++
            if (third >= 0) encoded[output] = alphabet[third and 63]
            output++
        }
        return String(encoded)
    }

    fun decode(value: String): ByteArray {
        val size = decodedLength(value)
        val bytes = ByteArray(size)
        var output = 0
        for (index in value.indices step 4) {
            val first = digit(value[index]); val second = digit(value[index + 1])
            val third = if (value[index + 2] == '=') 0 else digit(value[index + 2])
            val fourth = if (value[index + 3] == '=') 0 else digit(value[index + 3])
            bytes[output++] = (first shl 2 or (second ushr 4)).toByte()
            if (output < size) bytes[output++] = (second shl 4 or (third ushr 2)).toByte()
            if (output < size) bytes[output++] = (third shl 6 or fourth).toByte()
        }
        return bytes
    }

    /** Check size, alphabet and zero padding bits before allocating a decoded byte array. */
    fun decodedLength(value: String): Int {
        require(value.isNotEmpty() && value.length <= PdfContentValidation.MAX_BASE64_CHARACTERS && value.length % 4 == 0) { "Invalid base64 length." }
        val padding = when { value.endsWith("==") -> 2; value.endsWith("=") -> 1; else -> 0 }
        val dataEnd = value.length - padding
        require((0 until dataEnd).all { index -> val char = value[index]
            char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char == '+' || char == '/' }) { "Invalid base64 alphabet." }
        if (padding == 2) require((digit(value[dataEnd - 1]) and 15) == 0) { "Noncanonical base64 padding bits." }
        if (padding == 1) require((digit(value[dataEnd - 1]) and 3) == 0) { "Noncanonical base64 padding bits." }
        return (value.length / 4 * 3 - padding).also { require(it in 1..PdfContentValidation.MAX_PAYLOAD_BYTES) }
    }

    private fun digit(value: Char): Int = when (value) {
        in 'A'..'Z' -> value - 'A'
        in 'a'..'z' -> value - 'a' + 26
        in '0'..'9' -> value - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> throw IllegalArgumentException("Invalid base64 alphabet.")
    }
}
