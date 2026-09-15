package com.dongholab.pagetuner.server.catalogTranslation

import com.dongholab.pagetuner.server.workflow.WorkflowFailure
import com.dongholab.pagetuner.server.workflow.publicProviderError
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.springframework.stereotype.Service

/** Disposable display translations only: no DB, filesystem, credential fields, or durable partial results. */
@Service
class CatalogTranslationJobs(private val translator: CatalogTextTranslator) {
    private data class Entry(val owner: String, val signature: String, var view: CatalogTranslationView, var task: Job? = null)
    private val entries = mutableMapOf<Pair<String, UUID>, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun start(username: String, input: CatalogTranslationRequest): CatalogTranslationView {
        val request = input.snapshot().also { it.validate() }
        val key = username to request.requestId
        val entry = synchronized(lock) {
            prune()
            entries[key]?.let {
                if (it.signature != request.signature) throw WorkflowFailure("CATALOG_REQUEST_CONFLICT", 409, "요청 ID에 다른 카탈로그가 지정되었습니다.")
                return it.view
            }
            if (entries.values.any { it.owner == username && it.view.status in ACTIVE }) {
                throw WorkflowFailure("CATALOG_BUSY", 429, "진행 중인 목록 번역을 완료하거나 취소한 뒤 다시 시도해 주세요.")
            }
            if (entries.size >= 128) {
                entries.entries.filter { it.value.view.status !in ACTIVE }.minByOrNull { it.value.view.updatedAt }?.let { entries.remove(it.key) }
            }
            if (entries.size >= 128 || entries.values.count { it.view.status in ACTIVE } >= 16) {
                throw WorkflowFailure("CATALOG_BUSY", 429, "목록 번역 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
            }
            Entry(username, request.signature, CatalogTranslationView(request.requestId, "QUEUED", request.sourceHash,
                request.providerKind, request.targetLanguage, 0, 0, emptyList(), null, Instant.now())).also { entry ->
                entries[key] = entry
                entry.task = scope.launch(start = CoroutineStart.LAZY) {
                    update(key, entry) { it.copy(status = "RUNNING") }
                    try {
                        val result = withTimeout(120_000) {
                            translator.translate(request) { complete, total ->
                                ensureActive()
                                check(total > 0 && complete in 0..total)
                                update(key, entry) { it.copy(completedSegments = complete, totalSegments = total) }
                            }
                        }
                        ensureActive()
                        check(result.map { it.key } == request.items.map { it.key } && result.all {
                            it.title.isNotBlank() && it.title.length <= 4_000 && (it.description?.length ?: 0) <= 16_000 && it.targetLanguage == request.targetLanguage
                        } && result.sumOf { it.title.length + (it.description?.length ?: 0) } <= 96_000)
                        check(request.items.zip(result).all { (original, translated) -> original.description.isNullOrBlank() || !translated.description.isNullOrBlank() })
                        update(key, entry) { it.copy(status = "COMPLETED", completedSegments = it.totalSegments, items = result) }
                    } catch (_: TimeoutCancellationException) {
                        update(key, entry) { it.copy(status = "FAILED", errorCode = "CATALOG_TIMEOUT", items = emptyList()) }
                    } catch (error: CancellationException) {
                        update(key, entry) { it.copy(status = "CANCELLED", errorCode = null, items = emptyList()) }
                        throw error
                    } catch (error: Exception) {
                        val safeCode = if (error is WorkflowFailure && error.code in setOf("PROVIDER_NOT_CONFIGURED", "ENDPOINT_NOT_ALLOWED", "INVALID_PROVIDER")) error.code else publicProviderError(error).first
                        update(key, entry) { it.copy(status = "FAILED", errorCode = safeCode, items = emptyList()) }
                    } finally {
                        synchronized(lock) { if (entries[key] === entry) entry.task = null }
                    }
                }
            }
        }
        entry.task!!.start()
        return get(username, request.requestId)
    }

    fun get(username: String, requestId: UUID): CatalogTranslationView = synchronized(lock) {
        prune()
        entries[username to requestId]?.view ?: throw WorkflowFailure("CATALOG_NOT_FOUND", 404, "목록 번역 기록이 만료되었습니다. 현재 목록에서 다시 번역해 주세요.")
    }

    fun cancel(username: String, requestId: UUID): CatalogTranslationView = synchronized(lock) {
        val entry = entries[username to requestId] ?: throw WorkflowFailure("CATALOG_NOT_FOUND", 404, "목록 번역 기록이 만료되었습니다.")
        if (entry.view.status in ACTIVE) {
            entry.view = entry.view.copy(status = "CANCELLED", items = emptyList(), errorCode = null, updatedAt = Instant.now())
            entry.task?.cancel()
        }
        entry.view
    }

    private fun update(key: Pair<String, UUID>, expected: Entry, change: (CatalogTranslationView) -> CatalogTranslationView) = synchronized(lock) {
        entries[key]?.let { entry ->
            // Cancellation has priority over a late provider response, progress callback, or timeout.
            if (entry === expected && entry.view.status != "CANCELLED") entry.view = change(entry.view).copy(updatedAt = Instant.now())
        }
    }
    private fun prune() { val cutoff = Instant.now().minusSeconds(900); entries.entries.removeIf { it.value.view.status !in ACTIVE && it.value.view.updatedAt < cutoff } }
    @PreDestroy fun close() { scope.cancel() }
    private companion object { val ACTIVE = setOf("QUEUED", "RUNNING") }
}
