package com.dongholab.pagetuner.core.backup.exchange

/**
 * Bounded private copy of the complete source opened by a platform PDF decoder.
 * This object does not decode a PDF, prove its extracted text, or grant account permissions.
 * Decoder adapters must open [copyBytes] and pass their unmodified actual count to [verifiedContext].
 */
class PdfDecoderInput private constructor(private val bytes: ByteArray) {
    val originalFileSha256: String = exchangeSha256(bytes)
    val byteLength: Long = bytes.size.toLong()

    /** A decoder may mutate or detach its own copy without changing this source. */
    fun copyBytes(): ByteArray = bytes.copyOf()

    /** Never pass a ZIP/wire count, ReaderDocument fallback, or a reflowed display count here. */
    fun verifiedContext(rawPageCount: Int): VerifiedPdfContext {
        require(rawPageCount > 0) { "The PDF decoder must report at least one physical page." }
        return VerifiedPdfContext(originalFileSha256, rawPageCount)
    }

    companion object {
        fun capture(bytes: ByteArray, maximumBytes: Int = LibraryExchangeLimits.ARCHIVE_BYTES): PdfDecoderInput {
            require(maximumBytes in 1..LibraryExchangeLimits.ARCHIVE_BYTES)
            // Check the caller's bound before allocating the private copy.
            require(bytes.size in 1..maximumBytes) { "PDF source exceeds the decoder input limit or is empty." }
            return PdfDecoderInput(bytes.copyOf())
        }
    }
}
