package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.server.translation.SaveTranslationRequest
import com.dongholab.pagetuner.server.translation.TranslatedParagraphRequest
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

@Service
class TranslationWorkflowService(
    private val chapters: SourceChapterStore,
    private val jobs: TranslationJobStore,
    private val providers: WorkflowProviders,
    private val translator: WorkflowTranslator,
    private val translations: TranslationApplicationService,
    private val json: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val workerId = UUID.randomUUID()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val concurrent = Semaphore(2)
    private val running = ConcurrentHashMap<UUID, Job>()

    init {
        scope.launch {
            while (true) {
                delay(15_000)
                runCatching { jobs.recoverExpired() }
            }
        }
    }

    fun submit(user: String, request: CreateTranslationJobRequest): TranslationJobView {
        val chapter = chapters.get(user, request.chapterRecordId)
        val (configuration, secret) = providers.resolve(request, chapter)
        val hash = StableContentHash.sha256(json.writeValueAsString(listOf(chapter.recordId.toString(), chapter.sourceRevision, configuration)))
        jobs.recoverExpired()
        val (stored, created) = transactions.execute {
            jobs.lock(user, "submit")
            jobs.byIdempotency(user, request.idempotencyKey)?.let { previous ->
                if (previous.requestHash != hash) throw WorkflowFailure("IDEMPOTENCY_CONFLICT", 409, "같은 요청 ID에 다른 번역 설정이 사용되었습니다.")
                return@execute previous to false
            }
            jobs.reusable(user, hash)?.let {
                jobs.bindIdempotency(user, request.idempotencyKey, it.view.jobId)
                return@execute it to false
            }
            val active = jobs.countActive(user)
            if (active >= 4) throw WorkflowFailure("QUEUE_FULL", 429, "진행 중인 번역이 많습니다. 완료되거나 취소된 뒤 다시 시도해 주세요.")
            val retry = request.retryOf?.let { jobs.get(user, it) }
            if (retry != null && (!retry.view.canRetry || retry.requestHash != hash)) {
                throw WorkflowFailure("INVALID_RETRY", 409, "같은 원문과 번역 설정으로 중단된 작업만 이어서 번역할 수 있습니다.")
            }
            val next = jobs.create(user, request.idempotencyKey, hash, chapter, configuration, workerId)
            if (retry != null) jobs.copyCheckpoints(retry.view.jobId, next.view.jobId)
            jobs.get(user, next.view.jobId) to true
        }!!
        if (created) launch(stored, chapter, secret)
        return stored.view
    }

    fun get(user: String, id: UUID): TranslationJobView { jobs.recoverExpired(); return jobs.get(user, id).view }
    fun list(user: String, page: Int, size: Int): WorkflowPage<TranslationJobView> { jobs.recoverExpired(); return jobs.list(user, page, size) }
    fun cancel(user: String, id: UUID): TranslationJobView {
        val view = jobs.cancel(user, id)
        if (view.status == "CANCELLED") running[id]?.cancel()
        return view
    }

    private fun launch(stored: StoredJob, chapter: StoredChapter, secret: String) {
        val id = stored.view.jobId
        val worker = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineScope {
                    val parent = this
                    val heartbeat = launch {
                        while (true) {
                            delay(5_000)
                            if (!jobs.heartbeat(id, workerId)) parent.cancel("Job no longer active")
                        }
                    }
                    try {
                        concurrent.withPermit {
                            ensureActive()
                            if (!jobs.start(id, workerId)) return@withPermit
                            val config = stored.configuration
                            val content = chapter.content(config.sourceLanguage)
                            val result = translator.translate(content, config, secret, jobs.checkpoints(id)) { paragraphId, text ->
                                ensureActive()
                                if (text.isBlank() || content.paragraphs.none { it.paragraphId == paragraphId }) {
                                    throw IllegalStateException("Invalid provider paragraph")
                                }
                                if (!jobs.checkpoint(id, workerId, paragraphId, text)) throw CancellationException("Job no longer active")
                            }
                            ensureActive()
                            check(result.map { it.paragraphId } == content.paragraphs.map { it.paragraphId } && result.all { it.text.isNotBlank() }) {
                                "Provider did not return every source paragraph in order"
                            }
                            transactions.execute {
                                if (jobs.lockActive(stored.user, id, workerId)) {
                                    val saved = translations.save(stored.user, SaveTranslationRequest(
                                        chapter.providerId, chapter.bookId, chapter.chapterId, chapter.sourceRevision,
                                        config.sourceLanguage, config.targetLanguage, config.translationProviderId, config.model,
                                        config.promptRevision, config.glossaryRevision, result.map { TranslatedParagraphRequest(it.paragraphId, it.text) },
                                    ))
                                    jobs.complete(id, saved.recordId)
                                }
                            }
                        }
                    } finally { heartbeat.cancel() }
                }
            } catch (_: CancellationException) {
                // Cancel/restart status is committed by the caller or lease recovery; never publish partial artifacts.
            } catch (_: Exception) {
                // Provider responses and exception messages can contain credentials or private source text.
                runCatching { jobs.fail(id, workerId, "TRANSLATION_FAILED", "번역 제공자 응답 또는 연결을 확인하지 못했습니다. 설정을 확인한 뒤 완료된 문단부터 다시 시도해 주세요.") }
            } finally { running.remove(id) }
        }
        running[id] = worker
        worker.start()
    }

    @PreDestroy
    fun close() {
        runCatching { jobs.interruptOwned(workerId) }
        scope.cancel()
    }
}
