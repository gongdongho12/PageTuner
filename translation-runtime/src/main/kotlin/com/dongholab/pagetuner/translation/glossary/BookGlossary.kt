package com.dongholab.pagetuner.translation.glossary

import com.dongholab.pagetuner.document.DocumentIds

enum class GlossaryTermKind {
    Character,
    Place,
    Term,
}

data class BookGlossaryEntry(
    val id: String,
    val sourceTerm: String,
    val translatedTerm: String,
    val displayTerm: String = "",
    val kind: GlossaryTermKind = GlossaryTermKind.Character,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
) {
    val normalizedSourceTerm: String get() = sourceTerm.trim()
    val normalizedTranslatedTerm: String get() = translatedTerm.trim()
    val normalizedDisplayTerm: String get() = displayTerm.trim()
}

/** A character spelling proposed by an LLM while translating one book. */
data class CharacterAliasSuggestion(
    val sourceTerm: String,
    val alias: String,
)

data class BookGlossary(
    val bookId: String,
    val entries: List<BookGlossaryEntry> = emptyList(),
) {
    val activeEntries: List<BookGlossaryEntry>
        get() = entries.filter {
            it.enabled && it.normalizedSourceTerm.isNotBlank() && it.normalizedTranslatedTerm.isNotBlank()
        }

    val characterAliases: List<CharacterAliasSuggestion>
        get() = activeEntries
            .filter { it.kind == GlossaryTermKind.Character }
            .map {
                CharacterAliasSuggestion(
                    sourceTerm = it.normalizedSourceTerm,
                    alias = it.normalizedTranslatedTerm,
                )
            }

    /** Changes only when the translation contract changes; display aliases remain cache-free. */
    val translationFingerprint: String
        get() = DocumentIds.sha256(
            activeEntries
                .sortedBy(BookGlossaryEntry::id)
                .joinToString("\n") {
                    listOf(
                        it.id,
                        it.normalizedSourceTerm,
                        it.normalizedTranslatedTerm,
                        it.caseSensitive.toString(),
                    ).joinToString("\u001f")
                },
        ).take(16)
}

object BookGlossaryMerger {
    fun mergeEntries(
        glossary: BookGlossary,
        incoming: List<BookGlossaryEntry>,
    ): BookGlossary {
        val existingSources = glossary.entries
            .mapTo(mutableSetOf()) { it.normalizedSourceTerm.lowercase() }
        val existingIds = glossary.entries.mapTo(mutableSetOf(), BookGlossaryEntry::id)
        val additions = incoming.mapNotNull { entry ->
            val source = entry.normalizedSourceTerm
            val translated = entry.normalizedTranslatedTerm
            if (source.isBlank() || translated.isBlank() || !existingSources.add(source.lowercase())) {
                return@mapNotNull null
            }
            val safeId = entry.id.takeIf { it.isNotBlank() && existingIds.add(it) }
                ?: "shared-${DocumentIds.sha256(source.lowercase()).take(16)}"
            entry.copy(
                id = safeId,
                sourceTerm = source,
                translatedTerm = translated,
                displayTerm = entry.normalizedDisplayTerm,
            )
        }
        if (additions.isEmpty()) return glossary
        return glossary.copy(
            entries = (glossary.entries + additions)
                .sortedWith(compareBy<BookGlossaryEntry> { it.kind }.thenBy { it.sourceTerm.lowercase() }),
        )
    }

    fun mergeCharacterAliases(
        glossary: BookGlossary,
        suggestions: List<CharacterAliasSuggestion>,
    ): BookGlossary {
        val additions = suggestions.mapNotNull { suggestion ->
            val source = suggestion.sourceTerm.trim()
            val alias = suggestion.alias.trim()
            if (source.isBlank() || alias.isBlank()) return@mapNotNull null
            BookGlossaryEntry(
                id = "llm-${DocumentIds.sha256(source.lowercase()).take(16)}",
                sourceTerm = source,
                translatedTerm = alias,
                displayTerm = alias,
                kind = GlossaryTermKind.Character,
            )
        }
        return mergeEntries(glossary, additions)
    }
}
