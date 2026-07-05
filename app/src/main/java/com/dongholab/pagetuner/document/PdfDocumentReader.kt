package com.dongholab.pagetuner.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.content.PdfPageTextContent
import android.net.Uri
import android.os.Build
import com.dongholab.pagetuner.display.applyDisplayMode
import com.dongholab.pagetuner.display.DisplayMode
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

object PdfDocumentReader {
    private const val MaxRenderWidthPx = 1080
    private const val MaxRenderHeightPx = 1440
    private const val MaxSegmentChars = 520

    fun read(
        context: Context,
        uri: Uri,
        title: String,
        fallbackTitle: String,
    ): ReaderDocument {
        val pageTexts = openRenderer(context, uri) { renderer ->
            if (renderer.pageCount <= 0) {
                listOf("")
            } else {
                List(renderer.pageCount) { pageIndex ->
                    renderer.extractText(pageIndex)
                }
            }
        }
        val documentId = DocumentIds.stableId(
            title = title,
            body = "pdf:$uri:${pageTexts.size}:${pageTexts.joinToString(separator = "\n")}",
        )
        val pages = pageTexts.mapIndexed { pageIndex, text ->
            ReaderPage(
                index = pageIndex,
                segments = createPdfTextSegments(
                    documentId = documentId,
                    pageIndex = pageIndex,
                    rawText = text,
                ),
            )
        }

        return ReaderDocument(
            id = documentId,
            title = title.ifBlank { fallbackTitle },
            format = DocumentFormat.PDF,
            pages = pages,
        )
    }

    fun renderPage(
        context: Context,
        uri: Uri,
        pageIndex: Int,
        displayMode: DisplayMode,
    ): Bitmap {
        return openRenderer(context, uri) { renderer ->
            val safePageIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
            renderer.openPage(safePageIndex).use { page ->
                val scale = minOf(
                    MaxRenderWidthPx.toFloat() / page.width.toFloat(),
                    MaxRenderHeightPx.toFloat() / page.height.toFloat(),
                ).coerceAtLeast(1f)
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.applyDisplayMode(displayMode)
                bitmap
            }
        }
    }

    private fun PdfRenderer.extractText(pageIndex: Int): String {
        if (Build.VERSION.SDK_INT < 35) return ""

        return runCatching {
            openPage(pageIndex).use { page ->
                page.extractText()
            }
        }.getOrDefault("")
    }

    @Suppress("NewApi")
    private fun PdfRenderer.Page.extractText(): String {
        return textContents
            .joinToString(separator = "\n\n") { content: PdfPageTextContent -> content.text }
            .normalizePdfText()
    }

    private fun <T> openRenderer(
        context: Context,
        uri: Uri,
        block: (PdfRenderer) -> T,
    ): T {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("Unable to open PDF file.")

        descriptor.use { parcelFileDescriptor ->
            PdfRenderer(parcelFileDescriptor).use { renderer ->
                return block(renderer)
            }
        }
    }

    internal fun createPdfTextSegments(
        documentId: String,
        pageIndex: Int,
        rawText: String,
    ): List<TextSegment> {
        return rawText
            .normalizePdfText()
            .split(Regex("\\n\\s*\\n"))
            .flatMap { splitLongParagraph(it.trim()) }
            .filter { it.isNotBlank() }
            .mapIndexed { index, text ->
                TextSegment(
                    id = DocumentIds.segmentId(documentId, pageIndex, index, text),
                    pageIndex = pageIndex,
                    indexInPage = index,
                    text = text,
                )
            }
    }

    private fun splitLongParagraph(paragraph: String): List<String> {
        if (paragraph.length <= MaxSegmentChars) return listOf(paragraph)

        val sentences = paragraph.split(Regex("(?<=[.!?。！？])\\s+"))
        val chunks = mutableListOf<String>()
        var current = StringBuilder()

        for (sentence in sentences) {
            if (current.isNotEmpty() && current.length + sentence.length + 1 > MaxSegmentChars) {
                chunks += current.toString().trim()
                current = StringBuilder()
            }
            if (sentence.length > MaxSegmentChars) {
                sentence.chunked(MaxSegmentChars).forEach { chunks += it.trim() }
            } else {
                if (current.isNotEmpty()) current.append(' ')
                current.append(sentence)
            }
        }

        if (current.isNotEmpty()) chunks += current.toString().trim()
        return chunks
    }

    private fun String.normalizePdfText(): String {
        return replace("\r\n", "\n")
            .replace('\r', '\n')
            .lineSequence()
            .map { line -> line.replace(Regex("[\\t ]+"), " ").trim() }
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }
}
