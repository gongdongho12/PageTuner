package com.dongholab.pagetuner.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.content.PdfPageTextContent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.display.applyDisplayMode
import com.dongholab.pagetuner.display.DisplayMode
import java.io.IOException
import java.io.File
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
    ): PdfDecodedSnapshot {
        val bytes = context.contentResolver.openInputStream(uri)?.use(::readPdfBytes) ?: throw IOException("Unable to read PDF file.")
        return readInput(context, PdfDecoderInput.capture(bytes), uri.toString(), title, fallbackTitle)
    }

    internal fun readInput(context: Context, input: PdfDecoderInput, sourceLabel: String, title: String, fallbackTitle: String): PdfDecodedSnapshot =
        PdfDecodedSnapshot.decode(input, title, sourceLabel, fallbackTitle) { captured ->
            openRenderer(context, captured) { renderer ->
                val count = renderer.pageCount
                require(count in 1..LibraryExchangeLimits.MAX_PARAGRAPHS) { "PDF page count exceeds the reader limit or is empty." }
                var characters = 0L
                PdfDecodedPages(count, List(count) { index -> renderer.extractText(index).also { text ->
                    characters += text?.length ?: 0
                    require(characters <= LibraryExchangeLimits.MAX_CHARACTERS) { "PDF text exceeds the reader limit." }
                } })
            }
        }

    fun renderPage(
        context: Context,
        snapshot: PdfDecodedSnapshot,
        pageIndex: Int,
        displayMode: DisplayMode,
    ): Bitmap {
        snapshot.requireCurrent()
        require(pageIndex in 0 until snapshot.context.pageCount)
        return openRenderer(context, snapshot.input()) { renderer ->
            require(renderer.pageCount == snapshot.context.pageCount) { "PDF decoder page count changed." }
            snapshot.requireCurrent()
            renderer.openPage(pageIndex).use { page ->
                val (width, height) = renderSize(page.width, page.height)
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap.applyDisplayMode(displayMode)
                try { snapshot.requireCurrent() } catch (error: Exception) { bitmap.recycle(); throw error }
                bitmap
            }
        }
    }

    internal fun renderSize(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0) { "PDF page dimensions are invalid." }
        val scale = minOf(MaxRenderWidthPx.toDouble() / width, MaxRenderHeightPx.toDouble() / height)
        return (width * scale).roundToInt().coerceIn(1, MaxRenderWidthPx) to (height * scale).roundToInt().coerceIn(1, MaxRenderHeightPx)
    }

    private fun PdfRenderer.extractText(pageIndex: Int): String? {
        if (Build.VERSION.SDK_INT < 35) return null

        return runCatching {
            openPage(pageIndex).use { page ->
                page.extractText()
            }
        }.getOrNull()
    }

    @Suppress("NewApi")
    private fun PdfRenderer.Page.extractText(): String {
        return textContents
            .joinToString(separator = "\n\n") { content: PdfPageTextContent -> content.text }
            .normalizePdfText()
    }

    private fun <T> openRenderer(
        context: Context,
        input: PdfDecoderInput,
        block: (PdfRenderer) -> T,
    ): T {
        val directory = File(context.cacheDir, "pdf-decoder").also { require(it.isDirectory || it.mkdirs()) }
        val file = File.createTempFile("source-", ".pdf", directory)
        try {
            file.writeBytes(input.copyBytes())
            require(file.length() == input.byteLength && file.inputStream().use { exchangeSha256(readPdfBytes(it)) } == input.originalFileSha256)
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            // The decoder owns an unlinked, read-only descriptor: subsequent URI/cache replacement cannot affect it.
            val renderer = try {
                require(file.delete()) { "Unable to isolate PDF decoder input." }
                PdfRenderer(descriptor)
            } catch (error: Throwable) { descriptor.close(); throw error }
            renderer.use { return block(it) } // PdfRenderer owns and closes the descriptor exactly once.
        } finally { file.delete() }
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
                var start = 0
                while (start < sentence.length) {
                    var end = minOf(start + MaxSegmentChars, sentence.length)
                    if (end < sentence.length && sentence[end - 1].isHighSurrogate() && sentence[end].isLowSurrogate()) end--
                    chunks += sentence.substring(start, end).trim()
                    start = end
                }
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
