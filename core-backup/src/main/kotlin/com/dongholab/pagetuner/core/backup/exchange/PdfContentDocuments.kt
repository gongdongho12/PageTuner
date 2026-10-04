package com.dongholab.pagetuner.core.backup.exchange

data class PdfContentReceipt(val recordId: String, val createdAt: String, val proof: PortableContentProof)
data class PdfContentRecord(val recordId: String, val createdAt: String, val content: PdfContentDocument, val proof: PortableContentProof)
data class PdfContentVerification(val recordId: String, val verified: Boolean, val proof: PortableContentProof)

/** A lossless projection of one selected document and its bytes from the same package snapshot. */
object PdfContentDocuments {
    fun fromPackage(value: LibraryExchangePackage, documentIndex: Int): ValidatedPdfContent {
        require(value.documents.size in 1..LibraryExchangeLimits.MAX_DOCUMENTS)
        require(documentIndex in value.documents.indices) { "The selected package document is missing." }
        val selected = value.documents[documentIndex]
        require(selected.paragraphs.size <= PdfContentValidation.MAX_PARAGRAPHS)
        require(selected.assets.size in 1..PdfContentValidation.MAX_REFERENCES)
        val paragraphs = selected.paragraphs.map { it.copy() }
        val references = selected.assets.map { it.copy() }
        val paths = references.map { it.path }.distinct() // First occurrence, independent of package payload order.
        require(paths.size <= PdfContentValidation.MAX_PAYLOADS)
        require(value.assets.size <= LibraryExchangeLimits.MAX_ENTRIES)
        require(value.assets.all { it.bytes.size in 1..LibraryExchangeLimits.ARCHIVE_BYTES })
        require(value.assets.sumOf { it.bytes.size.toLong() } <= LibraryExchangeLimits.EXPANDED_BYTES)
        val selectedAssets = linkedMapOf<String, ExchangeAsset>()
        val allPaths = mutableSetOf<String>()
        var selectedBytes = 0L
        value.assets.forEach { asset ->
            val path = asset.path
            require(allPaths.add(path)) { "Duplicate package payload path." }
            if (path in paths) {
                selectedBytes += asset.bytes.size
                require(selectedBytes <= PdfContentValidation.MAX_PAYLOAD_BYTES) { "Selected PDF payloads exceed 4 MiB." }
                val snapshot = asset.copy(bytes = asset.bytes.copyOf())
                require(snapshot.path == path) { "Package payload changed while preparing the snapshot." }
                selectedAssets[path] = snapshot
            }
        }
        val payloads = paths.map { path ->
            val asset = requireNotNull(selectedAssets[path]) { "A referenced PDF payload is missing." }
            PdfContentPayload(path, asset.mimeType, PdfContentBase64.encode(asset.bytes))
        }
        return PdfContentValidation.validateContent(PdfContentDocument(language = selected.language,
            paragraphs = paragraphs, assets = references, payloads = payloads))
    }
}
