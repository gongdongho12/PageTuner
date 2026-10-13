package com.dongholab.pagetuner.source.wtr

import com.dongholab.pagetuner.source.RenderedChapterLoader
import com.dongholab.pagetuner.source.webnovel.WebNovelAuthenticationRequiredException
import com.dongholab.pagetuner.source.webnovel.WebNovelChapterLoadStrategy
import com.dongholab.pagetuner.source.webnovel.WtrLabSiteAdapter
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WtrLabReaderContentTest {
    @Test fun loadsOneVerifiedContentLocationAndPreservesRequestedIdentity() = runBlocking {
        val fetched = mutableListOf<String>()
        val result = adapter(location()).loadChapter(CHAPTER_URL, "Fallback", { url ->
            fetched += url
            content()
        }, null)
        assertEquals(listOf(CONTENT_URL), fetched)
        assertEquals(1, result.number)
        assertEquals("Translated chapter title", result.title)
        assertEquals(2, result.paragraphs.size)
        assertTrue(result.paragraphs.first().startsWith("The navigator examined"))
        assertTrue(result.paragraphs.last().startsWith("Well, the second"))
        val parsed = WtrLabDomScraper.parseReaderChapterResponse(43378, 1, location(), contentJson = content())
        assertEquals(43378L, parsed.novelId)
        assertEquals(1, parsed.chapterNumber)
    }

    @Test fun validatesOuterNovelOrderAndDatabaseIdBeforeAnyGet() {
        val invalid = listOf(
            JSONObject(location()).apply { remove("chapter") },
            changedChapter("raw_id", 43379), changedChapter("order", 2),
            changedChapter("raw_id", JSONObject.NULL), changedChapter("order", 1.5),
            changedChapter("id", JSONObject.NULL), changedChapter("id", 0),
            changedChapter("id", -1), changedChapter("id", 28316965.5),
        )
        invalid.forEach { assertRejectedBeforeGet(it.toString()) }
    }

    @Test fun rejectsForeignAmbiguousOrMismatchedContentLocationsBeforeAnyGet() {
        val invalid = listOf(
            CONTENT_URL.replace("https:", "http:"),
            CONTENT_URL.replace("wtr-lab.com", "untrusted.example"),
            CONTENT_URL.replace("wtr-lab.com", "sub.wtr-lab.com"),
            CONTENT_URL.replace("wtr-lab.com", "wtr-lab.com:8443"),
            CONTENT_URL.replace("wtr-lab.com", "user:secret@wtr-lab.com"),
            CONTENT_URL.replace("43378/", "43379/"),
            CONTENT_URL.replace("28316965/", "28316966/"),
            CONTENT_URL.replace("/en/", "/ko/"),
            CONTENT_URL.replace("/ai/", "/raw/"),
            CONTENT_URL.replace("/en/", "/en%2f/"),
            CONTENT_URL.replace("/en/", "/en/../en/"),
            CONTENT_URL.replace("/en/", "/en\\/"),
            CONTENT_URL.removePrefix("https:"),
            CONTENT_URL.substringAfter("wtr-lab.com/"),
            CONTENT_URL.substringAfter("wtr-lab.com").replace("/en/", "/en/../en/"),
            "$CONTENT_URL?token=unexpected", "$CONTENT_URL#fragment", "$CONTENT_URL/extra",
            CONTENT_URL.substringBeforeLast('/') + "/..",
            CONTENT_URL.substringBeforeLast('/') + "/" + "a".repeat(1025),
        )
        invalid.forEach { url ->
            assertRejectedBeforeGet(JSONObject(location()).put("content_url", url).toString())
        }
        assertRejectedBeforeGet(JSONObject(location()).put("content_url", JSONObject.NULL).toString())
    }

    @Test fun resolvesRootRelativeLocationAgainstVerifiedReaderOrigin() = runBlocking {
        val response = JSONObject(location()).put("content_url", CONTENT_URL.substringAfter("wtr-lab.com"))
        val fetched = mutableListOf<String>()
        val result = adapter(response.toString()).loadChapter(CHAPTER_URL, "Fallback", {
            fetched += it
            content()
        }, null)
        assertEquals(listOf(CONTENT_URL), fetched)
        assertEquals(2, result.paragraphs.size)
    }

    @Test fun supportsBothInlineBodyShapesWithoutRequiringADatabaseIdOrFollowingALocation() = runBlocking {
        for (nested in listOf(false, true)) {
            val response = JSONObject(location()).apply {
                getJSONObject("chapter").remove("id")
                put("content_url", "https://untrusted.example/never-fetched")
                val data = JSONObject(content()).getJSONObject("data")
                put("data", if (nested) JSONObject().put("data", data) else data)
            }
            val result = adapter(response.toString()).loadChapter(CHAPTER_URL, "Fallback",
                { error("Inline content must not fetch a location") }, null)
            assertEquals(2, result.paragraphs.size)
        }
    }

    @Test fun authenticationAndBrowserVerificationRemainDistinctBeforeAnyContentGet() {
        val auth = failure("""{"success":false,"code":1401,"content_url":"$CONTENT_URL"}""")
        assertTrue(auth is WebNovelAuthenticationRequiredException)
        val blocked = failure("""{"success":false,"requireTurnstile":true,"content_url":"$CONTENT_URL"}""")
        assertTrue(blocked is IOException)
        assertFalse(blocked is WebNovelAuthenticationRequiredException)
        assertTrue(blocked!!.message.orEmpty().contains("browser verification"))
    }

    @Test fun contentAuthenticationDoesNotRetryInWebView() = runBlocking {
        var gets = 0
        val adapter = WtrLabSiteAdapter(WebNovelChapterLoadStrategy.HttpThenWebView, { _, _, _ -> location() })
        val failure = runCatching {
            adapter.loadChapter(CHAPTER_URL, "Fallback", {
                gets++
                """{"success":false,"code":1401}"""
            }, RenderedChapterLoader { _, _ -> error("Authentication must not retry in WebView") })
        }.exceptionOrNull()
        assertTrue(failure is WebNovelAuthenticationRequiredException)
        assertEquals(1, gets)
    }

    @Test fun doesNotRecursivelyFollowAnotherLocationOrAcceptEmptyContent() = runBlocking {
        for (body in listOf(location(), """{"success":true,"data":{"body":[]}}""", """{"success":true,"data":{"body":["too short"]}}""")) {
            var gets = 0
            val failure = runCatching { adapter(location()).loadChapter(CHAPTER_URL, "Fallback", {
                gets++
                body
            }, null) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertEquals(1, gets)
        }
    }

    @Test fun malformedJsonAndProviderErrorsDoNotRevealSignedLocations() = runBlocking {
        for (body in listOf("""{"success":true,"content_url":"$CONTENT_URL" invalid}""",
            """{"success":false,"error":"$CONTENT_URL"}""")) {
            val failure = runCatching { adapter(location()).loadChapter(CHAPTER_URL, "Fallback", { body }, null) }.exceptionOrNull()
            assertTrue(failure is IOException)
            assertFalse(failure!!.stackTraceToString().contains("synthetic-test-signature"))
        }
        assertEquals("wtr-lab.com/api/v2/reader/content/[redacted]", WtrLabReaderContent.diagnosticTarget(CONTENT_URL))
    }

    private fun adapter(response: String) = WtrLabSiteAdapter(
        chapterLoadStrategy = WebNovelChapterLoadStrategy.HttpOnly,
        postReaderJson = { url, body, referer ->
            assertEquals("https://wtr-lab.com/api/reader/get", url)
            val request = JSONObject(body)
            assertEquals(43378L, request.getLong("raw_id"))
            assertEquals(1, request.getInt("chapter_no"))
            assertEquals("en", request.getString("language"))
            assertEquals(CHAPTER_URL, referer)
            response
        },
    )

    private fun changedChapter(key: String, value: Any) = JSONObject(location()).apply {
        getJSONObject("chapter").put(key, value)
    }

    private fun assertRejectedBeforeGet(response: String) {
        val failure = failure(response)
        assertTrue("Expected an IOException", failure is IOException)
        assertFalse(failure!!.stackTraceToString().contains("synthetic-test-signature"))
    }

    private fun failure(response: String): Throwable? = runBlocking {
        runCatching { adapter(response).loadChapter(CHAPTER_URL, "Fallback",
            { error("Invalid reader response must not fetch content") }, null) }.exceptionOrNull()
    }

    private fun location() = fixture("reader-location.json")
    private fun content() = fixture("reader-content.json")
    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/wtr/$name")).readText()

    companion object {
        private const val CHAPTER_URL = "https://wtr-lab.com/en/novel/43378/sample/chapter-1"
        private const val CONTENT_URL = "https://wtr-lab.com/api/v2/reader/content/v1/ai/43378/28316965/en/0123456789abcdef0123456789abcdef/108/1792627200/synthetic-test-signature"
    }
}
