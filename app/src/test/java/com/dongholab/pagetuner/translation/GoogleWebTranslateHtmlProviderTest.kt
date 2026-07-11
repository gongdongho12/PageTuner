package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GoogleWebTranslateHtmlProviderTest {
    @Test
    fun postsTranslateHtmlPayloadAndParsesAnchoredResponse() = runTest {
        var capturedEndpoint = ""
        var capturedHeaders = emptyMap<String, String>()
        var capturedBody = ""
        val provider = GoogleWebTranslateHtmlProvider(
            apiKey = "test-key",
            endpoint = "https://example.com/v1/translateHtml",
            transport = GoogleWebTranslateHtmlTransport { endpoint, headers, body ->
                capturedEndpoint = endpoint
                capturedHeaders = headers
                capturedBody = body
                """[["<a i=0>안녕 &amp; 반가워</a><a i=1>세계</a>"]]"""
            },
        )
        val document = PlainTextDocumentParser.parse(
            title = "Google Web",
            rawText = "Hello & hi\n\nWorld",
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = document.pages.first().segments,
            ),
        )

        assertEquals(listOf("안녕 & 반가워", "세계"), translated.map { it.translatedText })
        assertEquals("https://example.com/v1/translateHtml", capturedEndpoint)
        assertEquals("test-key", capturedHeaders["X-Goog-Api-Key"])
        assertEquals("*/*", capturedHeaders["Accept"])
        assertEquals("ko,en;q=0.9", capturedHeaders["Accept-Language"])
        assertEquals("application/json+protobuf", capturedHeaders["Content-Type"])
        assertFalse(capturedHeaders.containsKey("X-Browser-Validation"))
        assertFalse(capturedHeaders.containsKey("X-Client-Data"))
        assertFalse(capturedHeaders.containsKey("Origin"))
        assertTrue(provider.id.startsWith("google-web-translate-html:"))

        val root = JSONTokener(capturedBody).nextValue() as JSONArray
        val requestTuple = root.getJSONArray(0)
        val htmlSegments = requestTuple.getJSONArray(0)
        assertEquals("te_lib", root.getString(1))
        assertEquals("en", requestTuple.getString(1))
        assertEquals("ko", requestTuple.getString(2))
        assertEquals("<a i=0>Hello &amp; hi</a>", htmlSegments.getString(0))
        assertEquals("<a i=1>World</a>", htmlSegments.getString(1))
    }

    @Test
    fun postsTranslateHtmlPayloadWithoutApiKeyHeaderWhenKeyIsBlank() = runTest {
        var capturedHeaders = emptyMap<String, String>()
        val provider = GoogleWebTranslateHtmlProvider(
            apiKey = "",
            endpoint = "https://example.com/v1/translateHtml",
            transport = GoogleWebTranslateHtmlTransport { _, headers, _ ->
                capturedHeaders = headers
                """[["<a i=0>안녕</a>"]]"""
            },
        )
        val document = PlainTextDocumentParser.parse(
            title = "Google Web",
            rawText = "Hello",
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = document.pages.first().segments,
            ),
        )

        assertEquals(listOf("안녕"), translated.map { it.translatedText })
        assertFalse(capturedHeaders.containsKey("X-Goog-Api-Key"))
        assertEquals("*/*", capturedHeaders["Accept"])
        assertEquals("application/json+protobuf", capturedHeaders["Content-Type"])
    }

    @Test
    fun parserFallsBackToDirectStringArrays() {
        val parsed = GoogleWebTranslateHtmlResponseParser.parse(
            response = """[[["안녕", "세계"]]]""",
            expectedCount = 2,
        ).getOrThrow()

        assertEquals(listOf("안녕", "세계"), parsed)
    }

    @Test
    fun parserHandlesGoogleJsonPrefixAndObjectWrappedStrings() {
        val parsed = GoogleWebTranslateHtmlResponseParser.parse(
            response = """
                )]}'
                {"payload":["<a i=0>안녕</a>","<a i=1>세계</a>"]}
            """.trimIndent(),
            expectedCount = 2,
        ).getOrThrow()

        assertEquals(listOf("안녕", "세계"), parsed)
    }

    @Test
    fun parserHandlesAnchorsSplitAcrossCollectedStrings() {
        val parsed = GoogleWebTranslateHtmlResponseParser.parse(
            response = """[["<a i=0>안","녕</a><a i=1>세","계</a>"]]""",
            expectedCount = 2,
        ).getOrThrow()

        assertEquals(listOf("안녕", "세계"), parsed)
    }

    @Test
    fun wrapsUnexpectedResponsesAsProviderFailures() = runTest {
        val provider = GoogleWebTranslateHtmlProvider(
            apiKey = "test-key",
            transport = GoogleWebTranslateHtmlTransport { _, _, _ -> """{"unexpected":true}""" },
        )
        val document = PlainTextDocumentParser.parse(
            title = "Google Web",
            rawText = "Hello",
        )

        try {
            provider.translate(
                TranslationRequest(
                    sourceLanguage = "en",
                    targetLanguage = "ko",
                    segments = document.pages.first().segments,
                ),
            )
            fail("Expected provider failure.")
        } catch (error: TranslationProviderException) {
            assertEquals("Google Web HTML", error.failure.providerName)
            assertEquals(TranslationProviderErrorKind.ResponseFormat, error.failure.kind)
        }
    }
}
