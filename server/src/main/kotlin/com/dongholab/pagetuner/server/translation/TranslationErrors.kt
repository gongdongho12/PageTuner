package com.dongholab.pagetuner.server.translation

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

@ResponseStatus(HttpStatus.NOT_FOUND)
class TranslationNotFound : RuntimeException("Translation artifact was not found.")

@RestControllerAdvice
class TranslationErrors {
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidContent(error: IllegalArgumentException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, error.message ?: "Invalid content.")
}
