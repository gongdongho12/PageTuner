package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion
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

    @Test
    fun discoversAndReusesBookCharacterAliases() = runTest {
        var capturedBody = ""
        var discovered = emptyList<CharacterAliasSuggestion>()
        val provider = OpenAiCompatibleLlmTranslationProvider(
            apiKey = "test-key",
            endpoint = "https://example.com/v1/chat/completions",
            model = "sample-model",
            initialCharacterAliases = listOf(CharacterAliasSuggestion("Qin Feng", "진풍")),
            onCharacterAliases = { discovered = it },
            transport = LlmHttpTransport { _, _, body ->
                capturedBody = body
                """
                    {
                      "choices": [
                        {
                          "message": {
                            "content": "{\"translations\":[\"아푸는 진풍을 만났다.\"],\"characterAliases\":[{\"source\":\"A-Pu\",\"alias\":\"아푸\"},{\"source\":\"Hallucinated\",\"alias\":\"환각\"}]}"
                          }
                        }
                      ]
                    }
                """.trimIndent()
            },
        )
        val document = PlainTextDocumentParser.parse("Novel", "A-Pu met Qin Feng.")

        val translated = provider.translate(
            TranslationRequest("en", "ko", document.pages.first().segments),
        )

        assertEquals("아푸는 진풍을 만났다.", translated.single().translatedText)
        assertEquals(listOf(CharacterAliasSuggestion("A-Pu", "아푸")), discovered)
        val userPrompt = JSONObject(capturedBody)
            .getJSONArray("messages")
            .getJSONObject(1)
            .getString("content")
        assertTrue(userPrompt.contains("Qin Feng"))
        assertTrue(userPrompt.contains("진풍"))
        assertTrue(userPrompt.contains("characterAliases"))
        assertTrue(provider.id.endsWith(":character-alias-v1"))
    }
}
