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
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/translations")
class TranslationController(
    private val service: TranslationApplicationService,
) {
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

    @PostMapping("/{recordId}/backup-plans")
    fun planBackup(
        principal: Principal,
        @PathVariable recordId: UUID,
        @Valid @RequestBody request: PlanBackupRequest,
    ): BackupPlanResponse = service.planBackup(principal.name, recordId, request)
}
