package com.dongholab.pagetuner.server.account

import jakarta.servlet.http.HttpServletRequest
import java.security.Principal
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatusCode
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val accounts: AccountService, private val attempts: AccountAttempts) {
    @GetMapping("/languages")
    fun languages() = noStore(LanguageCatalog.list())
    @PostMapping("/register")
    fun register(@RequestBody request: RegisterAccountRequest, servlet: HttpServletRequest): ResponseEntity<AccountProfile> {
        if (!attempts.register(servlet.remoteAddr)) throw AccountFailure("REGISTRATION_LIMIT", 429, "Too many registration attempts. Try again later.")
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(accounts.register(request))
    }
    @GetMapping("/me")
    fun me(principal: Principal) = noStore(accounts.profile(principal.name))
    @PatchMapping("/me")
    fun update(principal: Principal, @RequestBody request: UpdateAccountRequest) = noStore(accounts.update(principal.name, request))
    @PostMapping("/me/password")
    fun changePassword(principal: Principal, @RequestBody request: ChangePasswordRequest): ResponseEntity<Void> {
        if (!attempts.changePassword(principal.name)) throw AccountFailure("PASSWORD_CHANGE_LIMIT", 429,
            "Too many password change attempts. Try again in 15 minutes.")
        accounts.changePassword(principal.name, request)
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
    }
    private fun <T> noStore(body: T) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)
}

@RestControllerAdvice
class AccountErrors {
    @ExceptionHandler(AccountFailure::class)
    fun failure(error: AccountFailure): ProblemDetail = ProblemDetail.forStatusAndDetail(HttpStatusCode.valueOf(error.status), error.message.orEmpty())
        .also { it.setProperty("code", error.code) }
}
