package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebNovelRemoteBookSourceTest {
    @Test
    fun connectDetailAndListShareOneBoundedPageFetch() = runTest {
        var fetchCount = 0
        val source = WebNovelRemoteBookSource(
            accountId = "wtr",
            endpointUrl = WtrLabDomScraperTest.NOVEL_URL,
            fetchHtml = {
                fetchCount += 1
                WtrLabDomScraperTest.detailHtml
            },
            renderedChapterLoader = null,
        )

        val connection = source.connect()
        val detail = source.loadNovelDetail()
        val chapters = source.list()

        assertEquals(1, fetchCount)
        assertEquals(3, connection.itemCount)
        assertEquals("God Emperor of Devouring", detail.title)
        assertEquals(3, chapters.size)
        assertEquals("chapter_1", chapters.first().identity.remoteId)
        assertEquals(1, chapters.first().chapterNumber)
        assertEquals("God Emperor of Devouring", chapters.first().seriesTitle)
        assertTrue(chapters.mapNotNull { it.seriesId }.distinct().size == 1)
    }

    @Test
    fun downloadUsesRenderedStructuredParagraphsForWtrChapter() = runTest {
        val longParagraph = "Rendered chapter content ".repeat(12).trim()
        val source = WebNovelRemoteBookSource(
            accountId = "wtr",
            endpointUrl = CHAPTER_URL,
            fetchHtml = { error("Static HTML should not be fetched for a rendered WTR chapter.") },
            renderedChapterLoader = RenderedChapterLoader { _, number ->
                RenderedChapter(
                    title = "Chapter $number The Demon Contract",
                    paragraphs = listOf(longParagraph, "Second paragraph."),
                )
            },
        )

        val text = source.download(chapterItem()).toString(Charsets.UTF_8)

        assertTrue(text.startsWith("# Chapter 1 The Demon Contract"))
        assertTrue(text.contains(longParagraph))
        assertTrue(text.contains("\n\nSecond paragraph."))
    }

    @Test
    fun pagedCatalogLoadsTheCanonicalListUrlAndReportsDomStages() = runTest {
        val requestedUrls = mutableListOf<String>()
        val steps = mutableListOf<RemoteCatalogLoadStep>()
        val source = WebNovelRemoteBookSource(
            accountId = "wtr",
            endpointUrl = "https://wtr-lab.com/en",
            fetchHtml = { url ->
                requestedUrls += url
                WtrLabDomScraperTest.catalogHtml
            },
            renderedChapterLoader = null,
        )

        val page = source.loadCatalogPage(2) { steps += it }

        assertEquals(
            "https://wtr-lab.com/en/novel-list?page=2",
            requestedUrls.single(),
        )
        assertEquals(2, page.currentPage)
        assertEquals(1, page.items.size)
        assertEquals(
            listOf(RemoteCatalogLoadStep.FetchingPage, RemoteCatalogLoadStep.ParsingDom),
            steps,
        )
    }

    @Test
    fun downloadFailsInsteadOfCreatingAPlaceholderBookWhenBodyIsMissing() = runTest {
        val source = WebNovelRemoteBookSource(
            accountId = "wtr",
            endpointUrl = CHAPTER_URL,
            fetchHtml = { "" },
            renderedChapterLoader = RenderedChapterLoader { _, _ ->
                RenderedChapter(title = "Chapter 1", paragraphs = emptyList())
            },
        )

        val error = runCatching { source.download(chapterItem()) }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error?.message.orEmpty().contains("rendered body"))
    }

    private fun chapterItem() = RemoteBookItem(
        identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "wtr", "chapter_1"),
        title = "Chapter 1",
        format = DocumentFormat.TEXT,
        downloadUrl = CHAPTER_URL,
    )

    private companion object {
        const val CHAPTER_URL =
            "https://wtr-lab.com/en/novel/88774/god-emperor-of-devouring/chapter-1"
    }
}
