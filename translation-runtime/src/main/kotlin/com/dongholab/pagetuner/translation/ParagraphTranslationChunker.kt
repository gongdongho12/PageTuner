package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry

internal data class ParagraphTranslationChunk(
    val id: String,
    val text: String,
    val prefix: String,
    val suffix: String,
) {
    val originalText: String get() = prefix + text + suffix
    fun restore(translated: String): String = prefix + translated.trim() + suffix
}

/** Provider-only chunks: source slices round-trip exactly, while persisted IDs remain paragraphs. */
internal object ParagraphTranslationChunker {
    fun split(paragraphId: String, text: String, maxCharacters: Int, glossary: List<BookGlossaryEntry>): List<ParagraphTranslationChunk> {
        require(maxCharacters >= 2)
        val matches = glossary.flatMap { entry ->
            val term = entry.normalizedSourceTerm
            if (!entry.enabled || term.isBlank() || entry.normalizedTranslatedTerm.isBlank()) emptyList()
            else Regex(Regex.escape(term), if (entry.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
                .findAll(text).map { it.range }.toList()
        }.sortedBy { it.first }
        val protectedRanges = mutableListOf<IntRange>()
        matches.forEach { range ->
            val previous = protectedRanges.lastOrNull()
            if (previous != null && range.first <= previous.last) {
                protectedRanges[protectedRanges.lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else protectedRanges += range
        }
        val chunks = mutableListOf<ParagraphTranslationChunk>()
        var start = 0
        while (start < text.length) {
            var end = (start + maxCharacters).coerceAtMost(text.length)
            if (end < text.length) {
                if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
                // Prefer a word/sentence boundary in the latter half of this request.
                val preferred = (end - 1 downTo start + maxCharacters / 2).firstOrNull { index ->
                    text[index].isWhitespace() || text[index] in ".!?。！？;；"
                }
                if (preferred != null) end = preferred + 1
                val crossing = protectedRanges.filter { end > it.first && end <= it.last }
                if (crossing.isNotEmpty()) {
                    val range = crossing.single()
                    end = if (range.first > start) range.first else range.last + 1
                    require(end <= start + maxCharacters) { "A glossary term exceeds the provider chunk limit." }
                }
                require(end > start) { "A glossary term exceeds the provider chunk limit." }
            }
            val raw = text.substring(start, end)
            val first = raw.indexOfFirst { !it.isWhitespace() }
            val last = raw.indexOfLast { !it.isWhitespace() }
            val source = if (first < 0) "" else raw.substring(first, last + 1)
            chunks += ParagraphTranslationChunk(
                id = if (text.length <= maxCharacters) paragraphId else "chunk:${DocumentIds.sha256("$paragraphId:${chunks.size}:$raw")}",
                text = source,
                prefix = if (first < 0) raw else raw.substring(0, first),
                suffix = if (first < 0) "" else raw.substring(last + 1),
            )
            start = end
        }
        return chunks
    }
}
