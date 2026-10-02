package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.reader.ReaderDisplayPosition
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.GlossaryDisplayText
import com.dongholab.pagetuner.translation.glossary.GlossaryTextProcessor

internal data class ReaderDisplayPart(val pageIndex: Int, val start: Int, val sourceStart: Int, val display: GlossaryDisplayText) {
    val end get() = start + display.text.length
}

/** Display transformations are separate from the canonical text and never change segment/cache IDs. */
internal class ReaderDisplayText(val text: String, val parts: List<ReaderDisplayPart>) {
    val emphasizedRanges = parts.flatMap { part -> part.display.emphasizedRanges.map { (part.start + it.first)..(part.start + it.last) } }
    fun start(page: Int): Int = parts.firstOrNull { it.pageIndex == page }?.start ?: text.length
    fun end(page: Int): Int = parts.lastOrNull { it.pageIndex == page }?.end ?: text.length
    fun offset(page: Int, sourceOffset: Int): Int {
        val part = parts.lastOrNull { it.pageIndex == page && it.sourceStart <= sourceOffset }
            ?: parts.firstOrNull { it.pageIndex == page } ?: return text.length
        return safeTextBoundary(text, part.start + part.display.displayOffsetAt(sourceOffset - part.sourceStart))
    }
    fun source(offset: Int): ReaderDisplayPosition {
        val part = parts.lastOrNull { it.start <= offset } ?: parts.first()
        return ReaderDisplayPosition(part.pageIndex, part.sourceStart + part.display.sourceOffsetAt(offset - part.start),
            originalDisplayOffset = offset, originalTextKey = text)
    }

    companion object {
        fun original(document: ReaderDocument, glossary: List<BookGlossaryEntry>): ReaderDisplayText {
            val output = StringBuilder()
            val parts = document.pages.mapIndexed { index, page ->
                if (index > 0) output.append("\n\n")
                val display = GlossaryTextProcessor.applyOriginalDisplayAliasesWithRanges(page.plainText, glossary)
                ReaderDisplayPart(page.index, output.length, 0, display).also { output.append(display.text) }
            }
            return ReaderDisplayText(output.toString(), parts)
        }
    }
}

internal data class TranslatedDisplayPart(val start: Int, val end: Int, val sourceStart: Int, val sourceEnd: Int)
internal class ReaderTranslatedDisplayText(val text: String, val emphasizedRanges: List<IntRange>, val parts: List<TranslatedDisplayPart>) {
    fun offsetForSource(offset: Int): Int = (parts.lastOrNull { it.sourceStart <= offset } ?: parts.firstOrNull())?.start ?: 0
    fun sourceOffset(offset: Int, retainedSourceOffset: Int): Int {
        val part = parts.lastOrNull { it.start <= offset } ?: return retainedSourceOffset
        return if (retainedSourceOffset in part.sourceStart until part.sourceEnd) retainedSourceOffset else part.sourceStart
    }
    companion object {
        fun create(page: ReaderPage, translation: PageTranslation, glossary: List<BookGlossaryEntry>): ReaderTranslatedDisplayText {
            val output = StringBuilder()
            val emphasized = mutableListOf<IntRange>()
            val originals = page.segments.associate { it.id to it }
            val originalStarts = mutableMapOf<String, Int>()
            var sourceStart = 0
            page.segments.forEach { originalStarts[it.id] = sourceStart; sourceStart += it.text.length + 2 }
            val parts = translation.segments.mapIndexed { index, segment ->
                if (index > 0) output.append("\n\n")
                val start = output.length
                val display = GlossaryTextProcessor.applyTranslatedDisplayAliasesWithRanges(segment.translatedText, glossary)
                output.append(display.text)
                emphasized += display.emphasizedRanges.map { (start + it.first)..(start + it.last) }
                val source = originalStarts[segment.segmentId] ?: 0
                TranslatedDisplayPart(start, output.length, source, source + (originals[segment.segmentId]?.text?.length ?: 0))
            }
            return ReaderTranslatedDisplayText(output.toString(), emphasized, parts)
        }
    }
}
