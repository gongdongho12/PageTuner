package com.dongholab.pagetuner.server.catalogTranslation

import java.security.Principal
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/catalog-translations")
class CatalogTranslationController(private val jobs: CatalogTranslationJobs) {
    @PostMapping fun start(principal: Principal, @RequestBody request: CatalogTranslationRequest) = response(jobs.start(principal.name, request))
    @GetMapping("/{id}") fun get(principal: Principal, @PathVariable id: UUID) = response(jobs.get(principal.name, id))
    @PostMapping("/{id}/cancel") fun cancel(principal: Principal, @PathVariable id: UUID) = response(jobs.cancel(principal.name, id))
    private fun response(value: CatalogTranslationView) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value)
}
