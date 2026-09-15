package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.TextSegment
import com.sun.net.httpserver.HttpServer
import java.io.Closeable
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP requests on loopback; no provider keys or paid services are used. */
class ProviderLoopbackIntegrationTest {
    private val request = TranslationRequest("en", "ko", listOf(TextSegment("one", 0, 0, "Hello.")))

    @Test fun allThreeProvidersRoundTripRealHttpWithTheirProductionWireContracts() = runBlocking {
        Fixture().use { fixture ->
            val providers = fixture.providers()
            providers.forEach { provider ->
                assertEquals(listOf(TranslatedSegment("one", "안녕.")), provider.translate(request))
            }
            val calls = fixture.calls.toList()
            assertEquals(3, calls.size)
            calls.forEach { assertEquals("POST", it.method); assertNull(it.query); assertFalse(it.body.toString().contains(KEY)) }
            assertEquals("deepseek-flash", calls[0].body.getString("model"))
            assertEquals("disabled", calls[0].body.getJSONObject("thinking").getString("type"))
            assertEquals("json_object", calls[0].body.getJSONObject("response_format").getString("type"))
            assertEquals(32_768, calls[0].body.getInt("max_tokens"))
            assertEquals("custom-model", calls[1].body.getString("model"))
            assertFalse(calls[1].body.has("thinking"))
            assertEquals("Bearer $KEY", calls[0].authorization)
            assertEquals("Bearer $KEY", calls[1].authorization)
            assertEquals(KEY, calls[2].googleKey)
            assertNull(calls[2].authorization)
            assertEquals("/language/translate/v2", calls[2].path)
            assertEquals("Hello.", calls[2].body.getJSONArray("q").getString(0))
            assertEquals("text", calls[2].body.getString("format"))
            assertEquals("en", calls[2].body.getString("source"))
        }
    }

    @Test fun incompleteAndMalformedRealHttpResponsesNeverBecomeSuccessfulTranslations() = runBlocking {
        Fixture().use { fixture ->
            fixture.malformed = true
            fixture.providers().forEach { provider ->
                val error = runCatching { provider.translate(request) }.exceptionOrNull()
                assertEquals(TranslationProviderErrorKind.ResponseFormat, (error as TranslationProviderException).failure.kind)
            }
            assertEquals(3, fixture.calls.size)
            fixture.malformed = false
            fixture.status = 429
            fixture.providers().forEach { provider ->
                val error = runCatching { provider.translate(request) }.exceptionOrNull()
                assertEquals(TranslationProviderErrorKind.RateLimited, (error as TranslationProviderException).failure.kind)
                assertFalse(error.toString().contains(KEY))
                assertFalse(error.toString().contains("private response"))
            }
            assertEquals(6, fixture.calls.size)
        }
    }

    private data class Call(val method: String, val path: String, val query: String?, val body: JSONObject,
        val authorization: String?, val googleKey: String?)

    private class Fixture : Closeable {
        val calls = ConcurrentLinkedQueue<Call>()
        @Volatile var malformed = false
        @Volatile var status = 200
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val body = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                calls += Call(exchange.requestMethod, exchange.requestURI.path, exchange.requestURI.rawQuery, JSONObject(body),
                    exchange.requestHeaders.getFirst("Authorization"), exchange.requestHeaders.getFirst("x-goog-api-key"))
                val cloud = exchange.requestURI.path.endsWith("/translate/v2")
                val response = when {
                    status != 200 -> "private response $KEY"
                    cloud && malformed -> """{"data":{"translations":[{"translatedText":17}]}}"""
                    cloud -> """{"data":{"translations":[{"translatedText":"안녕."}]}}"""
                    else -> """{"choices":[{"finish_reason":"${if (malformed) "length" else "stop"}","message":{"content":"{\"translations\":[\"안녕.\"]}"}}]}"""
                }.toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(status, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
                exchange.close()
            }
            start()
        }
        private val base = "http://127.0.0.1:${server.address.port}"

        fun providers(): List<TranslationProvider> = listOf(
            TranslationProviderFactory.create(TranslationSettings(TranslationProviderKind.DEEPSEEK, KEY,
                llmEndpoint = "$base/chat/completions", llmModel = DeepSeekDefaults.Model)),
            TranslationProviderFactory.create(TranslationSettings(TranslationProviderKind.OPENAI_COMPATIBLE_LLM, KEY,
                llmEndpoint = "$base/v1/chat/completions", llmModel = "custom-model")),
            GoogleCloudTranslationProvider(KEY, LlmHttpTransport { endpoint, headers, body ->
                check(endpoint == "https://translation.googleapis.com/language/translate/v2")
                // Only replace routing for the fixed Google endpoint; all headers/body/transport/parser are production.
                ProviderHttpTransport("Google Cloud").post("$base/language/translate/v2", headers, body)
            }),
        )

        override fun close() { server.stop(0) }
    }

    private companion object { const val KEY = "loopback-test-credential" }
}
