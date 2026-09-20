package com.dongholab.pagetuner.server

import java.security.Principal
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SessionController {
    @GetMapping("/api/v1/session")
    fun session(principal: Principal): Map<String, String> = mapOf("username" to principal.name)

    /** Accessing the deferred token creates the session used by subsequent mutation requests. */
    @GetMapping("/api/v1/csrf")
    fun csrf(token: CsrfToken): Map<String, String> =
        mapOf("headerName" to token.headerName, "token" to token.token)
}
