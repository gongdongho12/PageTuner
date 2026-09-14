package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.source.service.NovelSourceService
import java.security.Principal
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.springframework.http.CacheControl
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestController
@RequestMapping("/api/v1")
class WorkflowController(
    private val sources: NovelSourceService,
    private val chapters: SourceChapterStore,
    private val providers: WorkflowProviders,
    private val workflow: TranslationWorkflowService,
) {
    @PostMapping("/chapters/import")
    fun importChapter(principal: Principal, @RequestBody request: ImportChapterRequest) = noStore(runBlocking {
        chapters.save(principal.name, sources.fetchChapter(request.url, request.bookUrl))
    })
    @PostMapping("/chapters/upload")
    fun uploadChapter(principal: Principal, @RequestBody request: UploadedChapterRequest) = noStore(chapters.upload(principal.name, request))
    @GetMapping("/chapters")
    fun chapters(principal: Principal, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "12") size: Int) =
        noStore(chapters.list(principal.name, page, size))
    @GetMapping("/chapters/{recordId}")
    fun chapter(principal: Principal, @PathVariable recordId: UUID) = noStore(chapters.get(principal.name, recordId))
    @GetMapping("/translation-providers")
    fun providers() = noStore(providers.list())
    @PostMapping("/translation-jobs")
    fun start(principal: Principal, @RequestBody request: CreateTranslationJobRequest) = noStore(workflow.submit(principal.name, request))
    @GetMapping("/translation-jobs")
    fun jobs(principal: Principal, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "12") size: Int) = noStore(workflow.list(principal.name, page, size))
    @GetMapping("/translation-jobs/{jobId}")
    fun job(principal: Principal, @PathVariable jobId: UUID) = noStore(workflow.get(principal.name, jobId))
    @PostMapping("/translation-jobs/{jobId}/cancel")
    fun cancel(principal: Principal, @PathVariable jobId: UUID) = noStore(workflow.cancel(principal.name, jobId))
    private fun <T> noStore(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

@RestControllerAdvice
class WorkflowErrors {
    @ExceptionHandler(WorkflowFailure::class)
    fun failure(error: WorkflowFailure): ProblemDetail = ProblemDetail.forStatusAndDetail(
        org.springframework.http.HttpStatusCode.valueOf(error.httpStatus), error.message.orEmpty(),
    ).also { it.setProperty("code", error.code) }
}
