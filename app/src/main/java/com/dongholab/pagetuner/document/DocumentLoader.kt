package com.dongholab.pagetuner.document

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.dongholab.pagetuner.R

data class LoadedReaderDocument(
    val document: ReaderDocument,
    val pdfSourceUri: String? = null,
)

fun Context.sampleDocument(): ReaderDocument {
    return PlainTextDocumentParser.parse(
        title = getString(R.string.sample_document_title),
        rawText = getString(R.string.sample_document_body),
        fallbackTitle = getString(R.string.document_untitled),
    )
}

fun Context.readReaderDocument(
    uri: Uri,
    preferredTitle: String? = null,
    preferredFormat: DocumentFormat? = null,
): LoadedReaderDocument {
    val title = preferredTitle?.takeIf { it.isNotBlank() } ?: readerDocumentDisplayName(uri)
    val format = preferredFormat ?: detectReaderDocumentFormat(uri, title)

    return when (format) {
        DocumentFormat.PDF -> LoadedReaderDocument(
            document = PdfDocumentReader.read(
                context = this,
                uri = uri,
                title = title,
                fallbackTitle = getString(R.string.document_untitled),
            ),
            pdfSourceUri = uri.toString(),
        )
        DocumentFormat.EPUB -> {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("Unable to open EPUB file.")
            LoadedReaderDocument(
                document = EpubDocumentReader.parse(
                    title = title,
                    bytes = bytes,
                    fallbackTitle = getString(R.string.document_untitled),
                ),
            )
        }
        DocumentFormat.MARKDOWN,
        DocumentFormat.TEXT -> {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            LoadedReaderDocument(
                document = PlainTextDocumentParser.parse(
                    title = title,
                    rawText = text,
                    format = format,
                    fallbackTitle = getString(R.string.document_untitled),
                ),
            )
        }
    }
}

fun Context.detectReaderDocumentFormat(uri: Uri, title: String): DocumentFormat {
    val mimeType = contentResolver.getType(uri).orEmpty().lowercase()
    val lowerTitle = title.lowercase()

    return when {
        mimeType == "application/pdf" || lowerTitle.endsWith(".pdf") -> DocumentFormat.PDF
        mimeType == "application/epub+zip" || lowerTitle.endsWith(".epub") -> DocumentFormat.EPUB
        lowerTitle.endsWith(".md") || lowerTitle.endsWith(".markdown") -> DocumentFormat.MARKDOWN
        mimeType.startsWith("text/") || lowerTitle.endsWith(".txt") -> DocumentFormat.TEXT
        lowerTitle.hasUnsupportedDocumentExtension() || mimeType in unsupportedDocumentMimeTypes ->
            throw UnsupportedReaderDocumentException(title)
        else -> DocumentFormat.TEXT
    }
}

private fun String.hasUnsupportedDocumentExtension(): Boolean {
    return unsupportedDocumentExtensions.any { extension -> endsWith(extension) }
}

private val unsupportedDocumentExtensions = setOf(
    ".azw",
    ".azw3",
    ".cb7",
    ".cbr",
    ".cbz",
    ".doc",
    ".docx",
    ".fb2",
    ".mobi",
    ".odt",
    ".pages",
    ".ppt",
    ".pptx",
    ".rtf",
    ".xls",
    ".xlsx",
)

private val unsupportedDocumentMimeTypes = setOf(
    "application/msword",
    "application/rtf",
    "application/vnd.ms-excel",
    "application/vnd.ms-powerpoint",
    "application/vnd.oasis.opendocument.text",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
)

fun Context.readerDocumentDisplayName(uri: Uri): String {
    val queried = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    return queried ?: uri.lastPathSegment ?: getString(R.string.document_imported)
}
