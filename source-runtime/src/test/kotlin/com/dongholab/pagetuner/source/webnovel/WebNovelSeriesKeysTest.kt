package com.dongholab.pagetuner.source.webnovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class WebNovelSeriesKeysTest {
    @Test
    fun detailAndChapterUrlsResolveToSameSeriesKey() {
        val detail = "https://wtr-lab.com/en/novel/88774/god-emperor-of-devouring"
        val chapter = "$detail/chapter-219?reader=1#content"

        assertEquals(WebNovelSeriesKeys.fromUrl(detail), WebNovelSeriesKeys.fromUrl(chapter))

        val novelBuddyDetail = "https://novelbuddy.me/shadow-slave"
        val novelBuddyChapter = "$novelBuddyDetail/chapter-1-nightmare-begins"
        assertEquals(
            WebNovelSeriesKeys.fromUrl(novelBuddyDetail),
            WebNovelSeriesKeys.fromUrl(novelBuddyChapter),
        )
    }

    @Test
    fun sameChapterNumberInDifferentBooksHasDifferentChapterKey() {
        val first = "https://wtr-lab.com/en/novel/42/first/chapter-1"
        val second = "https://wtr-lab.com/en/novel/99/second/chapter-1"

        assertNotEquals(
            WebNovelChapterKeys.fromUrl(first, 1),
            WebNovelChapterKeys.fromUrl(second, 1),
        )
    }

    @Test
    fun extractsDirectChapterNumberFromCommonUrlShapes() {
        assertEquals(19, WebNovelChapterNumbers.fromUrl("https://example.test/book/1/chapter-19"))
        assertEquals(27, WebNovelChapterNumbers.fromUrl("https://example.test/book/1/chapter/27"))
        assertEquals(
            3157,
            WebNovelChapterNumbers.fromUrl("https://novelbuddy.me/shadow-slave/chapter-3157-shock-and-awe"),
        )
    }
}
