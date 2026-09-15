package com.dongholab.pagetuner.server.workflow

import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletResponse
import java.security.Principal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.*
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

// Avoid generated credential-bearing toString/copy methods.
class TranslationProviderCheckRequest(
    val providerKind: String,
    val targetLanguage: String = "ko",
    val sourceLanguage: String? = null,
    val apiKey: String? = null,
    val endpoint: String? = null,
    val model: String? = null,
) {
    override fun toString() = "TranslationProviderCheckRequest(credentials=REDACTED)"
    fun validate() {
        require(providerKind.length <= 40 && targetLanguage.length in 1..24 && (sourceLanguage?.length ?: 0) <= 24) { "Invalid provider or language." }
        require((apiKey?.length ?: 0) <= 4096 && apiKey.orEmpty().none { it == '\r' || it == '\n' }) { "Invalid API credential." }
        require((endpoint?.length ?: 0) <= 2000 && (model?.length ?: 0) <= 200) { "Invalid provider configuration." }
    }
}

data class TranslationProviderCheckView(
    val status: String, val code: String, val message: String,
    val providerKind: String, val sourceLanguage: String, val targetLanguage: String, val model: String,
)

/** A fixed disposable sample. No source, job, checkpoint, artifact or credential is persisted. */
@Service
class TranslationProviderChecks(private val providers: WorkflowProviders, private val translator: WorkflowTranslator) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val active = mutableMapOf<String, Job>()
    private val cooldown = mutableMapOf<String, Instant>()
    internal var now: () -> Instant = Instant::now
    internal var timeoutMillis = 20_000L

    fun check(username: String, request: TranslationProviderCheckRequest): CompletableFuture<TranslationProviderCheckView> {
        request.validate()
        val sample = sample()
        val (configuration, secret) = providers.resolve(CreateTranslationJobRequest(sample.recordId, request.providerKind,
            request.targetLanguage, UUID.randomUUID(), request.sourceLanguage ?: "auto", request.endpoint, request.model, request.apiKey), sample)
        fun result(status: String, code: String, message: String) = TranslationProviderCheckView(status, code, message,
            configuration.providerKind, configuration.sourceLanguage, configuration.targetLanguage, configuration.model)
        val future = CompletableFuture<TranslationProviderCheckView>()
        val task = synchronized(lock) {
            val time = now()
            cooldown.entries.removeIf { it.value <= time }
            if (username in active || active.size >= 4 || cooldown.size >= 512 || cooldown[username]?.let { it > time } == true) {
                throw WorkflowFailure("PROVIDER_CHECK_BUSY", 429, "번역기 점검이 진행 중이거나 요청 간격이 너무 짧습니다. 잠시 후 다시 시도해 주세요.")
            }
            cooldown[username] = time.plusSeconds(10)
            val worker = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val content = sample.content(configuration.sourceLanguage)
                    val translated = translator.translate(content, configuration, secret, emptyMap()) { _, _ -> ensureActive() }
                    ensureActive()
                    if (translated.size != 1 || translated.single().paragraphId != "sample-1" || translated.single().text.isBlank() || translated.single().text.length > 4096) {
                        val (code, message) = publicProviderError(com.dongholab.pagetuner.translation.TranslationProviderErrorKind.ResponseFormat, resumeCompleted = false)
                        future.complete(result("FAILED", code, message))
                    } else future.complete(result("SUCCESS", "PROVIDER_CHECK_OK", "번역기 연결과 응답 형식을 확인했습니다."))
                } catch (error: CancellationException) {
                    future.complete(result("FAILED", "PROVIDER_CHECK_CANCELLED", "번역기 점검이 취소되었습니다."))
                    throw error
                } catch (error: Exception) {
                    val (code, message) = publicProviderError(error, resumeCompleted = false)
                    future.complete(result("FAILED", code, message))
                }
            }
            active[username] = worker
            // Retain the permit until the coroutine finishes, including non-cancellable cleanup.
            worker.invokeOnCompletion {
                synchronized(lock) { if (active[username] === worker) active.remove(username) }
                future.complete(result("FAILED", "PROVIDER_CHECK_CANCELLED", "번역기 점검이 취소되었습니다."))
            }
            worker
        }
        val deadline = scope.launch {
            delay(timeoutMillis)
            if (future.complete(result("FAILED", "PROVIDER_CHECK_TIMEOUT", "번역기 연결 확인이 시간 내 끝나지 않았습니다. 잠시 후 다시 시도해 주세요."))) task.cancel()
        }
        task.invokeOnCompletion { deadline.cancel() }
        future.whenComplete { _, _ -> if (future.isCancelled) task.cancel() }
        task.start()
        return future
    }

    @PreDestroy fun close() { scope.cancel() }
    private fun sample() = StoredChapter(UUID.fromString("00000000-0000-4000-8000-000000000001"), "provider-check", "sample", "Provider check", "",
        "sample", "Provider check", "", "en", "", listOf(SourceParagraph("sample-1", 0, "The reader opens a book beside the window.")), Instant.EPOCH)
}

@RestController
class TranslationProviderCheckController(private val checks: TranslationProviderChecks) {
    @PostMapping("/api/v1/translation-providers/check")
    fun check(principal: Principal, @RequestBody request: TranslationProviderCheckRequest, response: HttpServletResponse): CompletableFuture<ResponseEntity<TranslationProviderCheckView>> {
        response.setHeader("Cache-Control", "no-store")
        val pending = checks.check(principal.name, request)
        val responseFuture = pending.thenApply { ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(it) }
        responseFuture.whenComplete { _, _ -> if (responseFuture.isCancelled) pending.cancel(true) }
        return responseFuture
    }
}
