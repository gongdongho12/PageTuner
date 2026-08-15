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

data class BookGlossary(
    val bookId: String,
    val entries: List<BookGlossaryEntry> = emptyList(),
) {
    val activeEntries: List<BookGlossaryEntry>
        get() = entries.filter {
            it.enabled && it.normalizedSourceTerm.isNotBlank() && it.normalizedTranslatedTerm.isNotBlank()
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
