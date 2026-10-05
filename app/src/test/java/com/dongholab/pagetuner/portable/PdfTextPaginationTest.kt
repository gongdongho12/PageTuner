package com.dongholab.pagetuner.portable

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PdfTextPaginationTest {
    @Test fun everySourceCharacterIncludingWhitespaceAndSupplementaryCharactersAppearsExactlyOnce() {
        val text = ("가나다 🌏\r\n\n  text\t끝\n" + "x".repeat(17)).repeat(100)
        val pages = mutableListOf<String>()
        var previous = 0
        val count = PdfTextPagination.forEachPage(text, maximumWindow = 19,
            measuredEnd = { start, windowEnd ->
                assertTrue(windowEnd - start <= 19)
                windowEnd
            }, render = { start, end, number ->
                assertEquals(previous, start)
                assertEquals(pages.size + 1, number)
                assertFalse(text[start].isLowSurrogate())
                assertFalse(text[end - 1].isHighSurrogate())
                pages += text.substring(start, end)
                previous = end
            })
        assertEquals(pages.size, count)
        assertEquals(text, pages.joinToString(""))
        assertEquals(text.length, previous)
    }

    @Test fun measuredLineBoundariesAreRetainedAndFinalPageIsNotDropped() {
        val text = "첫 줄\n\n둘째 🌏 줄\n마지막"
        val result = mutableListOf<String>()
        val count = PdfTextPagination.forEachPage(text,
            measuredEnd = { start, end -> text.indexOf('\n', start).takeIf { it >= 0 }?.plus(1) ?: end },
            render = { start, end, _ -> result += text.substring(start, end) })
        assertEquals(4, count)
        assertEquals(listOf("첫 줄\n", "\n", "둘째 🌏 줄\n", "마지막"), result)
        assertEquals(text, result.joinToString(""))
    }

    @Test fun noProgressSurrogateSplitOutOfWindowAndPageOverflowFailExplicitly() {
        for (invalid in listOf(0, 1, 4)) {
            assertThrows(IllegalArgumentException::class.java) {
                PdfTextPagination.forEachPage("🌏x", measuredEnd = { _, _ -> invalid },
                    render = { _, _, _ -> fail("Invalid layout must not create a page") })
            }
        }
        var rendered = 0
        assertThrows(IllegalArgumentException::class.java) {
            PdfTextPagination.forEachPage("12345", maximumPages = 2, maximumWindow = 2,
                measuredEnd = { _, end -> end }, render = { _, _, _ -> rendered++ })
        }
        assertEquals(2, rendered)
    }

    @Test fun finalFontPaddingBacksOffWholeLinesWithExactCoverageAndTerminatesWhenNothingFits() {
        val text = "첫째\n둘째🌏\n셋째\n마지막"
        val result = mutableListOf<String>()
        val attempted = mutableListOf<Int>()
        var firstPage = true
        PdfTextPagination.forEachPage(text, measuredEnd = { start, end ->
            val lines = ((start until end).filter { text[it] == '\n' }.map { it + 1 } + end).distinct()
            // All candidates fitted while intermediate; the last becomes too tall with bottom padding.
            PdfTextPagination.finalizedEnd(start, lines) { candidate ->
                attempted += candidate
                !firstPage || candidate != end
            }
        }, render = { start, end, _ ->
            result += text.substring(start, end)
            firstPage = false
        })
        assertEquals(listOf("첫째\n둘째🌏\n셋째\n", "마지막"), result)
        assertEquals(text, result.joinToString(""))
        assertEquals(listOf(text.length, text.lastIndexOf('\n') + 1, text.length), attempted)

        var measured = 0
        assertThrows(IllegalArgumentException::class.java) {
            PdfTextPagination.forEachPage("한글", measuredEnd = { start, end ->
                PdfTextPagination.finalizedEnd(start, listOf(start + 1, end)) { measured++; false }
            }, render = { _, _, _ -> fail("A page with no fitting whole line must never be drawn") })
        }
        assertEquals(2, measured)
    }

    @Test fun cancellationBetweenPagesStopsBeforeAnotherLayoutOrRender() {
        var rendered = 0
        var measured = 0
        assertThrows(CancellationException::class.java) {
            PdfTextPagination.forEachPage("12345", maximumWindow = 2,
                checkActive = { if (rendered == 1) throw CancellationException("cancel") },
                measuredEnd = { _, end -> measured++; end }, render = { _, _, _ -> rendered++ })
        }
        assertEquals(1, measured)
        assertEquals(1, rendered)
    }

    @Test fun boundedOutputRejectsTheEntireOverflowingWriteAndChecksCancellation() {
        var cancelled = false
        val output = BoundedDocumentOutputStream(4) { if (cancelled) throw CancellationException("cancel") }
        output.write(byteArrayOf(1, 2))
        assertThrows(IllegalArgumentException::class.java) { output.write(byteArrayOf(3, 4, 5)) }
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray())
        output.write(byteArrayOf(3, 4))
        assertThrows(IllegalArgumentException::class.java) { output.write(5) }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), output.toByteArray())
        cancelled = true
        assertThrows(CancellationException::class.java) { output.write(6) }
    }
}
