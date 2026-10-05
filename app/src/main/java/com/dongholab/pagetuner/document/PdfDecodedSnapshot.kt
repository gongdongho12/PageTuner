package com.dongholab.pagetuner.document

import com.dongholab.pagetuner.core.backup.exchange.*
import java.io.Closeable
import java.io.InputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException

internal data class PdfDecodedPages(val rawPageCount: Int, val text: List<String?>)
enum class PdfTextExtraction { Complete, Partial, Unavailable }

/** A local decoder result, never a ZIP assertion, portable paragraph identity or server authorization. */
class PdfDecodedSnapshot private constructor(private var source: PdfDecoderInput?, val context: VerifiedPdfContext,
    val document: ReaderDocument, val extraction: PdfTextExtraction, private val extracted: List<Boolean>,
    private val displayHash: String) : Closeable {
    val session: String = UUID.randomUUID().toString()
    private val lock = Any()
    fun requireCurrent() = synchronized(lock) { if (source == null) throw CancellationException("PDF decoder session closed.") }
    internal fun input(): PdfDecoderInput = synchronized(lock) { requireCurrent(); requireNotNull(source) }
    fun validateDisplayed(value: ReaderDocument) {
        requireCurrent()
        require(value.format == DocumentFormat.PDF && value.pages.size == context.pageCount && fingerprint(value, extracted) == displayHash) {
            "Displayed PDF text no longer belongs to the decoded source."
        }
    }
    /** A translated companion must not silently omit pages whose text could not be extracted. */
    fun validateCompleteText(value: ReaderDocument) {
        validateDisplayed(value)
        require(extraction == PdfTextExtraction.Complete) { "PDF text extraction is incomplete; a complete translation cannot be exported." }
    }
    /** Re-read bytes only detect changes. Export always takes the privately captured decoder input. */
    fun exportOriginal(value: ReaderDocument, reread: ByteArray): ByteArray {
        validateDisplayed(value)
        val original = input()
        require(reread.size.toLong() == original.byteLength && exchangeSha256(reread) == context.originalFileSha256) { "PDF source changed after decoding." }
        return original.copyBytes().also { requireCurrent() }
    }
    override fun close() = synchronized(lock) { source = null }

    companion object {
        internal fun decode(input: PdfDecoderInput, title: String, sourceLabel: String, fallbackTitle: String,
            decoder: (PdfDecoderInput) -> PdfDecodedPages): PdfDecodedSnapshot {
            val decoded = decoder(input)
            require(decoded.rawPageCount in 1..LibraryExchangeLimits.MAX_PARAGRAPHS) { "PDF page count exceeds the reader limit or is empty." }
            require(decoded.text.size == decoded.rawPageCount)
            require(decoded.text.sumOf { it?.length?.toLong() ?: 0L } <= LibraryExchangeLimits.MAX_CHARACTERS)
            val text = decoded.text.toList()
            val context = input.verifiedContext(decoded.rawPageCount)
            // Keep the existing reader/cache ID for compatibility. It is never used as a content proof.
            val id = DocumentIds.stableId(title, "pdf:$sourceLabel:${text.size}:${text.joinToString("\n") { it.orEmpty() }}")
            var segments = 0
            val document = ReaderDocument(id, title.ifBlank { fallbackTitle }, DocumentFormat.PDF,
                text.mapIndexed { index, value ->
                    val pageSegments = PdfDocumentReader.createPdfTextSegments(id, index, value.orEmpty())
                    segments += pageSegments.size
                    require(segments <= LibraryExchangeLimits.MAX_PARAGRAPHS) { "PDF extracted paragraphs exceed the reader limit." }
                    ReaderPage(index, pageSegments)
                })
            val available = text.map { it != null }
            val extraction = when { available.all { it } -> PdfTextExtraction.Complete; available.none { it } -> PdfTextExtraction.Unavailable; else -> PdfTextExtraction.Partial }
            return PdfDecodedSnapshot(input, context, document, extraction, available, fingerprint(document, available))
        }
        private fun fingerprint(document: ReaderDocument, available: List<Boolean>): String {
            require(document.pages.size == available.size)
            val digest = MessageDigest.getInstance("SHA-256")
            fun frame(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); digest.update("${bytes.size}:".toByteArray(Charsets.US_ASCII)); digest.update(bytes) }
            frame("pageturner.android-decoded-pdf-display.v1"); frame(document.pages.size.toString())
            document.pages.forEachIndexed { index, page ->
                require(page.index == index && page.images.isEmpty() && page.imageCount == 0)
                frame(index.toString()); frame(if (available[index]) "available" else "unavailable"); frame(page.segments.size.toString())
                page.segments.forEach { segment -> frame(segment.id); frame(segment.pageIndex.toString()); frame(segment.indexInPage.toString()); frame(segment.text) }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun readPdfBytes(input: InputStream): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = input.read(buffer); if (count < 0) break
        require(output.size().toLong() + count <= LibraryExchangeLimits.ARCHIVE_BYTES) { "PDF exceeds the 32 MiB reader limit." }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
