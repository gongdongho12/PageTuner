package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class WtrLabDomScraperTest {
    @Test
    fun parsesCurrentNestedNextDataCatalogShape() {
        val response = WtrLabDomScraper.parseNovelListResponse(
            html = catalogHtml,
            baseUrl = "https://wtr-lab.com/en",
        )

        val novel = response.novels.single()
        assertEquals(88774L, novel.novelId)
        assertEquals("God Emperor of Devouring", novel.title)
        assertEquals("god-emperor-of-devouring", novel.slug)
        assertEquals(2368, novel.chapterCount)
        assertEquals("https://img.wtr-lab.com/god-emperor.webp", novel.coverUrl)
        assertEquals("completed", novel.status)
        assertEquals("Yi Pian Hong Ye", novel.author)
        assertEquals("A devouring cultivation story.", novel.description)
    }

    @Test
    fun parsesDetailAndBuildsCompleteChapterUrlsWithoutLoadingEveryChapter() {
        val detail = WtrLabDomScraper.parseNovelDetailResponse(
            novelId = 88774L,
            html = detailHtml,
            url = NOVEL_URL,
        )
        val chapters = WtrLabDomScraper.parseChapterListResponse(
            novelId = 88774L,
            html = detailHtml,
            url = NOVEL_URL,
        )

        assertEquals("Yi Pian Hong Ye", detail.author)
        assertEquals("吞天神皇", detail.titleOriginal)
        assertEquals("A real synopsis.", detail.summary)
        assertEquals(3, detail.totalChapters)
        assertEquals(3, chapters.chapters.size)
        assertEquals("Chapter 3 The Finale", chapters.chapters.last().title)
        assertEquals(
            "/en/novel/88774/god-emperor-of-devouring/chapter-3",
            chapters.chapters.last().urlPath,
        )
    }

    @Test
    fun doesNotInventQuickResumeOrPlaceholderNovelMetadata() {
        val home = WtrLabDomScraper.parseHomeResponse(catalogHtml)

        assertEquals(null, home.quickResume)
        assertTrue(home.sections.single().items.single().views == "12")
    }

    @Test
    fun parsesServerPaginationWithoutTreatingOnePageAsTheWholeCatalog() {
        val series = (1..10).joinToString(",") { index ->
            """{"raw_id":$index,"slug":"book-$index","data":{"title":"Book $index"}}"""
        }
        val response = WtrLabDomScraper.parseNovelListResponse(
            html = nextDataHtml("""{"count":"85857","series":[$series]}"""),
            baseUrl = "https://wtr-lab.com/en/novel-list?page=2",
            currentPage = 2,
        )

        assertEquals(10, response.novels.size)
        assertEquals(85_857, response.totalItems)
        assertEquals(8_586, response.totalPages)
        assertTrue(response.hasNextPage)
    }

    @Test
    fun finderUsesFilteredPageLinksInsteadOfTheStaleGlobalCount() {
        val html = nextDataHtml(
            """{"count":"85859","series":[{"raw_id":1,"slug":"book-1","data":{"title":"Book 1"}}]}""",
        ).replace(
            "</body>",
            """<a href="/en/novel-finder?text=book&amp;page=29">29</a></body>""",
        )

        val response = WtrLabDomScraper.parseNovelListResponse(
            html = html,
            baseUrl = "https://wtr-lab.com/en/novel-finder?text=book&page=1",
            currentPage = 1,
        )

        assertEquals(29, response.totalPages)
        assertEquals(null, response.totalItems)
        assertTrue(response.hasNextPage)
    }

    @Test
    fun parsesReaderHttpResponseAndResolvesGlossaryMarkers() {
        val response = WtrLabDomScraper.parseReaderChapterResponse(
            novelId = 51_593L,
            chapterNumber = 1,
            rawJson = readerJson,
            language = "en",
        )

        assertEquals("Chapter 1: A Dream or Reality?", response.titleTranslated)
        assertEquals(2, response.paragraphs.size)
        assertEquals("Ji Ting met Yu Wen at the Shopping Mall.", response.paragraphs.first())
        assertTrue(
            response.paragraphs.last(),
            response.paragraphs.last().startsWith("Um, this is the second paragraph"),
        )
    }

    @Test
    fun readerAuthenticationResponseIsClassifiedWithoutRenderedRetry() {
        val error = assertThrows(
            com.dongholab.pagetuner.source.webnovel.WebNovelAuthenticationRequiredException::class.java,
        ) {
            WtrLabDomScraper.parseReaderChapterResponse(
                novelId = 90_937L,
                chapterNumber = 18,
                rawJson = """{"success":false,"error":"You are not logged in!","code":1401}""",
            )
        }

        assertEquals("WTR-LAB", error.providerName)
    }

    companion object {
        const val NOVEL_URL = "https://wtr-lab.com/en/novel/88774/god-emperor-of-devouring"

        val catalogHtml = nextDataHtml(
            """
            {
              "series": [{
                "id": 85122,
                "raw_id": 88774,
                "slug": "god-emperor-of-devouring",
                "status": 1,
                "chapter_count": 2368,
                "view": 5,
                "view_temp": 7,
                "data": {
                  "title": "God Emperor of Devouring",
                  "image": "https://img.wtr-lab.com/god-emperor.webp",
                  "author": "Yi Pian Hong Ye",
                  "description": "A devouring cultivation story."
                }
              }]
            }
            """.trimIndent(),
        )

        val detailHtml = nextDataHtml(
            """
            {
              "serie": {
                "serie_data": {
                  "id": 85122,
                  "raw_id": 88774,
                  "slug": "god-emperor-of-devouring",
                  "status": 1,
                  "chapter_count": 3,
                  "data": {
                    "title": "God Emperor of Devouring",
                    "author": "Yi Pian Hong Ye",
                    "description": "A real synopsis.",
                    "image": "https://img.wtr-lab.com/god-emperor.webp",
                    "raw": {"title": "吞天神皇", "author": "一片红叶"}
                  }
                },
                "last_chapters": [{
                  "order": 3,
                  "title": "Chapter 3 The Finale",
                  "updated_at": "2026-08-06 19:14:37+00"
                }]
              },
              "tags": []
            }
            """.trimIndent(),
        )

        val readerJson = """
            {
              "success": true,
              "chapter": {
                "raw_id": 51593,
                "order": 1,
                "title": "Chapter 1: Dream or Reality?"
              },
              "data": {
                "data": {
                  "title": "Chapter 1: A Dream or Reality?",
                  "body": [
                    "※0⛬ met ※1⛬ at the ※2⛬.",
                    "嗯, this is the second paragraph and it deliberately contains enough readable text to exercise the same minimum-content validation used by production chapter downloads."
                  ],
                  "glossary_data": {
                    "terms": [
                      ["Ji Ting", "季听"],
                      ["Yu Wen", "喻闻"],
                      ["Shopping Mall", "商场"]
                    ]
                  },
                  "patch": [{"en": "Um", "zh": "嗯"}]
                }
              }
            }
        """.trimIndent()

        private fun nextDataHtml(pageProps: String): String = """
            <html><head><title>WTR-LAB</title></head><body>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":$pageProps}}
              </script>
            </body></html>
        """.trimIndent()
    }
}
