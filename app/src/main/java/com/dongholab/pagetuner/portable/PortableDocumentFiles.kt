package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.content.DocumentFileExports
import com.dongholab.pagetuner.core.content.DocumentFileFormat
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.EpubDocumentReader
import com.dongholab.pagetuner.library.LocalBookReadSnapshot

/** PDF remains the original asset copy; PDF_DOCUMENT creates a new document from complete text. */
enum class PortableDocumentFileFormat { TXT, MARKDOWN, EPUB, PDF_DOCUMENT, PDF }
data class PreparedDocumentFile(val filename: String, val mimeType: String, val bytes: ByteArray)

/** Disposable UI selection, never persisted as an export grant across process recreation. */
internal class PortableFileExportSelection {
    private var key: String? = null
    private var generation = 0L
    @Synchronized fun select(value: String?) { key = value; generation++ }
    @Synchronized fun guard(expectedKey: String): () -> Unit {
        val expectedGeneration = generation
        return { synchronized(this) {
            if (key != expectedKey || generation != expectedGeneration) throw PortableExportExpired()
        } }
    }
}

/** General files project only readable content, never account, binding or outbox metadata. */
internal object PortableDocumentFiles {
    fun fromArchive(value: LibraryExchangePackage, index: Int, format: PortableDocumentFileFormat,
        checkActive: () -> Unit = {}): PreparedDocumentFile {
        checkActive()
        val document = value.documents[index]
        val pdf = document.assets.filter { it.role == "pdf" }
        if (format == PortableDocumentFileFormat.PDF) {
            val reference = pdf.single()
            val asset = value.assets.single { it.path == reference.path }
            val bytes = asset.bytes.copyOf()
            require(asset.mimeType == "application/pdf" && reference.path == "assets/${exchangeSha256(bytes)}") {
                "The original PDF payload could not be verified."
            }
            return pdf(document.bookTitle, document.chapterTitle, bytes)
        }
        require(pdf.isEmpty()) { "PDF text export requires verified complete extraction; export the original PDF instead." }
        return text(document.bookTitle, document.chapterTitle, document.paragraphs.map { it.text }, format,
            document.language, checkActive)
    }

    fun fromNative(snapshot: LocalBookReadSnapshot, format: PortableDocumentFileFormat,
        checkActive: () -> Unit = {}): PreparedDocumentFile {
        checkActive()
        require(snapshot.book.format != DocumentFormat.PDF && format != PortableDocumentFileFormat.PDF) {
            "Original PDF export requires its decoder snapshot."
        }
        require(exchangeSha256(snapshot.bytes) == snapshot.book.contentHash) { "The saved document changed." }
        val title = snapshot.book.currentChapterTitle ?: snapshot.book.title
        val paragraphs = if (snapshot.book.format == DocumentFormat.EPUB)
            EpubDocumentReader.extractTextForExport(snapshot.bytes)
        else {
            val text = snapshot.bytes.decodeToString(throwOnInvalidSequence = true)
            require(text.length <= LibraryExchangeLimits.MAX_CHARACTERS) { "The document text exceeds the export limit." }
            // Stored text is already the source: reader pagination trims/splits it and is not an export format.
            listOf(text)
        }
        return text(snapshot.book.title, title, paragraphs, format, snapshot.book.contentLanguage, checkActive)
    }

    fun pdf(bookTitle: String, chapterTitle: String, bytes: ByteArray): PreparedDocumentFile {
        require(bytes.isNotEmpty() && bytes.size <= LibraryExchangeLimits.ARCHIVE_BYTES)
        return PreparedDocumentFile(DocumentFileExports.safeFilename(bookTitle, chapterTitle, "pdf"), "application/pdf", bytes.copyOf())
    }

    private fun text(bookTitle: String, chapterTitle: String, paragraphs: List<String>, format: PortableDocumentFileFormat,
        language: String?, checkActive: () -> Unit): PreparedDocumentFile {
        checkActive()
        val type = when (format) {
            PortableDocumentFileFormat.TXT -> DocumentFileFormat.TXT
            PortableDocumentFileFormat.MARKDOWN -> DocumentFileFormat.MARKDOWN
            PortableDocumentFileFormat.EPUB -> return PortableEpubDocument.create(bookTitle, chapterTitle, language, paragraphs, checkActive)
            PortableDocumentFileFormat.PDF_DOCUMENT -> return PortablePdfDocument.create(bookTitle, chapterTitle, paragraphs, checkActive)
            PortableDocumentFileFormat.PDF -> error("PDF is not a text format.")
        }
        val value = DocumentFileExports.text(bookTitle, chapterTitle, paragraphs, type)
        return PreparedDocumentFile(value.filename, value.mimeType, value.text.toByteArray(Charsets.UTF_8))
    }
}
