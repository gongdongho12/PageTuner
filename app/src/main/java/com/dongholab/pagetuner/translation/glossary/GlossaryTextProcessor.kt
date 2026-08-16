package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.document.TextSegment

data class ProtectedGlossaryText(
    val text: String,
    val replacements: Map<String, String>,
)

data class GlossaryDisplayText(
    val text: String,
    val emphasizedRanges: List<IntRange> = emptyList(),
)

/** Pure shared pre/post processor used by every translation provider. */
object GlossaryTextProcessor {
    private const val TokenPrefix = "PTGLOSSARY"

    fun protect(text: String, entries: List<BookGlossaryEntry>): ProtectedGlossaryText {
        var protectedText = text
        val replacements = linkedMapOf<String, String>()
        activeSorted(entries).forEachIndexed { index, entry ->
            val token = "${TokenPrefix}${index.toString().padStart(4, '0')}TOKEN"
            val replaced = replaceTerm(
                text = protectedText,
                source = entry.normalizedSourceTerm,
                replacement = token,
                caseSensitive = entry.caseSensitive,
            )
            if (replaced != protectedText) {
                protectedText = replaced
                replacements[token] = entry.normalizedTranslatedTerm
            }
        }
        return ProtectedGlossaryText(protectedText, replacements)
    }

    fun restore(text: String, replacements: Map<String, String>): String {
        return replacements.entries.fold(text) { current, (token, replacement) ->
            current.replace(token, replacement, ignoreCase = true)
        }
    }

    fun applyOriginalDisplayAliases(text: String, entries: List<BookGlossaryEntry>): String {
        return applyOriginalDisplayAliasesWithRanges(text, entries).text
    }

    fun applyTranslatedDisplayAliases(text: String, entries: List<BookGlossaryEntry>): String {
        return applyTranslatedDisplayAliasesWithRanges(text, entries).text
    }

    fun applyOriginalDisplayAliasesWithRanges(
        text: String,
        entries: List<BookGlossaryEntry>,
    ): GlossaryDisplayText = applyDisplayAliases(
        text = text,
        entries = entries,
        sourceTerm = BookGlossaryEntry::normalizedSourceTerm,
        useTranslatedCharacterFallback = false,
    )

    fun applyTranslatedDisplayAliasesWithRanges(
        text: String,
        entries: List<BookGlossaryEntry>,
    ): GlossaryDisplayText = applyDisplayAliases(
        text = text,
        entries = entries,
        sourceTerm = BookGlossaryEntry::normalizedTranslatedTerm,
        useTranslatedCharacterFallback = true,
    )

    internal fun protectSegments(
        segments: List<TextSegment>,
        entries: List<BookGlossaryEntry>,
    ): Pair<List<TextSegment>, Map<String, Map<String, String>>> {
        val replacements = linkedMapOf<String, Map<String, String>>()
        val protected = segments.map { segment ->
            val result = protect(segment.text, entries)
            replacements[segment.id] = result.replacements
            segment.copy(text = result.text)
        }
        return protected to replacements
    }

    private fun activeSorted(entries: List<BookGlossaryEntry>): List<BookGlossaryEntry> {
        return entries
            .filter { it.enabled && it.normalizedSourceTerm.isNotBlank() && it.normalizedTranslatedTerm.isNotBlank() }
            .sortedWith(compareByDescending<BookGlossaryEntry> { it.normalizedSourceTerm.length }.thenBy { it.id })
    }

    private data class DisplayMatch(
        val start: Int,
        val endExclusive: Int,
        val replacement: String,
        val emphasize: Boolean,
    )

    private fun applyDisplayAliases(
        text: String,
        entries: List<BookGlossaryEntry>,
        sourceTerm: (BookGlossaryEntry) -> String,
        useTranslatedCharacterFallback: Boolean,
    ): GlossaryDisplayText {
        val candidates = activeSorted(entries).flatMap { entry ->
            val source = sourceTerm(entry)
            val replacement = entry.normalizedDisplayTerm.takeIf(String::isNotBlank)
                ?: if (useTranslatedCharacterFallback && entry.kind == GlossaryTermKind.Character) {
                    entry.normalizedTranslatedTerm
                } else {
                    return@flatMap emptyList()
                }
            if (source.isBlank() || replacement.isBlank()) return@flatMap emptyList()
            termRegex(source, entry.caseSensitive).findAll(text).map { match ->
                DisplayMatch(
                    start = match.range.first,
                    endExclusive = match.range.last + 1,
                    replacement = replacement,
                    emphasize = entry.kind == GlossaryTermKind.Character,
                )
            }.toList()
        }.sortedWith(compareBy<DisplayMatch> { it.start }.thenByDescending { it.endExclusive - it.start })

        if (candidates.isEmpty()) return GlossaryDisplayText(text)
        val selected = mutableListOf<DisplayMatch>()
        var occupiedUntil = 0
        candidates.forEach { candidate ->
            if (candidate.start >= occupiedUntil) {
                selected += candidate
                occupiedUntil = candidate.endExclusive
            }
        }

        val output = StringBuilder(text.length)
        val emphasized = mutableListOf<IntRange>()
        var sourceOffset = 0
        selected.forEach { match ->
            output.append(text, sourceOffset, match.start)
            val replacementStart = output.length
            output.append(match.replacement)
            if (match.emphasize && match.replacement.isNotEmpty()) {
                emphasized += replacementStart until output.length
            }
            sourceOffset = match.endExclusive
        }
        output.append(text, sourceOffset, text.length)
        return GlossaryDisplayText(output.toString(), emphasized)
    }

    private fun replaceTerm(
        text: String,
        source: String,
        replacement: String,
        caseSensitive: Boolean,
    ): String {
        if (source.isBlank()) return text
        return termRegex(source, caseSensitive).replace(text, replacement)
    }

    private fun termRegex(source: String, caseSensitive: Boolean): Regex {
        val escaped = Regex.escape(source)
        val needsWordBoundary = source.firstOrNull()?.isLatinWordCharacter() == true &&
            source.lastOrNull()?.isLatinWordCharacter() == true
        val pattern = if (needsWordBoundary) "(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])" else escaped
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options)
    }

    private fun Char.isLatinWordCharacter(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || isDigit()
}
