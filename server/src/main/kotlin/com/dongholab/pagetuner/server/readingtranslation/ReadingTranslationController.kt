package com.dongholab.pagetuner.server.readingtranslation

import java.security.Principal
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/reading-translations")
class ReadingTranslationController(private val jobs: ReadingTranslationJobs) {
    @PostMapping fun start(principal: Principal, @RequestBody request: ReadingTranslationRequest) = response(jobs.start(principal.name, request))
    @GetMapping("/{id}") fun get(principal: Principal, @PathVariable id: UUID) = response(jobs.get(principal.name, id))
    @PostMapping("/{id}/cancel") fun cancel(principal: Principal, @PathVariable id: UUID) = response(jobs.cancel(principal.name, id))
    private fun response(view: ReadingTranslationView) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(view)
}
