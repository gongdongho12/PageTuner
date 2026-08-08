package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.source.wtr.WtrLabDomScraper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                  "image": "https://img.wtr-lab.com/god-emperor.webp"
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

        private fun nextDataHtml(pageProps: String): String = """
            <html><head><title>WTR-LAB</title></head><body>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":$pageProps}}
              </script>
            </body></html>
        """.trimIndent()
    }
}
