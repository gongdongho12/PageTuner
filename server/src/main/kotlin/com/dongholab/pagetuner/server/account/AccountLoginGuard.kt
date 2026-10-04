package com.dongholab.pagetuner.server.account

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

class AccountLoginGuard(private val attempts: AccountAttempts) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val basic = request.getHeader("Authorization")?.startsWith("Basic ", ignoreCase = true) == true
        if (basic && !attempts.canAuthenticate(request.remoteAddr)) {
            response.status = 429
            response.contentType = "application/problem+json"
            response.setHeader("Cache-Control", "no-store")
            response.setHeader("Retry-After", "900")
            response.writer.write("""{"type":"about:blank","title":"Too Many Requests","status":429,"code":"LOGIN_LIMIT","detail":"Too many failed sign-in attempts. Try again later."}""")
            return
        }
        chain.doFilter(request, response)
        if (basic && response.status == 401) attempts.failed(request.remoteAddr)
    }
}
