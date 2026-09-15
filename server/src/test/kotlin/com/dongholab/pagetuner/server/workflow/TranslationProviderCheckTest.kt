package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.translation.*
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import java.security.Principal
import kotlinx.coroutines.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletResponse

class TranslationProviderCheckTest {
    private val request = TranslationProviderCheckRequest("GOOGLE_WEB_TRANSLATE_HTML")
    private fun translator(block: suspend (ChapterContent, JobConfiguration, String) -> List<TranslatedParagraph>) = object : WorkflowTranslator {
        override suspend fun translate(chapter: ChapterContent, config: JobConfiguration, apiKey: String, completed: Map<String, String>,
            onParagraph: suspend (String, String) -> Unit): List<TranslatedParagraph> {
            assertTrue(completed.isEmpty())
            return block(chapter, config, apiKey)
        }
    }
    private fun translated(chapter: ChapterContent) = chapter.paragraphs.map { TranslatedParagraph(it.paragraphId, "확인된 번역") }

    @Test fun `all four providers use shared resolved settings and only an ephemeral fixed sample`() {
        val secret = "do-not-expose-this-key"
        val providers = WorkflowProviders(mapOf("DEEPSEEK_API_KEY" to secret, "PAGETUNER_GOOGLE_API_KEY" to secret, "OPENAI_API_KEY" to secret))
        val seen = mutableListOf<String>()
        val checks = TranslationProviderChecks(providers, translator { chapter, config, key ->
            assertEquals(1, chapter.paragraphs.size)
            assertEquals("The reader opens a book beside the window.", chapter.paragraphs.single().text)
            assertEquals("auto", config.sourceLanguage); assertEquals("ko", config.targetLanguage)
            assertEquals(if (config.providerKind == "GOOGLE_WEB_TRANSLATE_HTML") "" else secret, key)
            seen += config.providerKind
            translated(chapter)
        })
        try {
            for (provider in providers.list().providers) {
                val result = checks.check(provider.id, TranslationProviderCheckRequest(provider.id)).get(2, TimeUnit.SECONDS)
                assertEquals("SUCCESS", result.status); assertEquals("PROVIDER_CHECK_OK", result.code)
                assertEquals(provider.id, result.providerKind)
                val json = jacksonObjectMapper().writeValueAsString(result)
                listOf(secret, "확인된 번역", "The reader", "endpoint", "apiKey").forEach { assertFalse(json.contains(it)) }
            }
            assertEquals(4, seen.size)
            assertFalse(TranslationProviderCheckRequest("DEEPSEEK", apiKey = secret).toString().contains(secret))
        } finally { checks.close() }
    }

    @Test fun `invalid configuration never reaches a provider and environment seams preserve the allowlist`() {
        val calls = AtomicInteger()
        val checks = TranslationProviderChecks(WorkflowProviders(emptyMap()), translator { chapter, _, _ -> calls.incrementAndGet(); translated(chapter) })
        try {
            for ((input, code) in listOf(
                TranslationProviderCheckRequest("unknown") to "INVALID_PROVIDER",
                TranslationProviderCheckRequest("DEEPSEEK") to "PROVIDER_NOT_CONFIGURED",
                TranslationProviderCheckRequest("DEEPSEEK", apiKey = "key", endpoint = "https://attacker.example/chat/completions") to "ENDPOINT_NOT_ALLOWED",
            )) assertEquals(code, assertThrows(WorkflowFailure::class.java) { checks.check("reader", input) }.code)
            for (input in listOf(TranslationProviderCheckRequest("GOOGLE_WEB_TRANSLATE_HTML", targetLanguage = "auto"),
                TranslationProviderCheckRequest("GOOGLE_WEB_TRANSLATE_HTML", sourceLanguage = "ko"),
                TranslationProviderCheckRequest("DEEPSEEK", apiKey = "key\nsecret"),
                TranslationProviderCheckRequest("DEEPSEEK", apiKey = "k".repeat(4097)),
                TranslationProviderCheckRequest("DEEPSEEK", apiKey = "key", model = "m".repeat(201)))) {
                assertThrows(IllegalArgumentException::class.java) { checks.check("reader", input) }
            }
            assertEquals(0, calls.get())
        } finally { checks.close() }
        val endpoint = "http://127.0.0.1:54321/chat/completions"
        val configured = WorkflowProviders(mapOf("PAGETUNER_LLM_ENDPOINTS" to endpoint, "DEEPSEEK_API_KEY" to "server-key"))
        val allowed = TranslationProviderChecks(configured, translator { chapter, config, key ->
            assertEquals(endpoint, config.endpoint); assertEquals("server-key", key); translated(chapter)
        })
        try { assertEquals("SUCCESS", allowed.check("reader", TranslationProviderCheckRequest("DEEPSEEK", endpoint = "$endpoint/")).get(2, TimeUnit.SECONDS).status) }
        finally { allowed.close() }
    }

    @Test fun `typed provider failures and malformed results expose only fixed public codes`() {
        for (kind in TranslationProviderErrorKind.entries) {
            val checks = TranslationProviderChecks(WorkflowProviders(emptyMap()), translator { _, _, _ ->
                throw TranslationProviderException(TranslationProviderFailure("secret-name", kind, "secret-response-body"), IllegalStateException("secret-cause"))
            })
            try {
                val result = checks.check("reader", request).get(2, TimeUnit.SECONDS)
                assertEquals("FAILED", result.status); assertEquals(publicProviderError(kind).first, result.code)
                assertFalse(result.toString().contains("secret-")); assertFalse(result.message.contains("완료된 문단"))
            } finally { checks.close() }
        }
        val malformed = TranslationProviderChecks(WorkflowProviders(emptyMap()), translator { _, _, _ -> listOf(TranslatedParagraph("wrong-id", "text")) })
        try { assertEquals("TRANSLATION_INVALID_RESPONSE", malformed.check("reader", request).get(2, TimeUnit.SECONDS).code) }
        finally { malformed.close() }
    }

    @Test fun `account and global concurrency and cooldown are bounded`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val checks = TranslationProviderChecks(WorkflowProviders(emptyMap()), translator { chapter, _, _ -> gate.await(); translated(chapter) })
        var time = Instant.EPOCH; checks.now = { time }
        try {
            val pending = (1..4).map { checks.check("reader-$it", request) }
            assertEquals("PROVIDER_CHECK_BUSY", assertThrows(WorkflowFailure::class.java) { checks.check("reader-1", request) }.code)
            assertEquals(429, assertThrows(WorkflowFailure::class.java) { checks.check("reader-5", request) }.httpStatus)
            gate.complete(Unit); pending.forEach { assertEquals("SUCCESS", it.get(2, TimeUnit.SECONDS).status) }
            assertThrows(WorkflowFailure::class.java) { checks.check("reader-1", request) }
            time = time.plusSeconds(11)
            eventually { checks.check("reader-1", request).get(2, TimeUnit.SECONDS).status == "SUCCESS" }
        } finally { gate.complete(Unit); checks.close() }
    }

    @Test fun `timeout response and controller future cancellation retain permits until provider cleanup finishes`() = runBlocking {
        for (timeout in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val cleaning = CompletableDeferred<Unit>(); val cleanup = CompletableDeferred<Unit>()
            val checks = TranslationProviderChecks(WorkflowProviders(emptyMap()), translator { _, _, _ ->
                entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { cleaning.complete(Unit); cleanup.await() } }
            })
            checks.timeoutMillis = if (timeout) 100 else 20_000
            var time = Instant.EPOCH; checks.now = { time }
            try {
                val pending: CompletableFuture<*> = if (timeout) checks.check("reader", request)
                    else TranslationProviderCheckController(checks).check(Principal { "reader" }, request, MockHttpServletResponse())
                withTimeout(2000) { entered.await() }
                if (timeout) assertEquals("PROVIDER_CHECK_TIMEOUT", (pending.get(2, TimeUnit.SECONDS) as TranslationProviderCheckView).code) else assertTrue(pending.cancel(true))
                withTimeout(2000) { cleaning.await() }; time = time.plusSeconds(11)
                assertEquals("PROVIDER_CHECK_BUSY", assertThrows(WorkflowFailure::class.java) { checks.check("reader", request) }.code)
                cleanup.complete(Unit)
                eventually { checks.check("reader", request).also { it.cancel(true) }; true }
            } finally { cleanup.complete(Unit); checks.close() }
        }
    }

    private suspend fun eventually(check: () -> Boolean) = withTimeout(2000) {
        while (true) { if (runCatching(check).getOrDefault(false)) break; delay(5) }
    }
}
