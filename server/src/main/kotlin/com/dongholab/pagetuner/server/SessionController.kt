package com.dongholab.pagetuner.server

import java.security.Principal
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Compatibility account check. Basic credentials are revalidated on every request. */
@RestController
class SessionController {
    @GetMapping("/api/v1/session")
    fun session(principal: Principal): ResponseEntity<Map<String, String>> = ResponseEntity.ok()
        .cacheControl(CacheControl.noStore()).body(mapOf("username" to principal.name))
}
