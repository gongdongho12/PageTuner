package com.dongholab.pagetuner.translation.glossary

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGlossaryStoreTest {
    @Test
    fun roundTripsPerBookGlossary() {
        val directory = Files.createTempDirectory("pageturner-glossary-test").toFile()
        try {
            val store = BookGlossaryStore(directory)
            val glossary = BookGlossary("series:42", listOf(
                BookGlossaryEntry(
                    id = "entry-1",
                    sourceTerm = "Northern Palace",
                    translatedTerm = "북궁",
                    displayTerm = "빙궁",
                    kind = GlossaryTermKind.Place,
                    caseSensitive = true,
                ),
            ))

            store.save(glossary)
            val restored = store.load("series:42")

            assertEquals(glossary, restored)
            assertTrue(store.load("another-book").entries.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun mergesAndPersistsLlmCharacterAliasesForOneBook() {
        val directory = Files.createTempDirectory("pageturner-glossary-alias-test").toFile()
        try {
            val store = BookGlossaryStore(directory)

            store.mergeCharacterAliases(
                "series:42",
                listOf(CharacterAliasSuggestion("A-Pu", "아푸")),
            )

            val restored = store.load("series:42")
            assertEquals("아푸", restored.entries.single().translatedTerm)
            assertEquals("아푸", restored.entries.single().displayTerm)
            assertEquals(GlossaryTermKind.Character, restored.entries.single().kind)
            assertTrue(store.load("series:other").entries.isEmpty())
        } finally {
            directory.deleteRecursively()
        }
    }
}
