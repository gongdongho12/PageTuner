package com.dongholab.pagetuner.server.pdf

import com.dongholab.pagetuner.core.backup.exchange.PdfContentValidation
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.Principal
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatusCode
import org.springframework.http.MediaType
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/pdf-content")
class PdfContentController(private val service: PdfContentService, json: ObjectMapper) {
    private val codec = PdfContentJson(json)
    @PostMapping(consumes = ["application/json"])
    fun upload(principal: Principal, @RequestBody bytes: ByteArray): ResponseEntity<PdfContentReceipt> {
        if (bytes.size > 8 * 1024 * 1024) tooLarge()
        return noStore(service.upload(principal.name, parse { codec.upload(bytes) }))
    }
    @GetMapping("/{recordId}")
    fun get(principal: Principal, @PathVariable recordId: String) = noStore(service.get(principal.name, id(recordId)))

    @PostMapping("/{recordId}/verify", consumes = ["application/json"])
    fun verify(principal: Principal, @PathVariable recordId: String, @RequestBody bytes: ByteArray): ResponseEntity<PdfContentVerification> {
        if (bytes.size > 2 * 1024 * 1024) tooLarge()
        return noStore(service.verify(principal.name, id(recordId), parse { codec.verification(bytes) }))
    }
    @GetMapping("/{recordId}/original")
    fun original(principal: Principal, @PathVariable recordId: String): ResponseEntity<ByteArray> {
        val record = id(recordId)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.APPLICATION_PDF)
            .header("Content-Disposition", "attachment; filename=\"$record.pdf\"")
            .header("X-Content-Type-Options", "nosniff").body(service.original(principal.name, record))
    }
    private fun id(value: String): UUID = parse { PdfContentValidation.validateUuid(value); UUID.fromString(value) }
    private fun <T> parse(action: () -> T): T = try { action() }
        catch (_: IllegalArgumentException) { invalid() }
        catch (_: com.fasterxml.jackson.core.JsonProcessingException) { invalid() }
    private fun invalid(): Nothing = throw PdfContentFailure("PDF_CONTENT_INVALID", 400, "Invalid PDF content request.")
    private fun tooLarge(): Nothing = throw PdfContentFailure("PDF_CONTENT_TOO_LARGE", 413, "The PDF content request exceeds the size limit.")
    private fun <T> noStore(value: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
    @ExceptionHandler(PdfContentFailure::class)
    fun failure(error: PdfContentFailure): ResponseEntity<ProblemDetail> {
        val detail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        detail.setProperty("code", error.code)
        return ResponseEntity.status(error.status).cacheControl(CacheControl.noStore()).body(detail)
    }
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformed() = failure(PdfContentFailure("PDF_CONTENT_INVALID", 400, "Invalid PDF content request."))
}
