package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.document.TextSegment

data class ProtectedGlossaryText(
    val text: String,
    val replacements: Map<String, String>,
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
        return activeSorted(entries).fold(text) { current, entry ->
            val alias = entry.normalizedDisplayTerm.takeIf(String::isNotBlank) ?: return@fold current
            replaceTerm(current, entry.normalizedSourceTerm, alias, entry.caseSensitive)
        }
    }

    fun applyTranslatedDisplayAliases(text: String, entries: List<BookGlossaryEntry>): String {
        return activeSorted(entries).fold(text) { current, entry ->
            val alias = entry.normalizedDisplayTerm.takeIf(String::isNotBlank) ?: return@fold current
            replaceTerm(current, entry.normalizedTranslatedTerm, alias, entry.caseSensitive)
        }
    }

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

    private fun replaceTerm(
        text: String,
        source: String,
        replacement: String,
        caseSensitive: Boolean,
    ): String {
        if (source.isBlank()) return text
        val escaped = Regex.escape(source)
        val needsWordBoundary = source.firstOrNull()?.isLatinWordCharacter() == true &&
            source.lastOrNull()?.isLatinWordCharacter() == true
        val pattern = if (needsWordBoundary) "(?<![\\p{L}\\p{N}_])$escaped(?![\\p{L}\\p{N}_])" else escaped
        val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(pattern, options).replace(text, replacement)
    }

    private fun Char.isLatinWordCharacter(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || isDigit()
}
