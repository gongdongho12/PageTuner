package com.dongholab.pagetuner.server.account

import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=account-bootstrap-test"])
@AutoConfigureMockMvc
class AccountIntegrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy { ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
            val container = PostgreSQLContainer<Nothing>("postgres:17-alpine"); container.start(); postgres = container
            ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
        } }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }; registry.add("spring.datasource.username") { database.user }
            registry.add("spring.datasource.password") { database.password }
        }
        @AfterAll @JvmStatic fun stop() { postgres?.stop() }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    private val password = "test-password-한국어"
    private fun username() = "reader-${UUID.randomUUID().toString().take(12)}"
    private data class Csrf(val session: MockHttpSession, val token: String)
    private fun csrf(): Csrf {
        val result = mvc.perform(get("/api/v1/accounts/csrf")).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andReturn()
        return Csrf(result.request.getSession(false) as MockHttpSession, json.readTree(result.response.contentAsString)["token"].asText())
    }
    private fun register(name: String, locale: String = "ko"): JsonNode {
        val token = csrf()
        val result = mvc.perform(post("/api/v1/accounts/register").session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content(json.writeValueAsString(RegisterAccountRequest(name, password, "A reader", locale, "ja"))))
            .andExpect(status().isCreated).andExpect(header().string("Cache-Control", "no-store")).andReturn()
        return json.readTree(result.response.contentAsString)
    }
    @Test fun `registration persists a password hash and authenticates the normalized username`() {
        val name = username()
        val profile = register(name.uppercase())
        assertEquals(name, profile["username"].asText())
        assertFalse(profile.has("password")); assertFalse(profile.has("passwordHash"))
        val hash = jdbc.queryForObject("select password_hash from reader_account where username=?", String::class.java, name)!!
        assertTrue(hash.startsWith("{bcrypt}")); assertFalse(hash.contains(password))
        mvc.perform(get("/api/v1/accounts/me").with(httpBasic(name, password))).andExpect(status().isOk)
            .andExpect(jsonPath("$.accountId").value(profile["accountId"].asText())).andExpect(jsonPath("$.targetLanguage").value("ja"))
        mvc.perform(get("/api/v1/accounts/me").with(httpBasic(name, "incorrect-password"))).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/accounts/me")).andExpect(status().isUnauthorized)
    }
    @Test fun `profile locale and translation language stay separate and unsupported packs fall back`() {
        val name = username(); register(name)
        val other = username(); register(other)
        val token = csrf()
        mvc.perform(patch("/api/v1/accounts/me").with(httpBasic(name, password)).session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content("""{"displayName":"New reader","locale":"zh-Hant","targetLanguage":"fr"}"""))
            .andExpect(status().isOk).andExpect(jsonPath("$.locale").value("zh-Hant"))
            .andExpect(jsonPath("$.effectiveLocale").value("en")).andExpect(jsonPath("$.targetLanguage").value("fr"))
        mvc.perform(get("/api/v1/accounts/me").with(httpBasic(other, password))).andExpect(jsonPath("$.locale").value("ko"))
            .andExpect(jsonPath("$.displayName").value("A reader")).andExpect(jsonPath("$.targetLanguage").value("ja"))
        mvc.perform(patch("/api/v1/accounts/me").with(httpBasic(name, password)).contentType("application/json").content("{}"))
            .andExpect(status().isForbidden)
    }
    @Test fun `registration requires csrf and enforces unique usernames and byte bounded passwords`() {
        val name = username(); register(name)
        mvc.perform(post("/api/v1/accounts/register").contentType("application/json").content("{}"))
            .andExpect(status().isForbidden)
        val token = csrf()
        mvc.perform(post("/api/v1/accounts/register").session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content(json.writeValueAsString(RegisterAccountRequest(name.uppercase(), password, "Duplicate"))))
            .andExpect(status().isConflict).andExpect(jsonPath("$.code").value("USERNAME_UNAVAILABLE"))
        val longPassword = "가".repeat(25)
        mvc.perform(post("/api/v1/accounts/register").session(token.session).header("X-CSRF-TOKEN", token.token)
            .contentType("application/json").content(json.writeValueAsString(RegisterAccountRequest(username(), longPassword, "Reader"))))
            .andExpect(status().isBadRequest)
    }
    @Test fun `language catalog is public and advertises available packs separately from future preferences`() {
        mvc.perform(get("/api/v1/accounts/languages")).andExpect(status().isOk).andExpect(jsonPath("$.defaultTag").value("ko"))
            .andExpect(jsonPath("$.items[0].available").value(true)).andExpect(jsonPath("$.items[2].available").value(false))
        assertEquals("pt-BR", LanguageCatalog.normalize("pt-br"))
        assertEquals("ko", LanguageCatalog.effective("ko-KR"))
        assertEquals("en", LanguageCatalog.effective("ar"))
        assertThrows(IllegalArgumentException::class.java) { LanguageCatalog.normalize("en<script>") }
    }
    @Test fun `local document upload preserves source IDs and reuses the same immutable revision`() {
        val name = username(); register(name)
        val token = csrf()
        val body = """{"bookId":"local-file-sha256","bookTitle":"Local book","chapterId":"chapter-1","chapterTitle":"Opening","sourceLanguage":"en","paragraphs":[{"paragraphId":"file:p0","ordinal":0,"text":"The garden was quiet."},{"paragraphId":"file:p1","ordinal":1,"text":"A reader opened the door."}]}"""
        fun upload() = mvc.perform(post("/api/v1/chapters/upload").with(httpBasic(name, password)).session(token.session)
            .header("X-CSRF-TOKEN", token.token).contentType("application/json").content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.providerId").value("uploaded-document"))
            .andExpect(jsonPath("$.paragraphs[0].paragraphId").value("file:p0")).andReturn()
        val first = json.readTree(upload().response.contentAsString)
        val repeated = json.readTree(upload().response.contentAsString)
        assertEquals(first["recordId"], repeated["recordId"]); assertEquals(first["sourceRevision"], repeated["sourceRevision"])
        mvc.perform(get("/api/v1/chapters/${first["recordId"].asText()}").with(httpBasic(name, password)))
            .andExpect(status().isOk).andExpect(jsonPath("$.paragraphs[1].ordinal").value(1))
    }
    @Test fun `abuse counters are bounded per address and successful normal traffic is permitted`() {
        val attempts = AccountAttempts()
        repeat(10) { assertTrue(attempts.register("address-a")) }
        assertFalse(attempts.register("address-a")); assertTrue(attempts.register("address-b"))
        repeat(20) { attempts.failed("address-a") }
        assertFalse(attempts.canAuthenticate("address-a")); assertTrue(attempts.canAuthenticate("address-b"))
    }
}
