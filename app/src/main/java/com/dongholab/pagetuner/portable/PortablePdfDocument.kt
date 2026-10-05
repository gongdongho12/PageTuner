package com.dongholab.pagetuner.portable

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.dongholab.pagetuner.core.backup.exchange.LibraryExchangeLimits
import com.dongholab.pagetuner.core.content.DocumentFileExports
import com.dongholab.pagetuner.core.content.DocumentFileFormat
import kotlinx.coroutines.CancellationException

/** A new text PDF using Android's Unicode shaping and system font fallback, never reader screenshots. */
internal object PortablePdfDocument {
    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 36
    private const val BODY_WIDTH = PAGE_WIDTH - MARGIN * 2
    private const val BODY_HEIGHT = PAGE_HEIGHT - MARGIN * 2

    fun create(bookTitle: String, chapterTitle: String, paragraphs: List<String>,
        checkActive: () -> Unit = {}, maximumPages: Int = PdfTextPagination.MAX_PAGES,
        maximumBytes: Int = LibraryExchangeLimits.ARCHIVE_BYTES): PreparedDocumentFile {
        require(maximumBytes in 1..LibraryExchangeLimits.ARCHIVE_BYTES)
        fun active() {
            if (Thread.currentThread().isInterrupted) throw CancellationException("PDF export interrupted.")
            checkActive()
        }
        active()
        // Shared validation enforces 5M input, 100K paragraphs and valid Unicode, with no truncation.
        val source = DocumentFileExports.text(bookTitle, chapterTitle, paragraphs, DocumentFileFormat.TXT)
        val bodyLength = paragraphs.sumOf { it.length.toLong() } + (paragraphs.size - 1).coerceAtLeast(0) * 2L
        val titleEnd = (source.text.length - bodyLength - 3).toInt()
        val text = SpannableString(source.text).apply {
            setSpan(RelativeSizeSpan(1.5f), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, titleEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            color = Color.BLACK
            textSize = 12f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }
        fun layout(start: Int, end: Int): StaticLayout = StaticLayout.Builder.obtain(text, start, end, paint, BODY_WIDTH)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR)
            .setLineSpacing(0f, 1.25f)
            .setIncludePad(true)
            // Builder's default SIMPLE break strategy is supported from API 23.
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .apply { if (Build.VERSION.SDK_INT >= 28) setUseLineSpacingFromFallbacks(true) }
            .build()
        fun drawnHeight(layout: StaticLayout, end: Int): Int {
            // A final LF produces one empty synthetic line; it has no glyphs to draw.
            val last = layout.lineCount - 1
            return if (layout.getLineStart(last) == end) layout.getLineTop(last) else layout.height
        }

        val document = PdfDocument()
        val output = BoundedDocumentOutputStream(maximumBytes, ::active)
        var finalPageLayout: StaticLayout? = null
        try {
            PdfTextPagination.forEachPage(source.text, maximumPages, checkActive = ::active,
                measuredEnd = { start, windowEnd ->
                    val measured = layout(start, windowEnd)
                    val lineEnds = mutableListOf<Int>()
                    for (line in 0 until measured.lineCount) {
                        if (measured.getLineBottom(line) > BODY_HEIGHT) break
                        lineEnds += measured.getLineEnd(line)
                    }
                    PdfTextPagination.finalizedEnd(start, lineEnds) { end ->
                        active()
                        val finalized = layout(start, end)
                        (drawnHeight(finalized, end) <= BODY_HEIGHT).also { fits ->
                            if (fits) finalPageLayout = finalized
                        }
                    }
                }, render = { start, end, number ->
                    val measured = requireNotNull(finalPageLayout).also { finalPageLayout = null }
                    require(measured.getLineStart(0) == start && drawnHeight(measured, end) <= BODY_HEIGHT) {
                        "The PDF layout exceeds the page margins."
                    }
                    val page = document.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, number).create())
                    try {
                        page.canvas.drawColor(Color.WHITE)
                        page.canvas.save()
                        page.canvas.translate(MARGIN.toFloat(), MARGIN.toFloat())
                        measured.draw(page.canvas)
                        page.canvas.restore()
                    } finally { document.finishPage(page) }
                    active()
                })
            active()
            document.writeTo(output)
            active()
        } finally { document.close() }
        return PreparedDocumentFile(DocumentFileExports.safeFilename(bookTitle, chapterTitle, "pdf"),
            "application/pdf", output.toByteArray())
    }
}
