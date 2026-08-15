package com.dongholab.pagetuner.source.webnovel

import com.dongholab.pagetuner.source.RenderedChapter
import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.WtrLabDomScraperTest
import com.dongholab.pagetuner.source.WebNovelRemoteBookSource
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WtrLabChapterLoadingTest {
    @Test
    fun httpOnlyLoadsReaderJsonWithoutCreatingWebView() = runTest {
        var httpCalls = 0
        var renderedCalls = 0
        val adapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly,
            postReaderJson = { apiUrl, body, referer ->
                httpCalls += 1
                assertEquals("https://wtr-lab.com/api/reader/get", apiUrl)
                assertTrue(body.contains("\"raw_id\":88774"))
                assertTrue(body.contains("\"chapter_no\":1"))
                assertEquals(CHAPTER_URL, referer)
                WtrLabDomScraperTest.readerJson
            },
        )

        val result = adapter.loadChapter(
            url = CHAPTER_URL,
            fallbackTitle = "Chapter 1",
            fetchHtml = { error("Static page fetch is not required by the reader HTTP call.") },
            renderedChapterLoader = RenderedChapterLoader { _, _ ->
                renderedCalls += 1
                error("WebView must not run after a successful HTTP response.")
            },
        )

        assertEquals(1, httpCalls)
        assertEquals(0, renderedCalls)
        assertEquals("Chapter 1: A Dream or Reality?", result.title)
        assertEquals("Ji Ting met Yu Wen at the Shopping Mall.", result.paragraphs.first())
    }

    @Test
    fun automaticModeFallsBackToWebViewWhenHttpReaderIsBlocked() = runTest {
        var renderedCalls = 0
        val adapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpThenWebView,
            postReaderJson = { _, _, _ -> throw IOException("Turnstile required") },
        )

        val result = adapter.loadChapter(
            url = CHAPTER_URL,
            fallbackTitle = "Chapter 1",
            fetchHtml = { error("Unused") },
            renderedChapterLoader = RenderedChapterLoader { _, number ->
                renderedCalls += 1
                RenderedChapter(
                    title = "Rendered chapter $number",
                    paragraphs = listOf("Rendered fallback content. ".repeat(8)),
                )
            },
        )

        assertEquals(1, renderedCalls)
        assertEquals("Rendered chapter 1", result.title)
    }

    @Test
    fun automaticModeSkipsWebViewWhenProviderRequiresAuthentication() = runTest {
        var renderedCalls = 0
        val adapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpThenWebView,
            postReaderJson = { _, _, _ ->
                """{"success":false,"error":"You are not logged in!","code":1401}"""
            },
        )

        val failure = runCatching {
            adapter.loadChapter(
                url = CHAPTER_URL,
                fallbackTitle = "Chapter 1",
                fetchHtml = { error("Unused") },
                renderedChapterLoader = RenderedChapterLoader { _, _ ->
                    renderedCalls += 1
                    error("Authentication failures must not start WebView.")
                },
            )
        }.exceptionOrNull()

        assertTrue(failure is WebNovelAuthenticationRequiredException)
        assertEquals(0, renderedCalls)
    }

    @Test
    fun directChapterPreservesLanguageFromUrlForOfflineMetadata() = runTest {
        val adapter = WtrLabSiteAdapter(chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly)
        val source = WebNovelRemoteBookSource(
            accountId = "direct_url",
            endpointUrl = CHAPTER_URL,
            fetchHtml = { WtrLabDomScraperTest.detailHtml },
            renderedChapterLoader = null,
            adapterRegistry = WebNovelSiteAdapterRegistry(listOf(adapter)),
        )

        assertEquals("en", source.list().single().language)
    }

    @Test
    fun webViewOnlySkipsTheHttpReader() = runTest {
        var httpCalls = 0
        val adapter = WtrLabSiteAdapter(
            chapterLoadStrategy = WebNovelChapterLoadStrategy.WebViewOnly,
            postReaderJson = { _, _, _ ->
                httpCalls += 1
                error("HTTP must be skipped")
            },
        )

        adapter.loadChapter(
            url = CHAPTER_URL,
            fallbackTitle = "Chapter 1",
            fetchHtml = { error("Unused") },
            renderedChapterLoader = RenderedChapterLoader { _, _ ->
                RenderedChapter("Rendered", listOf("Rendered content. ".repeat(8)))
            },
        )

        assertEquals(0, httpCalls)
    }

    private companion object {
        const val CHAPTER_URL =
            "https://wtr-lab.com/en/novel/88774/god-emperor-of-devouring/chapter-1"
    }
}
