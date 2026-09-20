package com.dongholab.pagetuner.server.translation

import jakarta.validation.Valid
import java.util.UUID
import java.security.Principal
import org.springframework.http.ResponseEntity
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/translations")
class TranslationController(
    private val service: TranslationApplicationService,
) {
    @GetMapping
    fun list(principal: Principal, @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int, @RequestParam(required = false) contentProviderId: String?,
        @RequestParam(required = false) bookId: String?, @RequestParam(required = false) chapterId: String?,
        @RequestParam(required = false) sourceRevision: String?, @RequestParam(required = false) targetLanguage: String?) =
        service.list(principal.name, page, size, contentProviderId, bookId, chapterId, sourceRevision, targetLanguage)

    @PostMapping
    fun save(
        principal: Principal,
        @Valid @RequestBody request: SaveTranslationRequest,
    ): ResponseEntity<TranslationResponse> {
        val result = service.save(principal.name, request)
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }

    @GetMapping("/{recordId}")
    fun get(
        principal: Principal,
        @PathVariable recordId: UUID,
    ): TranslationResponse = service.get(principal.name, recordId)

    @GetMapping("/{recordId}/backup")
    fun exportBackup(principal: Principal, @PathVariable recordId: UUID): ResponseEntity<TranslationBackupDocument> =
        ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_JSON)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=translation-$recordId.json")
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .body(service.exportBackup(principal.name, recordId))

    @PostMapping("/restore")
    fun restore(
        principal: Principal,
        @Valid @RequestBody document: TranslationBackupDocument,
    ): ResponseEntity<TranslationResponse> {
        val result = service.save(principal.name, document.verifiedRequest())
        return ResponseEntity.status(if (result.created) HttpStatus.CREATED else HttpStatus.OK).body(result)
    }

    @PostMapping("/{recordId}/backup-plans")
    fun planBackup(
        principal: Principal,
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: PlanBackupRequest,
    ): BackupPlanResponse = service.planBackup(principal.name, recordId, request)
}
