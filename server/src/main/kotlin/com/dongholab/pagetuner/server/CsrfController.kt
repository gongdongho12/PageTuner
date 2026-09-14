package com.dongholab.pagetuner.server

import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class CsrfResponse(val headerName: String, val token: String)

@RestController
class CsrfController {
    @GetMapping("/api/v1/csrf", "/api/v1/accounts/csrf")
    fun csrf(token: CsrfToken): ResponseEntity<CsrfResponse> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(CsrfResponse(token.headerName, token.token))
}
