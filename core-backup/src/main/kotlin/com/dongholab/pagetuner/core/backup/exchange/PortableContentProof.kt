package com.dongholab.pagetuner.core.backup.exchange

import java.security.MessageDigest

enum class PortableRepresentation { TEXT, PDF, EPUB }

data class PortableAssetProof(
    val path: String, val role: String, val paragraphId: String?, val alt: String?,
    val mimeType: String, val byteLength: Long, val sha256: String,
)

/** Exact content snapshot only. This is neither source provenance nor account authorization. */
data class PortableContentProof(
    val version: Int = 1,
    val representation: PortableRepresentation,
    val language: String,
    val paragraphHash: String,
    val originalFileByteLength: Long?,
    val originalFileSha256: String?,
    val assets: List<PortableAssetProof>,
    val sha256: String,
)

/** Page count obtained by independently parsing the original file with this exact full-file hash. */
data class VerifiedPdfContext(val originalFileSha256: String, val pageCount: Int)

sealed interface PortableProofAnchor {
    data class Text(val paragraphId: String, val characterOffset: Int) : PortableProofAnchor
    data class Pdf(val originalFileSha256: String, val pageIndex: Int) : PortableProofAnchor
}

object PortableContentProofs {
    const val MAX_REFERENCES = 512
    const val MAX_ASSETS = 511
    const val MAX_ALT_CHARACTERS = 2000
    private val hashPattern = Regex("[a-f0-9]{64}")
    private val languagePattern = Regex("[A-Za-z][A-Za-z0-9-]{0,34}")
    private val imageMimeTypes = setOf("image/png", "image/jpeg", "image/gif", "image/webp")

    /** Operates on one document's exact asset set. All supplied mutable byte arrays are copied before hashing. */
    fun compute(representation: PortableRepresentation, document: ExchangeDocument,
        assets: List<ExchangeAsset> = emptyList(), originalFile: ByteArray? = null): PortableContentProof {
        require(assets.size <= MAX_ASSETS && document.assets.size <= MAX_REFERENCES)
        require(originalFile == null || originalFile.size in 1..LibraryExchangeLimits.ARCHIVE_BYTES)
        require(assets.all { it.bytes.size in 1..LibraryExchangeLimits.ARCHIVE_BYTES })
        require((originalFile?.size?.toLong() ?: 0L) + assets.sumOf { it.bytes.size.toLong() } <= LibraryExchangeLimits.EXPANDED_BYTES)
        val original = originalFile?.copyOf()
        val payloads = assets.map { ExchangeAsset(it.bytes.copyOf(), it.mimeType) }
        val paragraphs = document.paragraphs.toList()
        val references = document.assets.toList()
        require(languagePattern.matches(document.language))
        val paragraphHash = paragraphHash(representation, paragraphs)
        require(representation == PortableRepresentation.TEXT || original != null)
        val originalHash = original?.let(::exchangeSha256)
        val originalLength = original?.size?.toLong()
        val byPath = payloads.map { asset ->
            val hash = exchangeSha256(asset.bytes)
            PortableAssetProof("assets/$hash", "", null, null, asset.mimeType, asset.bytes.size.toLong(), hash)
        }.associateBy { it.path }
        require(byPath.size == payloads.size) { "Duplicate asset payload paths." }
        require(references.map { it.path }.toSet() == byPath.keys) { "Missing or orphan asset payload." }
        require(representation != PortableRepresentation.TEXT || references.isEmpty())
        val paragraphIds = paragraphs.map { it.paragraphId }.toSet()
        val ordered = references.map { reference ->
            val asset = requireNotNull(byPath[reference.path])
            require(asset.mimeType in LibraryExchangeLimits.ASSET_MIME_TYPES)
            reference.paragraphId?.let { require(it in paragraphIds) { "Orphan paragraph binding." } }
            reference.alt?.let { text(it, MAX_ALT_CHARACTERS) }
            when (reference.role) {
                "pdf" -> {
                    require(representation == PortableRepresentation.PDF && asset.mimeType == "application/pdf")
                    require(reference.paragraphId == null && asset.sha256 == originalHash)
                }
                "image" -> require(representation != PortableRepresentation.TEXT && asset.mimeType in imageMimeTypes)
                else -> require(false) { "Unsupported asset role." }
            }
            PortableAssetProof(reference.path, reference.role, reference.paragraphId, reference.alt,
                asset.mimeType, asset.byteLength, asset.sha256)
        }
        require(representation != PortableRepresentation.PDF || ordered.count { it.role == "pdf" } == 1)
        val proof = PortableContentProof(representation = representation, language = document.language, paragraphHash = paragraphHash,
            originalFileByteLength = originalLength, originalFileSha256 = originalHash, assets = ordered, sha256 = "")
        return proof.copy(sha256 = digest(proof))
    }

    /** Metadata integrity only; actual bytes must have been checked by compute or the caller's trusted storage. */
    fun validate(proof: PortableContentProof) {
        require(proof.version == 1 && languagePattern.matches(proof.language))
        require(hashPattern.matches(proof.paragraphHash) && hashPattern.matches(proof.sha256))
        require((proof.originalFileByteLength == null) == (proof.originalFileSha256 == null))
        proof.originalFileByteLength?.let { require(it in 1..LibraryExchangeLimits.ARCHIVE_BYTES.toLong()) }
        proof.originalFileSha256?.let { require(hashPattern.matches(it)) }
        require(proof.representation == PortableRepresentation.TEXT || proof.originalFileSha256 != null)
        require(proof.assets.size <= MAX_REFERENCES)
        require(proof.representation != PortableRepresentation.TEXT || proof.assets.isEmpty())
        val unique = linkedMapOf<String, PortableAssetProof>()
        proof.assets.forEach { asset ->
            require(hashPattern.matches(asset.sha256) && asset.path == "assets/${asset.sha256}")
            require(asset.byteLength in 1..LibraryExchangeLimits.ARCHIVE_BYTES.toLong())
            require(asset.mimeType in LibraryExchangeLimits.ASSET_MIME_TYPES)
            asset.paragraphId?.let { require(it.isNotBlank() && it.length <= 500); text(it, 500) }
            asset.alt?.let { text(it, MAX_ALT_CHARACTERS) }
            when (asset.role) {
                "pdf" -> require(proof.representation == PortableRepresentation.PDF && asset.mimeType == "application/pdf" &&
                    asset.paragraphId == null && asset.sha256 == proof.originalFileSha256 && asset.byteLength == proof.originalFileByteLength)
                "image" -> require(proof.representation != PortableRepresentation.TEXT && asset.mimeType in imageMimeTypes)
                else -> require(false) { "Unsupported asset role." }
            }
            val previous = unique.put(asset.path, asset)
            require(previous == null || previous.mimeType == asset.mimeType && previous.byteLength == asset.byteLength)
        }
        require(unique.size <= MAX_ASSETS)
        require((proof.originalFileByteLength ?: 0L) + unique.values.sumOf { it.byteLength } <= LibraryExchangeLimits.EXPANDED_BYTES)
        require(proof.representation != PortableRepresentation.PDF || proof.assets.count { it.role == "pdf" } == 1)
        require(digest(proof) == proof.sha256) { "Content proof digest mismatch." }
    }

    private fun digest(proof: PortableContentProof): String {
        val digest = FramedDigest()
        digest.frame("pageturner.content-proof.v1"); digest.frame(proof.version.toString()); digest.frame(proof.representation.name)
        digest.frame(proof.language); digest.frame(proof.paragraphHash)
        digest.frame(if (proof.originalFileSha256 == null) "0" else "1")
        if (proof.originalFileSha256 != null) { digest.frame(proof.originalFileByteLength.toString()); digest.frame(proof.originalFileSha256) }
        digest.frame(proof.assets.size.toString())
        proof.assets.forEach { asset ->
            digest.frame(asset.path); digest.frame(asset.role)
            digest.nullable(asset.paragraphId); digest.nullable(asset.alt)
            digest.frame(asset.mimeType); digest.frame(asset.byteLength.toString()); digest.frame(asset.sha256)
        }
        return digest.finish()
    }

    /** Text offsets never become physical pages, including when extracted text belongs to a PDF. */
    fun validateTextAnchor(proof: PortableContentProof, paragraphs: List<ExchangeParagraph>, anchor: PortableProofAnchor.Text) {
        validate(proof)
        require(paragraphHash(proof.representation, paragraphs) == proof.paragraphHash)
        val ids = paragraphs.map { it.paragraphId }.toSet()
        require(proof.assets.all { it.paragraphId == null || it.paragraphId in ids })
        val paragraph = requireNotNull(paragraphs.singleOrNull { it.paragraphId == anchor.paragraphId })
        val offset = anchor.characterOffset
        require(offset in 0..paragraph.text.length)
        require(!(offset in 1 until paragraph.text.length && paragraph.text[offset - 1].isHighSurrogate() && paragraph.text[offset].isLowSurrogate()))
    }

    /** The caller must independently verify pageCount by opening the same original bytes in a PDF parser. */
    fun validatePdfAnchor(proof: PortableContentProof, anchor: PortableProofAnchor.Pdf, verified: VerifiedPdfContext) {
        validate(proof)
        require(proof.representation == PortableRepresentation.PDF)
        require(proof.originalFileByteLength != null && proof.originalFileByteLength > 0)
        require(proof.originalFileSha256 != null && anchor.originalFileSha256 == proof.originalFileSha256)
        require(verified.originalFileSha256 == proof.originalFileSha256)
        require(verified.pageCount > 0 && anchor.pageIndex in 0 until verified.pageCount)
    }

    private fun paragraphHash(representation: PortableRepresentation, paragraphs: List<ExchangeParagraph>): String {
        if (paragraphs.isNotEmpty()) return DocumentIdentities.paragraphHash(paragraphs)
        require(representation == PortableRepresentation.PDF)
        return FramedDigest().apply { frame("pageturner.document-paragraphs.v1"); frame("0") }.finish()
    }

    private fun text(value: String, maximum: Int) {
        require(value.length <= maximum)
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            require(!char.isLowSurrogate())
            if (char.isHighSurrogate()) require(index < value.length && value[index++].isLowSurrogate())
        }
    }

    private class FramedDigest {
        private val digest = MessageDigest.getInstance("SHA-256")
        fun frame(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII)); digest.update(':'.code.toByte()); digest.update(bytes)
        }
        fun nullable(value: String?) { frame(if (value == null) "0" else "1"); if (value != null) frame(value) }
        fun finish() = digest.digest().joinToString("") { "%02x".format(it) }
    }
}
