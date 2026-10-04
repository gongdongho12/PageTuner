package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.sharing.*
import java.security.MessageDigest
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.portable.PortablePageMetadata
import com.dongholab.pagetuner.portable.PortableReaderMapping

internal data class SharedSnapshot(val document: SharedDocument, val binaries: Map<String, ByteArray>) {
    val retainedBytes: Long = retainedBinaryBytes(binaries.values) + document.paragraphs.sumOf { it.text.length.toLong() * 2 }
}

private fun retainedBinaryBytes(values: Collection<ByteArray>): Long {
    val seen = java.util.IdentityHashMap<ByteArray, Boolean>()
    return values.sumOf { bytes -> if (seen.put(bytes, true) == null) bytes.size.toLong() else 0L }
}

/** Deliberately projects known fields only. Passive ZIP extensions can contain private account metadata. */
internal fun sharedProjection(id: String, source: ExchangeDocument, assets: List<ExchangeAsset>, format: String): SharedSnapshot {
    if (source.paragraphs.sumOf { it.text.length.toLong() } > LocalSharingContract.MAX_DOCUMENT_CHARACTERS || source.paragraphs.size > 50_000) throw SharingUnavailableException("document_too_large")
    require(source.paragraphs.map { it.paragraphId }.distinct().size == source.paragraphs.size)
    val byPath = assets.associateBy { it.path }
    val binaries = linkedMapOf<String, ByteArray>()
    val references = source.assets.mapIndexed { index, ref ->
        val asset = requireNotNull(byPath[ref.path]) { "Document asset is unavailable." }
        if (asset.bytes.size.toLong() > LocalSharingContract.MAX_ASSET_BYTES) throw SharingUnavailableException("asset_too_large")
        if (asset.mimeType !in LibraryExchangeLimits.ASSET_MIME_TYPES) throw SharingUnavailableException("unsupported_format")
        // byPath has already computed the validated content path once per asset. Repeated
        // illustration references must not hash the same multi-megabyte bytes again.
        val assetId = sharingId("${ref.path}:${ref.role}:${ref.paragraphId}:$index")
        binaries[assetId] = asset.bytes
        SharedAsset(assetId, asset.mimeType, asset.bytes.size.toLong(), ref.role, ref.paragraphId, ref.alt.orEmpty())
    }
    if (retainedBinaryBytes(binaries.values) > 48L * 1024 * 1024) throw SharingUnavailableException("asset_too_large")
    val digest = MessageDigest.getInstance("SHA-256")
    fun field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        digest.update(bytes)
    }
    field(source.id); field(source.bookTitle); field(format); field(source.kind); field(source.language)
    source.paragraphs.forEach { field(it.paragraphId); field(it.text) }
    source.outline.forEach { field(it.title); field(it.paragraphId) }
    references.forEach { field(it.id); field(it.mimeType); field(it.role); field(it.paragraphId.orEmpty()); field(it.alt) }
    val revision = digest.digest().joinToString("") { "%02x".format(it) }
    val paragraphIds = source.paragraphs.associate { it.paragraphId to it.text.length }
    val anchor = source.position?.takeIf { it.characterOffset in 0..(paragraphIds[it.paragraphId] ?: -1) }
    return SharedSnapshot(SharedDocument(id, source.bookTitle, format, if (source.kind == "translation") "translation" else "original",
        source.language, revision, source.paragraphs.map { SharedParagraph(it.paragraphId, it.text) },
        source.outline.filter { it.paragraphId in paragraphIds }.map { SharedOutlineItem(it.title, it.paragraphId) }, references,
        anchor?.let { SharedReadingAnchor(it.paragraphId, it.characterOffset) }), binaries)
}

internal fun sharingId(value: String): String = exchangeSha256(value.toByteArray(Charsets.UTF_8))

internal fun sharedPdfDocument(id: String, title: String, language: String, translated: Boolean, asset: ExchangeAsset,
    pageCount: Int, currentPageIndex: Int): ExchangeDocument {
    if (pageCount !in 1..2_000) throw SharingUnavailableException("document_too_large")
    val contentHash = asset.sha256
    val paragraphs = List(pageCount) { ExchangeParagraph("pdf:$contentHash:page:${it + 1}", "") }
    return ExchangeDocument(id, title, title, language, if (translated) "translation" else "original", paragraphs,
        position = ExchangeAnchor(paragraphs[currentPageIndex.coerceIn(0, pageCount - 1)].paragraphId, 0),
        assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
}

internal fun sharedPortablePdfDocument(id: String, source: ExchangeDocument, asset: ExchangeAsset, pageCount: Int): ExchangeDocument {
    if (pageCount !in 1..2_000) throw SharingUnavailableException("document_too_large")
    val mapping = PortableReaderMapping(ReaderDocument(id, source.bookTitle, DocumentFormat.PDF,
        List(pageCount) { ReaderPage(it, emptyList()) }), List(pageCount) { null })
    val currentPage = PortablePageMetadata.read(source, mapping, pdf = true).pageIndex
    val webId = "local-sha256:${asset.sha256}"
    // This exported web format explicitly binds each paragraph to one physical page of these PDF bytes.
    if (source.id == webId && source.paragraphs.size == pageCount &&
        source.paragraphs.withIndex().all { (index, paragraph) -> paragraph.paragraphId == "$webId:p$index" }) {
        return source.copy(position = currentPage?.let { ExchangeAnchor(source.paragraphs[it].paragraphId, 0) } ?: source.position,
            assets = listOf(ExchangeAssetReference(asset.path, "pdf")))
    }
    return sharedPdfDocument(id, source.bookTitle, source.language, source.kind == "translation", asset, pageCount, currentPage ?: 0)
}
