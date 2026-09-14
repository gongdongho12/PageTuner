package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.source.webnovel.WebNovelAuthenticationRequiredException
import java.io.IOException
import kotlinx.coroutines.TimeoutCancellationException
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

@RestControllerAdvice(assignableTypes = [NovelController::class, com.dongholab.pagetuner.server.workflow.WorkflowController::class])
class NovelErrors {
    @ExceptionHandler(WebNovelAuthenticationRequiredException::class)
    fun authenticationRequired(): ProblemDetail = problem(HttpStatus.BAD_GATEWAY,
        "This chapter requires an account on the source website; server import cannot complete it.", "source_auth_required")

    @ExceptionHandler(NovelProviderFailure::class)
    fun providerFailure(error: NovelProviderFailure): ProblemDetail = problem(HttpStatus.BAD_GATEWAY,
        error.message.orEmpty(), if (error.status == 429 || error.status == 503) "source_throttled" else "source_unavailable")

    @ExceptionHandler(TimeoutCancellationException::class)
    fun timeout(): ProblemDetail = problem(HttpStatus.GATEWAY_TIMEOUT,
        "The source website did not respond within the allowed time.", "source_timeout")

    @ExceptionHandler(IOException::class)
    fun unavailable(): ProblemDetail = problem(HttpStatus.BAD_GATEWAY,
        "The source website could not provide complete readable data. Please retry later.", "source_unavailable")

    private fun problem(status: HttpStatus, detail: String, code: String) =
        ProblemDetail.forStatusAndDetail(status, detail).apply { setProperty("code", code) }
}
