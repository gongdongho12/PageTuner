package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleLlmTranslationProviderTest {
    @Test
    fun translatesOpenAiCompatibleJsonResponse() = runTest {
        var capturedBody = ""
        val provider = OpenAiCompatibleLlmTranslationProvider(
            apiKey = "test-key",
            endpoint = "https://example.com/v1/chat/completions",
            model = "sample-model",
            transport = LlmHttpTransport { _, _, body ->
                capturedBody = body
                """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"translations\":[\"안녕\",\"세계\"]}"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            },
        )
        val document = PlainTextDocumentParser.parse(
            title = "LLM",
            rawText = "Hello\n\nWorld",
        )

        val translated = provider.translate(
            TranslationRequest(
                sourceLanguage = "en",
                targetLanguage = "ko",
                segments = document.pages.first().segments,
            ),
        )

        assertEquals(listOf("안녕", "세계"), translated.map { it.translatedText })
        assertEquals("sample-model", JSONObject(capturedBody).getString("model"))
        assertTrue(provider.id.startsWith("openai-compatible-llm:"))
    }
}
