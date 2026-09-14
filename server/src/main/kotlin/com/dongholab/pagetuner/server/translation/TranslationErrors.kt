package com.dongholab.pagetuner.server.translation

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@ResponseStatus(HttpStatus.NOT_FOUND)
class TranslationNotFound : RuntimeException("Translation artifact was not found.")

class TranslationMetadataUnavailable : RuntimeException(
    "Original book metadata is unavailable for this legacy record. Upload the original translation again to restore it.",
)

class TranslationIdentityConflict : RuntimeException(
    "A translation with this identity and revision already exists with different metadata.",
)

@RestControllerAdvice
class TranslationErrors {
    @ExceptionHandler(TranslationMetadataUnavailable::class, TranslationIdentityConflict::class)
    fun conflictingMetadata(error: RuntimeException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, requireNotNull(error.message))

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidContent(error: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.message ?: "Invalid content.")
}
