package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.source.service.NovelSourceService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Opt-in only. Every source response comes from the production HTTPS client; no fallback fixtures. */
@Tag("live-source")
class NovelSourceLiveTest {
    @Test
    fun `WTR live catalog detail index and reader POST complete`() = verify("wtr-lab", "Sea Survival")

    @Test
    fun `NovelBuddy live catalog detail full index and chapter complete`() = verify("novelbuddy", "shadow slave")

    private fun verify(sourceId: String, query: String) = runBlocking {
        check(System.getenv("RUN_LIVE_WEB_NOVEL_TESTS") == "1") { "Live source tests must be explicitly enabled." }
        withTimeout(180_000) {
            val service = NovelSourceService(PublicHttpsNovelHttpClient())
            val catalog = service.catalog(sourceId, null, query, 1)
            assertTrue(catalog.items.isNotEmpty(), "Real catalog did not contain books")
            val book = catalog.items.first()
            val detail = service.detail(book.url, 0, 20)
            assertTrue(detail.chapters.isNotEmpty(), "Real detail did not contain chapters")
            val chapter = detail.chapters.first()
            val draft = service.fetchChapter(chapter.url, book.url)
            assertEquals(book.bookId, draft.bookId)
            assertEquals(chapter.chapterId, draft.chapterId)
            assertTrue(draft.paragraphs.sumOf { it.length } >= 100)
            println("LIVE_SOURCE provider=$sourceId catalogItems=${catalog.items.size} chapters=${detail.totalItems} " +
                "paragraphs=${draft.paragraphs.size} characters=${draft.paragraphs.sumOf { it.length }}")
        }
    }
}
