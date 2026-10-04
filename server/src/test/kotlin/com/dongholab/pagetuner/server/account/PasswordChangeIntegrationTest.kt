package com.dongholab.pagetuner.server.account

import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextImpl
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.web.context.HttpSessionSecurityContextRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.name=password-bootstrap-reader", "spring.security.user.password=legacy"])
@AutoConfigureMockMvc
class PasswordChangeIntegrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy { ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
            val container = PostgreSQLContainer<Nothing>("postgres:17-alpine"); container.start(); postgres = container
            ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
        } }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.datasource.username") { database.user }
            registry.add("spring.datasource.password") { database.password }
        }
        @AfterAll @JvmStatic fun stop() { postgres?.stop() }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var accounts: AccountService
    private val createdUsers = mutableListOf<String>()
    private val oldPassword = " original-password-한국어 "
    private val newPassword = " replacement-password-日本語 "
    private data class Csrf(val session: MockHttpSession, val token: String)
    private fun csrf(): Csrf {
        val result = mvc.perform(get("/api/v1/accounts/csrf")).andExpect(status().isOk).andReturn()
        return Csrf(result.request.getSession(false) as MockHttpSession, json.readTree(result.response.contentAsString)["token"].asText())
    }
    private fun account(): AccountProfile {
        val name = "password-${UUID.randomUUID().toString().take(12)}"
        createdUsers += name
        return accounts.register(RegisterAccountRequest(name, oldPassword, "A reader", "zh-Hant", "fr"))
    }
    private fun change(name: String, current: String, replacement: String, auth: String = oldPassword, token: Csrf = csrf()) =
        mvc.perform(post("/api/v1/accounts/me/password").servletPath("/api/v1/accounts/me/password")
            .with(httpBasic(name, auth)).session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content(json.writeValueAsString(ChangePasswordRequest(current, replacement))))
    private fun hash(name: String) = jdbc.queryForObject("select password_hash from reader_account where username=?", String::class.java, name)!!

    @AfterEach fun cleanupAccounts() {
        createdUsers.forEach { jdbc.update("delete from reader_account where username=?", it) }
    }

    @Test fun `password change preserves account and library and rejects old credentials despite session authentication`() {
        val profile = account()
        val before = hash(profile.username)
        val token = csrf()
        val upload = mvc.perform(post("/api/v1/chapters/upload").with(httpBasic(profile.username, oldPassword))
            .session(token.session).header("X-CSRF-TOKEN", token.token).contentType("application/json")
            .content("""{"bookId":"password-test","bookTitle":"Retained book","chapterId":"chapter-1","chapterTitle":"Opening","sourceLanguage":"en","paragraphs":[{"paragraphId":"p0","ordinal":0,"text":"A retained paragraph."}]}"""))
            .andExpect(status().isOk).andReturn()
        val record = json.readTree(upload.response.contentAsString)["recordId"].asText()
        // A cookie from an older deployment must never turn into reusable authentication.
        token.session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
            SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(profile.username, oldPassword, emptyList())))
        change(profile.username, oldPassword, newPassword, token = token).andExpect(status().isNoContent)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().string(""))
        assertNotEquals(before, hash(profile.username))
        assertFalse(hash(profile.username).contains(newPassword))
        mvc.perform(get("/api/v1/accounts/me").session(token.session).with(httpBasic(profile.username, oldPassword)))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/accounts/me").session(token.session)).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/accounts/me").session(token.session).with(httpBasic(profile.username, newPassword.trim())))
            .andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/accounts/me").session(token.session).with(httpBasic(profile.username, newPassword)))
            .andExpect(status().isOk).andExpect(jsonPath("$.accountId").value(profile.accountId.toString()))
            .andExpect(jsonPath("$.displayName").value(profile.displayName)).andExpect(jsonPath("$.locale").value("zh-Hant"))
            .andExpect(jsonPath("$.targetLanguage").value("fr"))
        mvc.perform(get("/api/v1/chapters/$record").session(token.session).with(httpBasic(profile.username, newPassword)))
            .andExpect(status().isOk).andExpect(jsonPath("$.paragraphs[0].text").value("A retained paragraph."))
    }

    @Test fun `password change requires both authentication and csrf and applies its own small body limit`() {
        val name = account().username
        val token = csrf()
        val body = json.writeValueAsString(ChangePasswordRequest(oldPassword, newPassword))
        mvc.perform(post("/api/v1/accounts/me/password").with(httpBasic(name, oldPassword)).contentType("application/json").content(body))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/accounts/me/password").session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content(body)).andExpect(status().isUnauthorized)
        change(name, oldPassword, "x".repeat(4100), token = token).andExpect(status().isPayloadTooLarge)
        change(name, oldPassword, newPassword, token = token).andExpect(status().isNoContent)
    }

    @Test fun `wrong current and invalid replacements have stable errors and five attempts are account scoped`() {
        val name = account().username
        val before = hash(name)
        val cases = listOf(
            Triple(oldPassword.trim(), newPassword, "CURRENT_PASSWORD_INCORRECT"),
            Triple(oldPassword, oldPassword, "PASSWORD_UNCHANGED"),
            Triple(oldPassword, "😀".repeat(9), "INVALID_PASSWORD"),
            Triple(oldPassword, "가".repeat(25), "INVALID_PASSWORD"),
            Triple(oldPassword, "long-password\n", "INVALID_PASSWORD"),
        )
        cases.forEach { (current, next, code) ->
            val result = change(name, current, next).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value(code)).andReturn()
            assertFalse(result.response.contentAsString.contains(current))
            assertFalse(result.response.contentAsString.contains(next))
            assertEquals(before, hash(name))
        }
        change(name, oldPassword, newPassword).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("PASSWORD_CHANGE_LIMIT"))
        assertEquals(before, hash(name))
        change(account().username, oldPassword, "가".repeat(24)).andExpect(status().isNoContent)
        assertEquals("ChangePasswordRequest(credentials=REDACTED)", ChangePasswordRequest(oldPassword, newPassword).toString())
    }

    @Test fun `legacy bootstrap password can change and future bootstrap lookup cannot reset it`() {
        val name = "password-bootstrap-reader"
        jdbc.update("delete from reader_account where username=?", name)
        createdUsers += name
        accounts.loadUserByUsername(name)
        val id = accounts.profile(name).accountId
        change(name, "legacy", newPassword, auth = "legacy").andExpect(status().isNoContent)
        val restartedService = AccountService(jdbc, name, "legacy")
        val stored = restartedService.loadUserByUsername(name)
        assertTrue(BCryptPasswordEncoder().matches(newPassword, stored.password.removePrefix("{bcrypt}")))
        assertEquals(id, accounts.profile(name).accountId)
        mvc.perform(get("/api/v1/accounts/me").with(httpBasic(name, "legacy"))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/accounts/me").with(httpBasic(name, newPassword))).andExpect(status().isOk)
    }

    @Test fun `concurrent password replacements cannot overwrite a newer hash`() {
        val name = account().username
        val ready = CountDownLatch(2)
        val gatedJdbc = object : JdbcTemplate(requireNotNull(jdbc.dataSource)) {
            override fun update(sql: String, vararg args: Any?): Int {
                if (sql.startsWith("update reader_account set password_hash=")) {
                    ready.countDown()
                    check(ready.await(10, TimeUnit.SECONDS)) { "Both concurrent requests must reach their atomic update." }
                }
                return super.update(sql, *args)
            }
        }
        val service = AccountService(gatedJdbc, "unused", "")
        val replacements = listOf("first-new-password", "second-new-password")
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = replacements.map { replacement -> pool.submit<String> {
                try { service.changePassword(name, ChangePasswordRequest(oldPassword, replacement)); "CHANGED" }
                catch (failure: AccountFailure) { failure.code }
            } }.map { it.get(20, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it == "CHANGED" })
            assertEquals(1, results.count { it == "PASSWORD_CHANGE_CONFLICT" })
            val finalHash = hash(name).removePrefix("{bcrypt}")
            assertEquals(1, replacements.count { BCryptPasswordEncoder().matches(it, finalHash) })
            assertFalse(BCryptPasswordEncoder().matches(oldPassword, finalHash))
        } finally { pool.shutdownNow() }
    }
}
