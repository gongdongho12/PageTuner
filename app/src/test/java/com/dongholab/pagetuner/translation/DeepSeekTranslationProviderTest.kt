package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepSeekTranslationProviderTest {
    @Test
    fun sendsDeepSeekJsonRequestAndPreservesSegmentIdentity() = runTest {
        var capturedEndpoint = ""
        var capturedHeaders = emptyMap<String, String>()
        var capturedBody = ""
        val provider = DeepSeekTranslationProvider(
            apiKey = "test-deepseek-key",
            transport = LlmHttpTransport { endpoint, headers, body ->
                capturedEndpoint = endpoint
                capturedHeaders = headers
                capturedBody = body
                """
                    {
                      "choices": [
                        {
                          "finish_reason": "stop",
                          "message": {
                            "content": "{\"translations\":[\"안녕하세요\",\"비행기에서 읽습니다\"]}"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            },
        )
        val document = PlainTextDocumentParser.parse(
            title = "DeepSeek",
            rawText = "Hello\n\nI read on an airplane.",
        )
        val sourceSegments = document.pages.first().segments

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = sourceSegments,
            ),
        )

        val body = JSONObject(capturedBody)
        assertEquals(DeepSeekDefaults.ApiUrl, capturedEndpoint)
        assertEquals("Bearer test-deepseek-key", capturedHeaders["Authorization"])
        assertEquals(DeepSeekDefaults.Model, body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("disabled", body.getJSONObject("thinking").getString("type"))
        assertEquals(false, body.getBoolean("stream"))
        assertEquals(sourceSegments.map { it.id }, translated.map { it.segmentId })
        assertEquals(listOf("안녕하세요", "비행기에서 읽습니다"), translated.map { it.translatedText })
        assertTrue(provider.id.startsWith("deepseek:"))
    }
}
