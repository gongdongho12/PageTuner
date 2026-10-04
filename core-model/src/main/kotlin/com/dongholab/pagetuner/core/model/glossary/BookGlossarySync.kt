package com.dongholab.pagetuner.core.model.glossary

import com.dongholab.pagetuner.core.model.library.LibraryFilter

/** Original source IDs and a concrete target language; independent of local document UUIDs. */
data class BookGlossarySyncIdentity(val providerId: String, val bookId: String, val targetLanguage: String)
enum class BookGlossarySyncKind { Character, Place, Term }

/** The full ordered account snapshot, including disabled entries and display-only metadata. */
data class BookGlossarySyncEntry(
    val id: String,
    val sourceTerm: String,
    val translatedTerm: String,
    val displayTerm: String,
    val kind: BookGlossarySyncKind,
    val caseSensitive: Boolean,
    val enabled: Boolean,
)

object BookGlossarySyncValidation {
    fun validateIdentity(identity: BookGlossarySyncIdentity) {
        require(identityText(identity.providerId, 100) && identityText(identity.bookId, 2000)) { "Invalid glossary identity." }
        require(identity.targetLanguage.length in 2..24 && identity.targetLanguage != "auto" &&
            Language.matches(identity.targetLanguage)) { "Invalid glossary target language." }
    }

    fun validateEntries(entries: List<BookGlossarySyncEntry>) {
        require(entries.size <= 500) { "A synchronized glossary can contain at most 500 entries." }
        entries.forEach(::validateEntry)
        require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate glossary entry IDs." }
    }

    fun validateEntry(entry: BookGlossarySyncEntry) {
        require(identityText(entry.id, 200)) { "Invalid glossary entry ID." }
        require(content(entry.sourceTerm, 1, 200) && LibraryFilter.trim(entry.sourceTerm).isNotEmpty() &&
            content(entry.translatedTerm, 1, 200) && LibraryFilter.trim(entry.translatedTerm).isNotEmpty() &&
            content(entry.displayTerm, 0, 200)) { "Invalid glossary entry text." }
    }

    private fun identityText(value: String, maximum: Int) = content(value, 1, maximum) &&
        LibraryFilter.trim(value) == value

    private fun content(value: String, minimum: Int, maximum: Int): Boolean {
        if (value.length !in minimum..maximum || value.any(Char::isISOControl)) return false
        var index = 0
        while (index < value.length) {
            val current = value[index++]
            if (Character.isHighSurrogate(current)) {
                if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
            } else if (Character.isLowSurrogate(current)) return false
        }
        return true
    }

    private val Language = Regex("[a-z]{2,8}(?:-[a-z0-9]{1,8})*")
}
