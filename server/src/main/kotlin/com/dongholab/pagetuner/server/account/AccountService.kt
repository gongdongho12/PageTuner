package com.dongholab.pagetuner.server.account

import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.security.core.userdetails.User
import org.springframework.security.core.userdetails.UserDetails
import org.springframework.security.core.userdetails.UserDetailsService
import org.springframework.security.core.userdetails.UsernameNotFoundException
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** Existing local-reader ownership is preserved while new accounts use durable credentials. */
@Service
class AccountService(
    private val jdbc: JdbcTemplate,
    @param:Value("\${spring.security.user.name:local-reader}") private val bootstrapUsername: String,
    @param:Value("\${spring.security.user.password:}") private val bootstrapPassword: String,
) : UserDetailsService {
    private val encoder = BCryptPasswordEncoder(12)
    private val bootstrapHash by lazy { "{bcrypt}" + encoder.encode(bootstrapPassword) }
    private data class StoredAccount(val profile: AccountProfile, val passwordHash: String)
    private val mapper = RowMapper { row, _ -> StoredAccount(
        AccountProfile(row.getObject("id", UUID::class.java), row.getString("username"), row.getString("display_name"),
            row.getString("locale"), row.getString("target_language")), row.getString("password_hash")) }

    @Transactional
    fun register(request: RegisterAccountRequest): AccountProfile {
        val username = request.username.trim().lowercase(java.util.Locale.ROOT)
        require(username.length in 3..40 && username.matches(Regex("[a-z0-9][a-z0-9_.-]*"))) { "Username must be 3–40 lowercase letters, numbers, dots, underscores or hyphens." }
        val profile = validateProfile(UpdateAccountRequest(request.displayName, request.locale, request.targetLanguage))
        require(request.password.codePointCount(0, request.password.length) >= 10 && request.password.toByteArray(Charsets.UTF_8).size <= 72 && request.password.none(Char::isISOControl)) {
            "Password must contain at least 10 characters, fit 72 UTF-8 bytes, and contain no control characters."
        }
        if (bootstrapPassword.isNotBlank() && username == bootstrapUsername.lowercase(java.util.Locale.ROOT)) duplicate()
        val id = UUID.randomUUID()
        val changed = jdbc.update("""insert into reader_account(id,username,password_hash,display_name,locale,target_language)
            values(?,?,?,?,?,?) on conflict(username) do nothing""", id, username, "{bcrypt}" + encoder.encode(request.password),
            profile.displayName, profile.locale, profile.targetLanguage)
        if (changed != 1) duplicate()
        return AccountProfile(id, username, profile.displayName, profile.locale, profile.targetLanguage)
    }

    @Transactional
    override fun loadUserByUsername(username: String): UserDetails {
        val canonical = username.trim().lowercase(java.util.Locale.ROOT)
        var account = find(canonical)
        if (account == null && bootstrapPassword.isNotBlank() && canonical == bootstrapUsername.lowercase(java.util.Locale.ROOT)) {
            jdbc.update("""insert into reader_account(id,username,password_hash,display_name,locale,target_language)
                values(?,?,?,?, 'ko','ko') on conflict(username) do nothing""", UUID.randomUUID(), canonical, bootstrapHash, canonical.take(80))
            account = find(canonical)
        }
        val stored = account ?: throw UsernameNotFoundException("Invalid credentials.")
        return User.withUsername(stored.profile.username).password(stored.passwordHash).roles("READER").build()
    }

    @Transactional(readOnly = true)
    fun profile(username: String): AccountProfile = find(username)?.profile ?: throw AccountFailure("ACCOUNT_NOT_FOUND", 404, "Account not found.")

    @Transactional
    fun update(username: String, request: UpdateAccountRequest): AccountProfile {
        val valid = validateProfile(request)
        val changed = jdbc.update("update reader_account set display_name=?,locale=?,target_language=?,updated_at=now() where username=?",
            valid.displayName, valid.locale, valid.targetLanguage, username)
        if (changed != 1) throw AccountFailure("ACCOUNT_NOT_FOUND", 404, "Account not found.")
        return profile(username)
    }

    private fun validateProfile(request: UpdateAccountRequest): UpdateAccountRequest {
        val name = request.displayName.trim()
        require(name.length in 1..80 && name.none(Char::isISOControl)) { "Display name must contain 1–80 characters without control characters." }
        val locale = LanguageCatalog.normalize(request.locale)
        require(!request.targetLanguage.trim().equals("auto", ignoreCase = true)) { "Choose a concrete translation target language." }
        return UpdateAccountRequest(name, locale, LanguageCatalog.normalize(request.targetLanguage, 24))
    }
    private fun find(username: String): StoredAccount? = jdbc.query("select * from reader_account where username=?", mapper, username).singleOrNull()
    private fun duplicate(): Nothing = throw AccountFailure("USERNAME_UNAVAILABLE", 409, "This username is unavailable.")
}
