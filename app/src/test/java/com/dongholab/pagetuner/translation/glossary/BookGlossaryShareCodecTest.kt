package com.dongholab.pagetuner.translation.glossary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookGlossaryShareCodecTest {
    @Test
    fun roundTripsVersionedBookDictionary() {
        val original = BookGlossary(
            bookId = "wtr-lab:series-42",
            entries = listOf(
                BookGlossaryEntry("apu", "A-Pu", "아푸", "아푸", GlossaryTermKind.Character),
                BookGlossaryEntry("sect", "Azure Sect", "창천문", "푸른 문파", GlossaryTermKind.Term),
            ),
        )

        val shared = BookGlossaryShareCodec.decode(
            BookGlossaryShareCodec.encode(original, "My Novel"),
        )

        assertEquals("wtr-lab:series-42", shared.sourceBookId)
        assertEquals("My Novel", shared.bookTitle)
        assertEquals(listOf("A-Pu", "Azure Sect"), shared.entries.map { it.sourceTerm })
        assertEquals("아푸", shared.entries.first().displayTerm)
    }

    @Test
    fun sharedEntriesMergeWithoutOverwritingReadersExistingChoice() {
        val current = BookGlossary(
            "local-book",
            listOf(BookGlossaryEntry("manual", "A-Pu", "에이푸", "애칭")),
        )
        val incoming = BookGlossaryShareCodec.decode(
            BookGlossaryShareCodec.encode(
                BookGlossary(
                    "other-user-book",
                    listOf(
                        BookGlossaryEntry("remote-apu", "A-Pu", "아푸"),
                        BookGlossaryEntry("remote-lin", "Lin Mei", "린메이"),
                    ),
                ),
                "Same Novel",
            ),
        )

        val merged = BookGlossaryMerger.mergeEntries(current, incoming.entries)

        assertEquals(2, merged.entries.size)
        assertEquals("에이푸", merged.entries.first { it.sourceTerm == "A-Pu" }.translatedTerm)
        assertTrue(merged.entries.any { it.sourceTerm == "Lin Mei" && it.translatedTerm == "린메이" })
        assertFalse(merged.entries.any { it.id == "remote-apu" })
    }
}
