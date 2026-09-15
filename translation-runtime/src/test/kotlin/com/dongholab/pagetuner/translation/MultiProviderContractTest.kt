package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.document.TextSegment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MultiProviderContractTest {
    private val request = TranslationRequest("en", "ko", listOf(TextSegment("first", 0, 0, "Hello."), TextSegment("second", 0, 1, "A new world.")))

    @Test fun deepSeekSendsTheCurrentModelAndBoundedNonThinkingJsonRequestThroughRealTransport() = runBlocking {
        val wire = Wire(chat("""{"translations":["안녕.","새로운 세계."]}"""))
        val provider = DeepSeekTranslationProvider(KEY, transport = wire.transport)
        val result = provider.translate(request)
        assertEquals(DeepSeekDefaults.ApiUrl, wire.connection.url.toString())
        assertEquals("deepseek-flash", wire.body.getString("model"))
        assertEquals("Bearer $KEY", wire.connection.getRequestProperty("Authorization"))
        assertEquals("json_object", wire.body.getJSONObject("response_format").getString("type"))
        assertEquals("disabled", wire.body.getJSONObject("thinking").getString("type"))
        assertEquals(32_768, wire.body.getInt("max_tokens"))
        assertFalse(wire.body.getBoolean("stream"))
        assertEquals(0, wire.body.getInt("temperature"))
        assertTrue(wire.body.getJSONArray("messages").getJSONObject(0).getString("content").contains("JSON"))
        assertEquals(request.segments.map { it.id }, result.map { it.segmentId })
        assertEquals(listOf("안녕.", "새로운 세계."), result.map { it.translatedText })
        assertTrue(wire.connection.closed)
    }

    @Test fun customOpenAiEndpointKeepsItsModelAndAcceptsOneCompleteFencedAnswer() = runBlocking {
        val wire = Wire(chat("```json\n{\"translations\":[\"안녕.\",\"새로운 세계.\"]}\n```"))
        val provider = llm(wire.transport)
        assertEquals(2, provider.translate(request).size)
        assertEquals(ENDPOINT, wire.connection.url.toString())
        assertEquals("custom-model", wire.body.getString("model"))
        assertFalse(wire.body.has("thinking"))
        assertFalse(wire.body.has("max_tokens"))
        assertFalse(wire.body.has("response_format"))
        assertFalse(wire.connection.url.toString().contains(KEY))
    }

    @Test fun googleCloudUsesTheOfficialApiKeyHeaderAndPlainTextBodyWithoutCredentialInUrl() = runBlocking {
        val wire = Wire("""{"data":{"translations":[{"translatedText":"안녕."},{"translatedText":"새로운 세계."}]}}""")
        val provider = GoogleCloudTranslationProvider("  $KEY  ", wire.transport)
        val result = provider.translate(request.copy(sourceLanguage = "auto"))
        assertEquals("https://translation.googleapis.com/language/translate/v2", wire.connection.url.toString())
        assertNull(wire.connection.url.query)
        assertEquals(KEY, wire.connection.getRequestProperty("x-goog-api-key"))
        assertEquals("text", wire.body.getString("format"))
        assertEquals("ko", wire.body.getString("target"))
        assertFalse(wire.body.has("source"))
        assertEquals(request.segments.map { it.text }, (0 until wire.body.getJSONArray("q").length()).map { wire.body.getJSONArray("q").getString(it) })
        assertEquals(request.segments.map { it.id }, result.map { it.segmentId })
        provider.translate(request)
        assertEquals("en", wire.body.getString("source"))
        assertFalse(wire.body.toString().contains(KEY))
    }

    @Test fun incompleteRefusedAndToolCallingCompletionsAreRejectedEvenWithAValidTranslationArray() = runBlocking {
        val content = """{"translations":["안녕.","새로운 세계."]}"""
        val variants = listOf("length", "content_filter", "tool_calls", "function_call", "insufficient_system_resource", "aborted", "unknown")
            .map { chat(content, it) } + listOf(
                JSONObject(chat(content)).apply { getJSONArray("choices").getJSONObject(0).remove("finish_reason") }.toString(),
                JSONObject(chat(content)).apply { getJSONArray("choices").getJSONObject(0).put("finish_reason", JSONObject.NULL) }.toString(),
                chat(content, refusal = "private-provider-text"),
                JSONObject(chat(content)).apply { getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("tool_calls", JSONArray().put(JSONObject().put("id", "tool"))) }.toString(),
                JSONObject(chat(content)).apply { getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("function_call", JSONObject()) }.toString(),
                JSONObject(chat(content)).apply { getJSONArray("choices").put(getJSONArray("choices").getJSONObject(0)) }.toString(),
            )
        variants.forEach { response ->
            assertFormat { llm(LlmHttpTransport { _, _, _ -> response }).translate(request) }
        }
    }

    @Test fun llmDoesNotCoerceTypesSalvageEmbeddedJsonOrAcceptMalformedJson() = runBlocking {
        val contents = listOf(
            "{}", """{"translations":"text"}""", """{"translations":["first",7]}""",
            """{"translations":["first",null]}""", """{"translations":["first",{}]}""", """{"translations":["first",true]}""",
            """{"translations":["first"]}""", """{"translations":["first"," "]}""",
            "prefix {\"translations\":[\"a\",\"b\"]}", "{\"translations\":[\"a\",\"b\"]} trailing",
            "{translations:['a','b']}", "{\"translations\":[\"a\",\"b\"],}",
            """{"translations":["a","b"],"\u0074ranslations":["c","d"]}""",
            """{"translations":["a","\uD800"]}""", "```json\n{\"translations\":[\"a\",\"b\"]}",
            "{\"translations\":[\"a\",\"b\"],\"extra\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}",
        )
        contents.forEach { response -> assertFormat { llm(LlmHttpTransport { _, _, _ -> chat(response) }).translate(request) } }
        listOf(JSONObject.NULL, 123, JSONObject(), JSONArray()).forEach { content ->
            val response = JSONObject(chat("unused")).apply { getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content", content) }.toString()
            assertFormat { llm(LlmHttpTransport { _, _, _ -> response }).translate(request) }
        }
        assertFormat { llm(LlmHttpTransport { _, _, _ -> chat("{}") + " trailing" }).translate(request) }
    }

    @Test fun cloudRejectsMissingNonStringAndMalformedTranslationsWithTypedSafeErrors() = runBlocking {
        val responses = listOf("{}", "not JSON", """{"data":{"translations":[]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},{}]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},null]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},{"translatedText":17}]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},{"translatedText":false}]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},{"translatedText":null}]}}""",
            """{"data":{"translations":[{"translatedText":"ok"},{"translatedText":" "}]}}""",
            """{"data":{"translations":[{"translatedText":"a"},{"translatedText":"b"}]}} trailing""")
        responses.forEach { response -> assertFormat { GoogleCloudTranslationProvider(KEY, LlmHttpTransport { _, _, _ -> response }).translate(request) } }
    }

    @Test fun malformedProviderBatchCannotPublishAnyChapterCheckpoint() = runBlocking {
        val chapter = ChapterContent(ChapterIdentity(BookIdentity("fixture", "book"), "chapter"), "Chapter", "en",
            request.segments.mapIndexed { index, segment -> ContentParagraph(segment.id, index, segment.text) })
        val cases = listOf(
            llm(LlmHttpTransport { _, _, _ -> chat("""{"translations":["first",23]}""") }),
            DeepSeekTranslationProvider(KEY, transport = LlmHttpTransport { _, _, _ -> chat("""{"translations":["first","second"]}""", "length") }),
            GoogleCloudTranslationProvider(KEY, LlmHttpTransport { _, _, _ -> """{"data":{"translations":[{"translatedText":"first"},{"translatedText":null}]}}""" }),
        )
        cases.forEach { provider ->
            val committed = mutableListOf<String>()
            val engine = ChapterTranslationEngine(providerFactory = { provider }, maxRetries = 0)
            assertFormat { engine.translate(chapter, TranslationSettings(TranslationProviderKind.OPENAI_COMPATIBLE_LLM, KEY, sourceLanguage = "en"),
                onParagraph = { id, _ -> committed += id }) }
            assertTrue(committed.isEmpty())
        }
    }

    @Test fun httpFailuresRemainTypedAndDoNotLeakKeysOrProviderBodyForAnyProvider() = runBlocking {
        val statuses = mapOf(401 to TranslationProviderErrorKind.Authentication, 402 to TranslationProviderErrorKind.Quota,
            429 to TranslationProviderErrorKind.RateLimited, 500 to TranslationProviderErrorKind.Server)
        statuses.forEach { (status, kind) ->
            listOf<(LlmHttpTransport) -> TranslationProvider>(::llm, { DeepSeekTranslationProvider(KEY, transport = it) }, { GoogleCloudTranslationProvider(KEY, it) }).forEach { factory ->
                val wire = Wire("private-provider-text $KEY", status)
                val error = runCatching { factory(wire.transport).translate(request) }.exceptionOrNull()
                assertEquals(kind, (error as TranslationProviderException).failure.kind)
                assertFalse(error.toString().contains(KEY))
                assertFalse(error.toString().contains("private-provider-text"))
                assertTrue(wire.connection.closed)
            }
        }
    }

    @Test fun executionIdentitySeparatesLlmResponseContractAndOptionsWhileRetainingExplicitModels() {
        fun provider(key: String = KEY, options: LlmChatRequestOptions = LlmChatRequestOptions()) =
            OpenAiCompatibleLlmTranslationProvider(key, ENDPOINT, "custom-model", requestOptions = options)
        val original = provider().id
        assertTrue(original.contains(":paragraph-json-v2:"))
        assertEquals(original, provider(key = "different-key").id)
        assertNotEquals(original, provider(options = LlmChatRequestOptions(jsonResponse = true)).id)
        assertNotEquals(original, provider(options = LlmChatRequestOptions(thinkingEnabled = false)).id)
        assertNotEquals(original, provider(options = LlmChatRequestOptions(maxTokens = 32_768)).id)
        assertTrue(DeepSeekTranslationProvider(KEY, model = "deepseek-v4-flash").id.contains(":deepseek-v4-flash:"))
        assertEquals("google-cloud-v2", GoogleCloudTranslationProvider(KEY).id)
    }

    @Test fun headerCredentialsAreValidatedBeforeOpeningTransport() = runBlocking {
        var called = false
        val transport = LlmHttpTransport { _, _, _ -> called = true; "{}" }
        listOf(" ", "key\r\nInjected: value", "x".repeat(4097)).forEach { key ->
            listOf(GoogleCloudTranslationProvider(key, transport), OpenAiCompatibleLlmTranslationProvider(key, ENDPOINT, "model", transport = transport)).forEach { provider ->
                val error = runCatching { provider.translate(request) }.exceptionOrNull()
                assertEquals(TranslationProviderErrorKind.Configuration, (error as TranslationProviderException).failure.kind)
            }
        }
        assertFalse(called)
    }

    private suspend fun assertFormat(action: suspend () -> Any?) {
        val error = runCatching { action() }.exceptionOrNull()
        assertTrue("Malformed response must produce a typed failure", error is TranslationProviderException)
        assertEquals(TranslationProviderErrorKind.ResponseFormat, (error as TranslationProviderException).failure.kind)
        assertNull(error.cause)
        assertFalse(error.toString().contains(KEY))
        assertFalse(error.toString().contains("private-provider-text"))
    }

    private fun llm(transport: LlmHttpTransport) = OpenAiCompatibleLlmTranslationProvider(KEY, ENDPOINT, "custom-model", transport = transport)
    private fun chat(content: String, finish: String = "stop", refusal: String? = null): String = JSONObject()
        .put("choices", JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("role", "assistant").put("content", content).put("refusal", refusal ?: JSONObject.NULL))))
        .toString()

    private class Wire(private val response: String, private val status: Int = 200) {
        lateinit var connection: Connection
        val body: JSONObject get() = JSONObject(connection.output.toString("UTF-8"))
        val transport = LlmHttpTransport { endpoint, headers, body ->
            ProviderHttpTransport("fixture", connectionFactory = { Connection(it, response, status).also { connection = it } }).post(endpoint, headers, body)
        }
    }
    private class Connection(url: URL, private val response: String, private val status: Int) : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        var closed = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = output
        override fun getResponseCode() = status
        override fun getInputStream() = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = inputStream
    }
    private companion object {
        const val KEY = "private-test-key"
        const val ENDPOINT = "https://provider.example/v1/chat/completions"
    }
}
