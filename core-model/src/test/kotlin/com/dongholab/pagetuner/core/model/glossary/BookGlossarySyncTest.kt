package com.dongholab.pagetuner.core.model.glossary

import org.junit.Assert.*
import org.junit.Test

class BookGlossarySyncTest {
    private val identity = BookGlossarySyncIdentity("provider:A", "Book/書籍😀", "zh-hant")
    private val entry = BookGlossarySyncEntry("original-id", " Alice ", " アリス ", " 별칭 ", BookGlossarySyncKind.Character, true, false)

    @Test fun `validation preserves original identities full metadata whitespace and ordered duplicate source entries`() {
        val entries = listOf(entry, entry.copy(id = "second", kind = BookGlossarySyncKind.Place, enabled = true),
            entry.copy(id = "third", kind = BookGlossarySyncKind.Term, displayTerm = "", caseSensitive = false))
        BookGlossarySyncValidation.validateIdentity(identity)
        BookGlossarySyncValidation.validateEntries(entries)
        assertEquals(listOf("original-id", "second", "third"), entries.map { it.id })
        assertEquals(" Alice ", entries.first().sourceTerm)
        assertFalse(entries.first().enabled)
    }

    @Test fun `rejects changed identities malformed unicode controls and noncanonical target languages`() {
        for (bad in listOf("", " a", "a\u00a0", "a\ufeff", "\ud800", "a\u0000", "a\u0085")) {
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateIdentity(identity.copy(providerId = bad)) }
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateIdentity(identity.copy(bookId = bad)) }
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntry(entry.copy(id = bad)) }
        }
        for (bad in listOf("auto", "en-US", " en", "e", "en_kr", "en--us", "en-123456789", "englishlanguage", "en-abcdefgh-abcdefgh-abcdefgh"))
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateIdentity(identity.copy(targetLanguage = bad)) }
    }

    @Test fun `UTF16 bounds count astral characters without truncating original text`() {
        BookGlossarySyncValidation.validateIdentity(identity.copy(providerId = "源".repeat(100), bookId = "😀".repeat(1000)))
        BookGlossarySyncValidation.validateEntry(entry.copy(id = "識".repeat(200), sourceTerm = "😀".repeat(100), translatedTerm = "字".repeat(200), displayTerm = " ".repeat(200)))
        for (invalid in listOf(entry.copy(id = "x".repeat(201)), entry.copy(sourceTerm = "😀".repeat(101)),
            entry.copy(translatedTerm = "x".repeat(201)), entry.copy(displayTerm = "x".repeat(201))))
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntry(invalid) }
        assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateIdentity(identity.copy(bookId = "x".repeat(2001))) }
    }

    @Test fun `blank terms invalid text duplicate IDs and overflow are rejected but empty glossary is allowed`() {
        BookGlossarySyncValidation.validateEntries(emptyList())
        for (bad in listOf("", " \u00a0\ufeff ", "a\n", "a\u007f", "a\udc00")) {
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntry(entry.copy(sourceTerm = bad)) }
            assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntry(entry.copy(translatedTerm = bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntry(entry.copy(displayTerm = "x\u0001")) }
        assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntries(listOf(entry, entry.copy(kind = BookGlossarySyncKind.Place))) }
        val maximum = List(500) { entry.copy(id = "entry-$it") }
        BookGlossarySyncValidation.validateEntries(maximum)
        assertThrows(IllegalArgumentException::class.java) { BookGlossarySyncValidation.validateEntries(maximum + entry) }
    }
}
