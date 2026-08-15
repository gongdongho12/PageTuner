package com.dongholab.pagetuner.source.novelbuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelBuddyDomScraperTest {
    @Test
    fun parsesServerRenderedSearchPageAndPaging() {
        val response = NovelBuddyDomScraper.parseCatalogResponse(catalogHtml, SEARCH_URL)

        assertEquals(1, response.currentPage)
        assertEquals(33, response.totalPages)
        assertEquals(776, response.totalItems)
        assertTrue(response.hasNextPage)
        assertEquals("Shadow Slave", response.items.single().name)
        assertEquals("https://novelbuddy.me/shadow-slave", response.items.single().url)
        assertEquals(3157, response.items.single().chapterCount)
        assertTrue("Fantasy" in response.items.single().tags)
    }

    @Test
    fun parsesDetailFullChapterIndexAndChapterBody() {
        val detail = NovelBuddyDomScraper.parseDetail(detailHtml, BOOK_URL)
        val embedded = NovelBuddyDomScraper.parseEmbeddedChapters(detailHtml, BOOK_URL)
        val complete = NovelBuddyDomScraper.parseChapterIndex(chapterIndexJson, BOOK_URL)
        val chapter = NovelBuddyDomScraper.parseChapterContent(chapterHtml)

        assertEquals("Guiltythree", detail.authors.single())
        assertEquals(3157, detail.chapterCount)
        assertEquals("https://api.novelbuddy.me/titles/VYPGVZ8z/chapters?cv=1786760383290", NovelBuddyDomScraper.chapterIndexUrl(detailHtml, BOOK_URL))
        assertEquals("https://novelbuddy.me/shadow-slave/chapter-1-nightmare-begins", NovelBuddyDomScraper.firstChapterUrl(detailHtml, BOOK_URL))
        assertEquals(listOf(3156, 3157), embedded.map { it.number })
        assertEquals(listOf(1, 2, 3157), complete.map { it.number })
        assertEquals(1, chapter.number)
        assertEquals("Chapter 1: Nightmare Begins", chapter.title)
        assertEquals(2, chapter.paragraphs.size)
        assertTrue(chapter.paragraphs.first().contains("Sunny"))
    }

    private companion object {
        const val SEARCH_URL = "https://novelbuddy.me/search?q=shadow%20slave"
        const val BOOK_URL = "https://novelbuddy.me/shadow-slave"

        val catalogHtml = nextHtml(
            """
            {
              "ssrItems": [{
                "id":"VYPGVZ8z","url":"/shadow-slave","name":"Shadow Slave",
                "cover":"https://rs.novelbuddy.me/covers/shadow-slave.png","status":"OnGoing",
                "rating":4.72,"displayViews":"25.5M","summary":"A dark fantasy.",
                "stats":{"chaptersCount":3157},
                "genres":[{"name":"Fantasy","slug":"fantasy"}]
              }],
              "ssrPagination":{"total":776,"page":1,"limit":24,"total_pages":33,"has_next":true,"has_previous":false}
            }
            """.trimIndent(),
        )

        val detailHtml = nextHtml(
            """
            {"initialManga":{
              "id":"VYPGVZ8z","url":"/shadow-slave","name":"Shadow Slave",
              "cover":"https://rs.novelbuddy.me/covers/shadow-slave.png","status":"OnGoing",
              "rating":4.72,"displayViews":"25.5M","summary":"A dark fantasy.","cv":1786760383290,
              "authors":[{"name":"Guiltythree"}],
              "genres":[{"name":"Fantasy","slug":"fantasy"}],
              "tags":[{"name":"System","slug":"system"}],
              "stats":{"chaptersCount":3157},
              "firstChapter":{"number":1,"name":"Chapter 1: Nightmare Begins","url":"/shadow-slave/chapter-1-nightmare-begins"},
              "chapters":[
                {"id":"c3157","number":3157,"name":"Chapter 3157 Shock and Awe","url":"/shadow-slave/chapter-3157-shock-and-awe"},
                {"id":"c3156","number":3156,"name":"Chapter 3156 Strong Roots","url":"/shadow-slave/chapter-3156-strong-roots"}
              ]
            }}
            """.trimIndent(),
        )

        val chapterIndexJson =
            """{"success":true,"data":{"chapters":[
              {"id":"c3157","number":3157,"name":"Chapter 3157 Shock and Awe","url":"/shadow-slave/chapter-3157-shock-and-awe"},
              {"id":"c2","number":2,"name":"Chapter 2: Slave Caravan","url":"/shadow-slave/chapter-2-slave-caravan"},
              {"id":"c1","number":1,"name":"Chapter 1: Nightmare Begins","url":"/shadow-slave/chapter-1-nightmare-begins"}
            ]}}"""

        val chapterHtml = nextHtml(
            """
            {"initialChapter":{
              "id":"c1","number":1,"name":"Chapter 1: Nightmare Begins",
              "url":"/shadow-slave/chapter-1-nightmare-begins",
              "content":"<p>Sunny held a real cup of coffee.</p><p>His life was coming to an end.</p>"
            }}
            """.trimIndent(),
        )

        fun nextHtml(pageProps: String): String =
            """<html><head><title>NovelBuddy</title></head><body><script id="__NEXT_DATA__" type="application/json">{"props":{"pageProps":$pageProps}}</script></body></html>"""
    }
}
