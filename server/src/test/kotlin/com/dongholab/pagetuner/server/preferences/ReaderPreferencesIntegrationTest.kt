package com.dongholab.pagetuner.server.preferences

import com.dongholab.pagetuner.server.ApiRequestBodyLimit
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=reader-preferences-test-password"])
@AutoConfigureMockMvc
class ReaderPreferencesIntegrationTest {
    companion object {
        private var postgres: PostgreSQLContainer<Nothing>? = null
        private val database by lazy {
            ExternalPostgresTestDatabase.fromEnvironment(System.getenv()) ?: run {
                val container = PostgreSQLContainer<Nothing>("postgres:17-alpine")
                container.start(); postgres = container
                ExternalPostgresTestDatabase(container.jdbcUrl, container.username, container.password)
            }
        }
        @DynamicPropertySource @JvmStatic fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { database.url }
            registry.add("spring.datasource.username") { database.user }
            registry.add("spring.datasource.password") { database.password }
        }
        @AfterAll @JvmStatic fun stop() { postgres?.stop() }
        private const val PATH = "/api/v1/reader-preferences"
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var preferences: ReaderPreferencesService

    private fun reader() = "preferences-${UUID.randomUUID()}"
    private fun value(fontSize: Int = 20) = ReaderPreferences(fontSize, 195, 28, "left-previous", "paged")
    private fun request(version: Long = 0, value: ReaderPreferences = value(), mutation: UUID = UUID.randomUUID()) =
        PutReaderPreferencesRequest(version, mutation, value)
    private fun write(owner: String, body: String) = mvc.perform(put(PATH).with(user(owner)).with(csrf())
        .contentType("application/json").content(body))

    @Test fun `first reads expose null preferences without creating defaults and round trip explicit adoption`() {
        val owner = reader()
        repeat(2) {
            mvc.perform(get(PATH).with(user(owner))).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("""{"version":0,"preferences":null,"updatedAt":null}""", JsonCompareMode.STRICT))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from reader_preferences where user_id=?", Long::class.java, owner)!!)
        val saved = write(owner, json.writeValueAsString(request())).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.preferences.fontSize").value(20))
            .andExpect(jsonPath("$.preferences.lineHeightPercent").value(195)).andExpect(jsonPath("$.updatedAt").isString)
            .andReturn().response.contentAsString
        val read = mvc.perform(get(PATH).with(user(owner))).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(json.readTree(saved), json.readTree(read))
    }

    @Test fun `preferences and mutation identities are isolated by authenticated account`() {
        val owner = reader(); val other = reader(); val sharedMutation = UUID.randomUUID()
        val first = preferences.put(owner, request(value = value(18), mutation = sharedMutation))
        mvc.perform(get(PATH).with(user(other))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(0))
        val second = preferences.put(other, request(value = value(36), mutation = sharedMutation))
        assertEquals(1, second.version)
        assertEquals(first, preferences.get(owner))
        mvc.perform(get(PATH).with(user(other))).andExpect(status().isOk).andExpect(jsonPath("$.preferences.fontSize").value(36))
    }

    @Test fun `latest exact retries return stored timestamp while reused mutation rejects differing values`() {
        val owner = reader(); val request = request()
        val first = write(owner, json.writeValueAsString(request)).andExpect(status().isOk).andReturn().response.contentAsString
        val retry = write(owner, json.writeValueAsString(request)).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(first, retry)
        for (changed in listOf(request.copy(expectedVersion = 1), request.copy(preferences = value(21)))) {
            write(owner, json.writeValueAsString(changed)).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("READER_PREFERENCES_MUTATION_REUSED"))
        }
        assertEquals(1, preferences.get(owner).version)
    }

    @Test fun `stale edits include current view and require explicit resolution against newer version`() {
        val owner = reader(); val first = request()
        preferences.put(owner, first)
        write(owner, json.writeValueAsString(request(value = value(21)))).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("READER_PREFERENCES_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(1)).andExpect(jsonPath("$.current.preferences.fontSize").value(20))
        val resolved = preferences.put(owner, request(1, value(21)))
        assertEquals(2, resolved.version)
        write(owner, json.writeValueAsString(first)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.current.version").value(2)).andExpect(jsonPath("$.current.preferences.fontSize").value(21))
        assertEquals(resolved, preferences.get(owner))
    }

    @Test fun `stale initial adoption preserves its null current view`() {
        val owner = reader()
        write(owner, json.writeValueAsString(request(version = 1))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("READER_PREFERENCES_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(0))
            .andExpect(jsonPath("$.current.preferences").isEmpty).andExpect(jsonPath("$.current.updatedAt").isEmpty)
        assertEquals(ReaderPreferencesView(0, null, null), preferences.get(owner))
    }

    @Test fun `concurrent first writes and updates have exactly one winner`() {
        val owner = reader()
        for (version in 0L..1L) {
            val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val go = CountDownLatch(1)
            try {
                val outcomes = (0..5).map { index -> pool.submit(Callable {
                    ready.countDown(); check(go.await(10, TimeUnit.SECONDS))
                    try { preferences.put(owner, request(version, value(20 + index))); "ok" }
                    catch (error: ReaderPreferencesFailure) { error.code }
                }) }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
                val values = outcomes.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, values.count { it == "ok" })
                assertEquals(5, values.count { it == "READER_PREFERENCES_CONFLICT" })
                assertEquals(version + 1, preferences.get(owner).version)
            } finally { go.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `authentication and csrf are required on singleton endpoint`() {
        val owner = reader(); val body = json.writeValueAsString(request())
        mvc.perform(get(PATH)).andExpect(status().isUnauthorized)
        mvc.perform(put(PATH).with(user(owner)).contentType("application/json").content(body)).andExpect(status().isForbidden)
        mvc.perform(put(PATH).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized)
        assertEquals(0, preferences.get(owner).version)
    }

    @Test fun `portable ranges and all directions are accepted without altering stored values`() {
        val owner = reader()
        val values = listOf(ReaderPreferences(14, 110, 0, "left-previous", "paged"),
            ReaderPreferences(36, 240, 48, "left-next", "scroll"), value().copy(touchDirection = "buttons-only"))
        values.forEachIndexed { index, value ->
            val saved = preferences.put(owner, request(index.toLong(), value))
            assertEquals(value, saved.preferences)
        }
    }

    @Test fun `invalid settings are rejected through HTTP and direct service calls`() {
        val owner = reader(); val good = value()
        val invalid = listOf(good.copy(fontSize = 13), good.copy(fontSize = 37), good.copy(lineHeightPercent = 109),
            good.copy(lineHeightPercent = 241), good.copy(pageMargin = -1), good.copy(pageMargin = 49),
            good.copy(touchDirection = "LEFT_PREVIOUS"), good.copy(touchDirection = ""), good.copy(listMode = "Paged"))
        for (value in invalid) {
            write(owner, json.writeValueAsString(request(value = value))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("READER_PREFERENCES_INVALID"))
            assertEquals("READER_PREFERENCES_INVALID", assertThrows(ReaderPreferencesFailure::class.java) {
                preferences.put(owner, request(value = value))
            }.code)
        }
        assertEquals(0, preferences.get(owner).version)
    }

    @Test fun `JSON rejects missing unknown duplicate coerced fractional and trailing data`() {
        val owner = reader(); val good = json.writeValueAsString(request())
        val invalid = listOf("{}", "null", "[]", good + "{}", good.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9223372036854775808"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"),
            good.replace("\"expectedVersion\":0", "\"unknown\":true,\"expectedVersion\":0"),
            good.replace("\"fontSize\":20", "\"fontSize\":20.0"),
            good.replace("\"fontSize\":20", "\"fontSize\":\"20\""),
            good.replace("\"fontSize\":20", "\"fontSize\":null"),
            good.replace("\"fontSize\":20", "\"fontSize\":2147483648"),
            good.replace("\"fontSize\":20", "\"fontSize\":20,\"fontSize\":20"),
            good.replace("\"fontSize\":20", "\"fontSize\":20,\"fontFamily\":\"serif\""),
            good.replace("\"fontSize\":20,", ""),
            good.replace("\"lineHeightPercent\":195", "\"lineHeightPercent\":195.5"),
            good.replace("\"pageMargin\":28", "\"pageMargin\":true"),
            good.replace("\"touchDirection\":\"left-previous\"", "\"touchDirection\":null"),
            good.replace("\"listMode\":\"paged\"", "\"listMode\":[]"),
            good.replace(Regex("\\{\"fontSize\".*}"), "null}"),
            good.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "1-1-1-1-1"))
        for (body in invalid) write(owner, body).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READER_PREFERENCES_INVALID"))
        assertEquals(0, preferences.get(owner).version)
    }

    @Test fun `eight KiB body bound also covers requests without content length`() {
        val owner = reader(); val body = " ".repeat(8193)
        mvc.perform(put(PATH).servletPath(PATH).with(user(owner)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isPayloadTooLarge).andExpect(header().string("Cache-Control", "no-store"))
        val unknownLength = object : MockHttpServletRequest() {
            override fun getContentLength(): Int = -1
            override fun getContentLengthLong(): Long = -1
        }.apply { method = "PUT"; servletPath = PATH; setContent(body.toByteArray()) }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(unknownLength, response) { _, _ -> fail<Unit>("Oversized chunked body reached JSON binding.") }
        assertEquals(413, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(0, preferences.get(owner).version)
    }

    @Test fun `version exhaustion is terminal while latest successful request remains retryable`() {
        val owner = reader(); preferences.put(owner, request())
        jdbc.update("update reader_preferences set version=?,expected_version=? where user_id=?",
            MAX_READER_PREFERENCES_VERSION - 1, MAX_READER_PREFERENCES_VERSION - 2, owner)
        val finalRequest = request(MAX_READER_PREFERENCES_VERSION - 1, value(22))
        val saved = preferences.put(owner, finalRequest)
        assertEquals(MAX_READER_PREFERENCES_VERSION, saved.version)
        assertEquals(saved, preferences.put(owner, finalRequest))
        write(owner, json.writeValueAsString(request())).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("READER_PREFERENCES_EXHAUSTED"))
            .andExpect(jsonPath("$.current").doesNotExist())
        assertEquals(saved, preferences.get(owner))
    }

    @Test fun `write limits provide retry after while reads and other accounts remain available`() {
        val owner = reader(); val body = json.writeValueAsString(request())
        repeat(120) { write(owner, body).andExpect(status().isOk) }
        write(owner, body).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("READER_PREFERENCES_LIMIT"))
            .andExpect(header().exists("Retry-After")).andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(PATH).with(user(owner))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        var time = 1_000L; val limits = ReaderPreferencesWriteLimits { time }
        repeat(120) { limits.acquire(owner) }
        assertEquals(60, assertThrows(ReaderPreferencesFailure::class.java) { limits.acquire(owner) }.retryAfterSeconds)
        limits.acquire("other-user")
        time += 60_000
        assertDoesNotThrow { limits.acquire(owner) }
    }
}
