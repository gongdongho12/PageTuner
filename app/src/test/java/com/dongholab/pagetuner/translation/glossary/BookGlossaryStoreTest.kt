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
}
