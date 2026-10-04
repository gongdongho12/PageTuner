package com.dongholab.pagetuner.server.readingtranslation

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.server.workflow.CreateTranslationJobRequest
import com.dongholab.pagetuner.server.workflow.WorkflowFailure
import com.dongholab.pagetuner.server.workflow.WorkflowProviders
import com.dongholab.pagetuner.server.workflow.publicProviderError
import jakarta.annotation.PreDestroy
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import org.springframework.stereotype.Service

/** Bounded in-memory previews. No translation artifact, source row or checkpoint is written here. */
@Service class ReadingTranslationJobs(private val source: ReadingSource, private val providers: WorkflowProviders, private val translator: ReadingFragmentTranslator) {
    private data class Entry(val owner: String, val signature: String, var view: ReadingTranslationView, var task: Job? = null)
    private val entries = mutableMapOf<Pair<String, UUID>, Entry>()
    // UUIDs are allocated by the client before POST. A cancel arriving first must win over a delayed start.
    private val cancelledBeforeStart = mutableMapOf<Pair<String, UUID>, Instant>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()

    fun start(username: String, input: ReadingTranslationRequest): ReadingTranslationView {
        val request = input.snapshot()
        val chapter = source.get(username, request.chapterRecordId)
        request.validate(chapter)
        val (configuration, secret) = providers.resolve(CreateTranslationJobRequest(chapter.recordId, request.providerKind,
            request.targetLanguage, request.requestId, request.sourceLanguage, request.endpoint, request.model, request.apiKey, request.glossary), chapter)
        val signature = StableContentHash.sha256(listOf(request.sourceHash, configuration.providerKind, configuration.sourceLanguage,
            configuration.targetLanguage, configuration.endpoint, configuration.model, configuration.translationProviderId,
            configuration.promptRevision, configuration.glossaryRevision, request.readingWordsPerMinute.toString(), request.paceMode)
            .joinToString("") { "${it.length}:$it" })
        val key = username to request.requestId
        val entry = synchronized(lock) {
            prune()
            if (key in cancelledBeforeStart) throw WorkflowFailure("READING_CANCELLED", 409, "이미 취소한 읽기 요청입니다. 새 요청 ID로 다시 시작해 주세요.")
            entries[key]?.let { if (it.signature != signature) throw WorkflowFailure("READING_REQUEST_CONFLICT", 409, "이 요청 ID에는 다른 읽기 범위가 지정되어 있습니다."); return it.view }
            // Cancellation is visible immediately, but its provider call may still be cleaning up.
            // Reserve a slot until the coroutine actually completes, including a queued lazy task.
            if (entries.values.any { it.owner == username && it.task != null }) throw WorkflowFailure("READING_BUSY", 429, "진행 중인 읽기 번역을 완료하거나 취소해 주세요.")
            if (entries.size >= 128) entries.entries.filter { it.value.task == null && it.value.view.status !in ACTIVE }.minByOrNull { it.value.view.updatedAt }?.let { entries.remove(it.key) }
            if (entries.size >= 128 || entries.values.count { it.task != null } >= 8) throw WorkflowFailure("READING_BUSY", 429, "읽기 번역 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
            Entry(username, signature, ReadingTranslationView(request.requestId, "QUEUED", chapter.recordId, chapter.sourceRevision,
                request.sourceHash, configuration.providerKind, configuration.targetLanguage, 0, request.fragments.size, emptyList(), null, Instant.now())).also { entry ->
                entries[key] = entry
                val task = scope.launch(start = CoroutineStart.LAZY) {
                    update(key, entry) { it.copy(status = "RUNNING") }
                    try {
                        val result = withTimeout(120_000) { translator.translate(chapter, request, configuration, secret) { completed ->
                            ensureActive(); check(completed in 0..request.fragments.size)
                            update(key, entry) { it.copy(completedFragments = completed) }
                        } }
                        ensureActive()
                        check(result.map { ReadingFragment(it.paragraphId, it.start, it.end) } == request.fragments &&
                            result.all { it.text.isNotBlank() && it.text.length <= 96_000 } && result.sumOf { it.text.length.toLong() } <= 96_000) { "Invalid reading translation response." }
                        update(key, entry) { it.copy(status = "COMPLETED", completedFragments = it.totalFragments, items = result) }
                    } catch (_: TimeoutCancellationException) { update(key, entry) { it.copy(status = "FAILED", errorCode = "READING_TIMEOUT", items = emptyList()) } }
                    catch (error: CancellationException) { update(key, entry) { it.copy(status = "CANCELLED", items = emptyList()) }; throw error }
                    catch (error: Exception) { update(key, entry) { it.copy(status = "FAILED", errorCode = publicProviderError(error).first, items = emptyList()) } }
                }
                entry.task = task
                // A lazy task cancelled before it starts never enters a coroutine-body finally block.
                task.invokeOnCompletion { synchronized(lock) { if (entries[key] === entry) entry.task = null } }
            }
        }
        entry.task?.start()
        return get(username, request.requestId)
    }
    fun get(username: String, id: UUID): ReadingTranslationView = synchronized(lock) {
        prune(); entries[username to id]?.view ?: throw WorkflowFailure("READING_NOT_FOUND", 404, "임시 읽기 번역이 만료되었습니다. 현재 쪽을 다시 번역해 주세요.")
    }
    fun cancel(username: String, id: UUID): ReadingTranslationView = synchronized(lock) {
        prune()
        val key = username to id
        val entry = entries[key] ?: run {
            if (cancelledBeforeStart.size >= 512 && key !in cancelledBeforeStart) throw WorkflowFailure("READING_BUSY", 429, "취소 요청이 많습니다. 잠시 후 다시 시도해 주세요.")
            cancelledBeforeStart[key] = Instant.now()
            throw WorkflowFailure("READING_NOT_FOUND", 404, "아직 시작하지 않은 요청도 취소했습니다.")
        }
        if (entry.view.status in ACTIVE) { entry.view = entry.view.copy(status = "CANCELLED", items = emptyList(), errorCode = null, updatedAt = Instant.now()); entry.task?.cancel() }
        entry.view
    }
    private fun update(key: Pair<String, UUID>, expected: Entry, change: (ReadingTranslationView) -> ReadingTranslationView) = synchronized(lock) {
        entries[key]?.let { if (it === expected && it.view.status != "CANCELLED") it.view = change(it.view).copy(updatedAt = Instant.now()) }
    }
    private fun prune() {
        val cutoff = Instant.now().minusSeconds(900)
        entries.entries.removeIf { it.value.task == null && it.value.view.status !in ACTIVE && it.value.view.updatedAt < cutoff }
        cancelledBeforeStart.entries.removeIf { it.value < cutoff }
    }
    @PreDestroy fun close() { scope.cancel() }
    private companion object { val ACTIVE = setOf("QUEUED", "RUNNING") }
}
