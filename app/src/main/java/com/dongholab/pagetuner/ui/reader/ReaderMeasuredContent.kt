package com.dongholab.pagetuner.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.reader.ReaderDisplayNavigation
import com.dongholab.pagetuner.reader.ReaderDisplayPosition
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkSoft
import com.dongholab.pagetuner.settings.ReaderFontFamily

/** Fixed user typography, measured display slices, and canonical source anchors are independent. */
@Composable
internal fun ReaderMeasuredContent(
    document: ReaderDocument,
    page: ReaderPage,
    position: ReaderDisplayPosition,
    pageChangeRevision: Long,
    pdfPageBitmap: Bitmap?,
    pdfFitMode: PdfFitMode,
    displayMode: DisplayMode,
    translation: PageTranslation?,
    glossaryEntries: List<BookGlossaryEntry>,
    translationDisplayMode: TranslationDisplayMode,
    fontSizeSp: Int,
    lineSpacing: Float,
    pageMarginDp: Int,
    onNavigation: (ReaderDisplayNavigation) -> Unit,
    onPositionResolved: (ReaderDisplayPosition) -> Unit,
    fontFamily: ReaderFontFamily = ReaderFontFamily.DEFAULT,
) {
    val original = remember(document, glossaryEntries) { ReaderDisplayText.original(document, glossaryEntries) }
    val translated = remember(page, translation, glossaryEntries) {
        translation?.let { ReaderTranslatedDisplayText.create(page, it, glossaryEntries) }
    }
    val originalText = remember(original) { emphasizedText(original.text, original.emphasizedRanges) }
    val translatedText = remember(translated) { translated?.let { emphasizedText(it.text, it.emphasizedRanges) } ?: AnnotatedString("") }
    val layout = readerTranslationLayout(translation != null, translationDisplayMode)
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // Measure and render both source and translation with the same selected font and weights.
    val style = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = fontFamily.toComposeFontFamily(),
        fontSize = fontSizeSp.sp,
        lineHeight = (fontSizeSp * lineSpacing).sp,
    )
    val measurer = rememberTextMeasurer(cacheSize = 32)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val margin = with(density) { pageMarginDp.dp.roundToPx() }
        val gap = if (layout.showOriginal && layout.showTranslation && pdfPageBitmap == null) with(density) { 12.dp.roundToPx() } else 0
        val outerMargin = if (layout.showOriginal && pdfPageBitmap == null) margin else 0
        val width = (constraints.maxWidth - 2 * outerMargin).coerceAtLeast(0)
        val height = (constraints.maxHeight - 2 * outerMargin - gap).coerceAtLeast(0)
        val originalHeight = if (layout.showOriginal) (height * layout.originalFraction).toInt() else 0
        val translationHeight = if (!layout.showTranslation) 0 else if (pdfPageBitmap != null && layout.showOriginal)
            (height * layout.translationFraction).toInt() else height - if (pdfPageBitmap == null) originalHeight else 0
        val translationPadding = if (layout.showOriginal) with(density) { 8.dp.roundToPx() } else margin
        val label = if (layout.showTranslationLabel) stringResource(R.string.saved_translation_title) else ""
        val labelStyle = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold)
        val translationWidth = (width - 2 * translationPadding).coerceAtLeast(0)
        val labelHeight = if (label.isNotEmpty() && translationWidth > 0) measurer.measure(label, labelStyle,
            maxLines = 1, constraints = Constraints(maxWidth = translationWidth), density = density, layoutDirection = direction).size.height +
            with(density) { 8.dp.roundToPx() } else 0
        val translationTextHeight = (translationHeight - 2 * translationPadding - labelHeight).coerceAtLeast(0)
        // Image ordering/completeness is handled separately by D2; preserve the current image layout.
        val images = page.images.take(2)
        val imageGap = with(density) { 10.dp.roundToPx() }
        val originalTextHeight = if (images.isEmpty()) originalHeight else
            ((originalHeight - imageGap * images.size).coerceAtLeast(0) * .55f / (.55f + .45f * images.size)).toInt()
        val viewportKey = "${constraints.maxWidth}:${constraints.maxHeight}:$fontSizeSp:$lineSpacing:${density.density}:${density.fontScale}:$pageMarginDp:$width:$originalTextHeight:$translationWidth:$translationTextHeight:$translationDisplayMode:${translation != null}:${images.size}:${pdfPageBitmap != null}:$style"
        fun fits(text: AnnotatedString, start: Int, end: Int, areaWidth: Int, areaHeight: Int): Boolean {
            if (start == end) return true
            if (areaWidth <= 0 || areaHeight <= 0) return false
            val measured = measurer.measure(text.subSequence(start, end), style = style,
                overflow = TextOverflow.Clip, softWrap = true, maxLines = Int.MAX_VALUE,
                constraints = Constraints(maxWidth = areaWidth, maxHeight = areaHeight), density = density, layoutDirection = direction)
            return !measured.hasVisualOverflow
        }
        val measurement = remember(original, translated, position, page, viewportKey, density, direction, measurer, style) {
            val bounded = position.fromEnd || layout.showTranslation || page.images.isNotEmpty() || document.format == DocumentFormat.PDF
            val lower = if (bounded) original.start(page.index) else {
                val precedingImage = document.pages.take(page.index).lastOrNull { it.images.isNotEmpty() }
                original.start(precedingImage?.index?.plus(1) ?: 0)
            }
            val upper = if (bounded) original.end(page.index) else {
                val nextImage = document.pages.drop(page.index + 1).firstOrNull { it.images.isNotEmpty() }
                if (nextImage == null) original.text.length else original.end(nextImage.index - 1)
            }
            val originalFits = { start: Int, end: Int -> fits(originalText, start, end, width, originalTextHeight) }
            val translatedFits = { start: Int, end: Int -> fits(translatedText, start, end, translationWidth, translationTextHeight) }
            val originalStart = if (position.fromEnd && layout.showOriginal && pdfPageBitmap == null)
                fittingTextStart(original.text, upper, lower, originalFits)
            else if (position.originalTextKey == original.text && position.originalDisplayOffset != null)
                position.originalDisplayOffset.coerceIn(lower, upper)
            else original.offset(page.index, position.characterOffset).coerceIn(lower, upper)
            val translatedStart = if (position.fromEnd && layout.showTranslation)
                fittingTextStart(translatedText.text, translatedText.length, fits = translatedFits)
            else if (position.translatedTextKey == translatedText.text) position.translatedDisplayOffset.coerceIn(0, translatedText.length)
            else translated?.offsetForSource(position.characterOffset) ?: 0
            val originalCap = position.originalEnd?.takeIf { position.viewportKey == viewportKey }?.coerceIn(originalStart, upper) ?: upper
            val translatedCap = position.translatedEnd?.takeIf { position.viewportKey == viewportKey }?.coerceIn(translatedStart, translatedText.length) ?: translatedText.length
            val originalEnd = if (layout.showOriginal && pdfPageBitmap == null) fittingTextEnd(original.text, originalStart, originalCap, originalFits) else originalStart
            val translatedEnd = if (layout.showTranslation) fittingTextEnd(translatedText.text, translatedStart, translatedCap, translatedFits) else translatedStart
            val hasOriginalRemaining = layout.showOriginal && pdfPageBitmap == null && originalEnd < upper
            val hasTranslatedRemaining = layout.showTranslation && translatedEnd < translatedText.length
            fun anchor(originalOffset: Int, translatedOffset: Int): ReaderDisplayPosition {
                val source = if (layout.showOriginal && pdfPageBitmap == null && originalOffset < upper) original.source(originalOffset)
                    else ReaderDisplayPosition(page.index, translated?.sourceOffset(translatedOffset, position.characterOffset) ?: position.characterOffset)
                return source.copy(originalDisplayOffset = originalOffset, originalTextKey = original.text,
                    translatedDisplayOffset = translatedOffset, translatedTextKey = translatedText.text)
            }
            val next = if (hasOriginalRemaining || hasTranslatedRemaining) {
                if (originalEnd > originalStart || translatedEnd > translatedStart) anchor(originalEnd, translatedEnd) else null
            } else {
                val lastPage = if (bounded) page.index else original.source(upper).pageIndex
                (lastPage + 1).takeIf { it < document.pageCount }?.let { ReaderDisplayPosition(it) }
            }
            val previous = if ((layout.showOriginal && pdfPageBitmap == null && originalStart > lower) || (layout.showTranslation && translatedStart > 0)) {
                val prevOriginal = if (layout.showOriginal && pdfPageBitmap == null) fittingTextStart(original.text, originalStart, lower, originalFits) else originalStart
                val prevTranslated = if (layout.showTranslation) fittingTextStart(translatedText.text, translatedStart, fits = translatedFits) else translatedStart
                if (prevOriginal < originalStart || prevTranslated < translatedStart) anchor(prevOriginal, prevTranslated).copy(
                    originalEnd = originalStart, translatedEnd = translatedStart, viewportKey = viewportKey) else null
            } else {
                val firstPage = if (bounded) page.index else original.source(lower).pageIndex
                (firstPage - 1).takeIf { it >= 0 }?.let { ReaderDisplayPosition(it, fromEnd = document.format != DocumentFormat.PDF || layout.showTranslation) }
            }
            MeasuredReaderPage(originalStart, originalEnd, translatedStart, translatedEnd, previous, next,
                anchor(originalStart, translatedStart).let { resolved ->
                    if (position.fromEnd) resolved.copy(originalEnd = upper, translatedEnd = translatedText.length, viewportKey = viewportKey) else resolved
                },
                layout.showOriginal && pdfPageBitmap == null && originalStart < upper && originalStart == originalEnd ||
                    layout.showTranslation && translatedStart < translatedText.length && translatedStart == translatedEnd)
        }
        SideEffect {
            if (position.fromEnd) onPositionResolved(measurement.position)
            else onNavigation(ReaderDisplayNavigation(measurement.previous, measurement.next, position, pageChangeRevision))
        }
        if (measurement.tooSmall) {
            Text(stringResource(R.string.reader_viewport_too_small), modifier = Modifier.padding(8.dp), color = EinkInk)
        } else {
            if (pdfPageBitmap != null && layout.showOriginal) Image(pdfPageBitmap.asImageBitmap(), contentDescription = null,
                modifier = Modifier.fillMaxSize().padding(10.dp).testTag("reader-pdf-original"), contentScale = if (pdfFitMode == PdfFitMode.FitPage) ContentScale.Fit else ContentScale.FillWidth)
            Column(Modifier.fillMaxSize().padding(with(density) { outerMargin.toDp() }),
                verticalArrangement = if (pdfPageBitmap != null) Arrangement.Bottom else Arrangement.spacedBy(with(density) { gap.toDp() })) {
                if (layout.showOriginal && pdfPageBitmap == null) Column(Modifier.fillMaxWidth().height(with(density) { originalHeight.toDp() }),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(originalText.subSequence(measurement.originalStart, measurement.originalEnd), style = style,
                        modifier = Modifier.fillMaxWidth().height(with(density) { originalTextHeight.toDp() }).testTag("reader-original-text"), color = EinkInk,
                        softWrap = true, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                    images.forEach { image -> EmbeddedPageImage(image, displayMode, Modifier.fillMaxWidth().weight(1f)) }
                }
                if (layout.showTranslation) Surface(Modifier.fillMaxWidth().height(with(density) { translationHeight.toDp() }).testTag("reader-translation-panel"), color = EinkSoft,
                    shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, EinkLine)) {
                    Column(Modifier.fillMaxSize().padding(with(density) { translationPadding.toDp() }), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (label.isNotEmpty()) Text(label, style = labelStyle, color = EinkMuted, maxLines = 1)
                        Text(translatedText.subSequence(measurement.translatedStart, measurement.translatedEnd), style = style,
                            modifier = Modifier.fillMaxWidth().weight(1f).testTag("reader-translated-text"), color = EinkInk, maxLines = Int.MAX_VALUE, overflow = TextOverflow.Clip)
                    }
                }
            }
            if (document.format == DocumentFormat.PDF && pdfPageBitmap == null && layout.showOriginal && page.plainText.isEmpty()) {
                Text(stringResource(R.string.viewer_pdf_rendering), Modifier.padding(pageMarginDp.dp), color = EinkMuted)
            }
        }
    }
}

private data class MeasuredReaderPage(val originalStart: Int, val originalEnd: Int, val translatedStart: Int, val translatedEnd: Int,
    val previous: ReaderDisplayPosition?, val next: ReaderDisplayPosition?, val position: ReaderDisplayPosition, val tooSmall: Boolean)

private fun emphasizedText(text: String, ranges: List<IntRange>): AnnotatedString = buildAnnotatedString {
    append(text)
    ranges.forEach { range -> if (range.first >= 0 && range.last < text.length && !range.isEmpty())
        addStyle(SpanStyle(fontWeight = FontWeight.Bold), range.first, range.last + 1) }
}
