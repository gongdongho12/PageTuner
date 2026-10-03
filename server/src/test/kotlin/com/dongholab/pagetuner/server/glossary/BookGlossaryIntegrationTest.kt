package com.dongholab.pagetuner.server.glossary

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncKind
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=book-glossary-test-password"])
@AutoConfigureMockMvc
class BookGlossaryIntegrationTest {
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
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var service: BookGlossaryService
    private val path = "/api/v1/book-glossary"
    private val identity = BookGlossarySyncIdentity("source-a", "https://example.com/series/Book One", "ko")
    private val entry = BookGlossarySyncEntry("existing-entry", " Alice ", " アリス ", " 아리 ", BookGlossarySyncKind.Character, true, true)
    private fun reader() = "glossary-${UUID.randomUUID()}".also {
        jdbc.update("insert into reader_account(id,username,password_hash,display_name) values(?,?,?,?)", UUID.randomUUID(), it, "test-only", "Test")
    }
    private fun request(version: Long = 0, key: BookGlossarySyncIdentity = identity, entries: List<BookGlossarySyncEntry>? = listOf(entry), mutation: UUID = UUID.randomUUID()) =
        PutBookGlossaryRequest(key.providerId, key.bookId, key.targetLanguage, version, mutation, entries)
    private fun write(owner: String, input: PutBookGlossaryRequest) = writeJson(owner, json.writeValueAsString(input))
    private fun writeJson(owner: String, input: String) = mvc.perform(put(path).servletPath(path).with(user(owner)).with(csrf()).contentType("application/json").content(input))
    private fun read(owner: String, key: BookGlossarySyncIdentity = identity) = mvc.perform(get(path).with(user(owner))
        .param("providerId", key.providerId).param("bookId", key.bookId).param("targetLanguage", key.targetLanguage))

    @Test fun `absent reads are read only and full shared fixture preserves every field and order`() {
        val owner = reader()
        val absent = read(owner).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(0)).andReturn().response.contentAsString
        assertTrue(json.readTree(absent)["entries"].isNull); assertTrue(json.readTree(absent)["updatedAt"].isNull)
        mvc.perform(post("$path/query").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(identity)))
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value(0))
        assertEquals(0L, jdbc.queryForObject("select count(*) from book_glossary where user_id=?", Long::class.java, owner)!!)
        val fixture = javaClass.getResource("/book-glossary-v1/request.json")!!.readText()
        val input = json.readValue(fixture, PutBookGlossaryRequest::class.java)
        val response = writeJson(owner, fixture).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1)).andReturn().response.contentAsString
        assertEquals(input.entries, json.readValue(response, BookGlossaryView::class.java).entries)
        assertEquals(input.entries, service.get(owner, input.identity()).entries)
    }

    @Test fun `exact latest replay does not advance and mutation payload mismatch or stale version does not overwrite`() {
        val owner = reader(); val input = request()
        val first = write(owner, input).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(first, write(owner, input).andExpect(status().isOk).andReturn().response.contentAsString)
        for (modified in listOf(input.copy(expectedVersion = 1), input.copy(entries = null), input.copy(entries = listOf(entry.copy(displayTerm = "Other")))))
            write(owner, modified).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_MUTATION_REUSED"))
        service.put(owner, request(1, entries = listOf(entry.copy(kind = BookGlossarySyncKind.Place))))
        write(owner, input).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(2)).andExpect(jsonPath("$.current.entries[0].kind").value("Place"))
        assertEquals(2L, service.get(owner, identity).version)
    }

    @Test fun `null tombstones differ from empty lists and prevent stale resurrection`() {
        val owner = reader()
        val deleted = service.put(owner, request(entries = null))
        assertEquals(1L, deleted.version); assertNull(deleted.entries); assertNotNull(deleted.updatedAt)
        write(owner, request()).andExpect(status().isConflict).andExpect(jsonPath("$.current.version").value(1))
        val empty = service.put(owner, request(1, entries = emptyList()))
        assertEquals(emptyList<BookGlossarySyncEntry>(), empty.entries)
        assertEquals(2L, empty.version)
        assertEquals(listOf(entry), service.put(owner, request(2)).entries)
    }

    @Test fun `account provider book case target and tuple delimiters remain independent with cascading deletion`() {
        val owner = reader(); val other = reader()
        val keys = listOf(identity, identity.copy(providerId = "source-b"), identity.copy(bookId = identity.bookId.lowercase()),
            identity.copy(targetLanguage = "en"), BookGlossarySyncIdentity("source:a", "book", "ko"), BookGlossarySyncIdentity("source", "a:book", "ko"))
        keys.forEachIndexed { index, key -> service.put(owner, request(key = key, entries = listOf(entry.copy(displayTerm = "alias-$index")))) }
        assertEquals(0L, service.get(other, identity).version)
        service.put(other, request())
        keys.forEachIndexed { index, key -> assertEquals("alias-$index", service.get(owner, key).entries!!.single().displayTerm) }
        jdbc.update("delete from reader_account where username=?", owner)
        assertEquals(0L, jdbc.queryForObject("select count(*) from book_glossary where user_id=?", Long::class.java, owner)!!)
        assertEquals(1L, service.get(other, identity).version)
    }

    @Test fun `maximum original CJK identifiers and 500 full fidelity entries fit PostgreSQL without btree truncation`() {
        val owner = reader(); val key = BookGlossarySyncIdentity("源".repeat(100), "書".repeat(2000), "zh-hant")
        val maximum = List(500) { index -> entry.copy(id = index.toString().padStart(3, '0') + "識".repeat(197),
            kind = BookGlossarySyncKind.entries[index % 3], enabled = index % 2 == 0, caseSensitive = index % 3 == 0) }
        val input = request(key = key, entries = maximum)
        assertTrue(json.writeValueAsBytes(input).size <= 1024 * 1024)
        write(owner, input).andExpect(status().isOk)
        val queried = mvc.perform(post("$path/query").servletPath("$path/query").with(user(owner)).with(csrf())
            .contentType("application/json").content(json.writeValueAsBytes(key))).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andReturn().response.contentAsString
        assertEquals(maximum, json.readValue(queried, BookGlossaryView::class.java).entries)
        assertEquals(maximum, service.get(owner, key).entries)
        assertEquals(key.bookId, service.get(owner, key).bookId)
        val after = service.put(owner, request(1, key, maximum.reversed()))
        assertEquals(maximum.reversed(), after.entries)
        write(owner, request(2, key, maximum + entry)).andExpect(status().isBadRequest)
    }

    @Test fun `simultaneous first writers produce exactly one commit and authoritative conflict`() {
        val owner = reader(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val futures = (1..2).map { index -> pool.submit(Callable {
                assertTrue(start.await(10, TimeUnit.SECONDS))
                try { service.put(owner, request(entries = listOf(entry.copy(displayTerm = "alias-$index")))); "success" }
                catch (error: BookGlossaryFailure) { assertEquals(1L, error.current!!.version); error.code }
            }) }
            start.countDown()
            assertEquals(setOf("success", "BOOK_GLOSSARY_CONFLICT"), futures.map { it.get(15, TimeUnit.SECONDS) }.toSet())
            assertEquals(1L, service.get(owner, identity).version)
        } finally { pool.shutdownNow() }
    }

    @Test fun `strict JSON rejects unknown missing duplicate coerced and malformed fields before storing`() {
        val owner = reader(); val input = json.writeValueAsString(request())
        val invalid = listOf("null", "[]", "{}", input + "{}", input.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            input.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"), input.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"),
            input.replace("\"expectedVersion\":0", "\"unknown\":true,\"expectedVersion\":0"), input.replace("\"kind\":\"Character\"", "\"kind\":\"Unknown\""),
            input.replace("\"enabled\":true", "\"enabled\":1"), input.replace("\"caseSensitive\":true,", ""),
            input.replace("\"id\":\"existing-entry\"", "\"id\":\"existing-entry\",\"id\":\"other\""),
            input.replace("existing-entry", "\\ud800"), input.replace("existing-entry", " boundary"),
            input.replace(Regex("\"mutationId\":\"[^\"]+\""), "\"mutationId\":\"1-1-1-1-1\""))
        invalid.forEach { writeJson(owner, it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_INVALID")) }
        assertEquals(0L, service.get(owner, identity).version)
    }

    @Test fun `limits reject identity normalization bad languages terms and duplicate IDs while preserving valid raw terms`() {
        val owner = reader()
        val bad = listOf(request(key = identity.copy(providerId = " source")), request(key = identity.copy(bookId = "x".repeat(2001))),
            request(key = identity.copy(targetLanguage = "auto")), request(key = identity.copy(targetLanguage = "en-US")),
            request(entries = listOf(entry.copy(sourceTerm = " \u00a0\ufeff "))), request(entries = listOf(entry.copy(translatedTerm = "x\n"))),
            request(entries = listOf(entry.copy(displayTerm = "x".repeat(201)))), request(entries = listOf(entry, entry.copy(kind = BookGlossarySyncKind.Place))))
        bad.forEach { write(owner, it).andExpect(status().isBadRequest) }
        val bounds = entry.copy(id = "識".repeat(200), sourceTerm = "😀".repeat(100), translatedTerm = "字".repeat(200), displayTerm = " ".repeat(200), enabled = false)
        write(owner, request(entries = listOf(bounds))).andExpect(status().isOk)
        assertEquals(listOf(bounds), service.get(owner, identity).entries)
    }

    @Test fun `authentication csrf no store and unambiguous exact query fields are required`() {
        val owner = reader(); val input = json.writeValueAsString(request())
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(put(path).with(user(owner)).contentType("application/json").content(input)).andExpect(status().isForbidden)
        mvc.perform(put(path).with(csrf()).contentType("application/json").content(input)).andExpect(status().isUnauthorized)
        mvc.perform(post("$path/query").with(user(owner)).contentType("application/json").content(json.writeValueAsBytes(identity))).andExpect(status().isForbidden)
        mvc.perform(post("$path/query").with(csrf()).contentType("application/json").content(json.writeValueAsBytes(identity))).andExpect(status().isUnauthorized)
        for (body in listOf("{}", json.writeValueAsString(identity).replace("\"providerId\":", "\"unknown\":1,\"providerId\":"),
            json.writeValueAsString(identity) + "{}")) {
            mvc.perform(post("$path/query").with(user(owner)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_INVALID"))
        }
        mvc.perform(get(path).with(user(owner))).andExpect(status().isBadRequest)
        mvc.perform(get(path).with(user(owner)).param("providerId", identity.providerId).param("bookId", identity.bookId)
            .param("targetLanguage", "ko", "en")).andExpect(status().isBadRequest)
        mvc.perform(get(path).with(user(owner)).param("providerId", identity.providerId).param("bookId", identity.bookId)
            .param("targetLanguage", "ko").param("unknown", "1")).andExpect(status().isBadRequest)
    }

    @Test fun `body ceiling is bounded for fixed and chunked requests and account rate limit includes replays`() {
        val owner = reader(); val tooLarge = " ".repeat(1024 * 1024 + 1)
        writeJson(owner, tooLarge).andExpect(status().isPayloadTooLarge).andExpect(header().string("Cache-Control", "no-store"))
        val raw = object : MockHttpServletRequest() {
            override fun getContentLength() = -1
            override fun getContentLengthLong() = -1L
        }.apply { method = "PUT"; servletPath = path; setContent(tooLarge.toByteArray()) }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(raw, response) { _, _ -> fail<Unit>("Oversized glossary reached binding.") }
        assertEquals(413, response.status)
        mvc.perform(post("$path/query").servletPath("$path/query").with(user(owner)).with(csrf()).contentType("application/json")
            .content(" ".repeat(16 * 1024 + 1))).andExpect(status().isPayloadTooLarge)
        val input = request()
        repeat(120) { write(owner, input).andExpect(status().isOk) }
        write(owner, input).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_LIMIT"))
            .andExpect(header().exists("Retry-After"))
        mvc.perform(post("$path/query").with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(identity)))
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        assertEquals(1L, service.get(owner, identity).version)
        var now = 0L; val limit = BookGlossaryWriteLimits { now }
        repeat(120) { limit.acquire("a") }
        assertThrows(BookGlossaryFailure::class.java) { limit.acquire("a") }
        now += 60_000; assertDoesNotThrow { limit.acquire("a") }
    }

    @Test fun `version exhaustion is explicit and digest collision never aliases another original identity`() {
        val owner = reader(); service.put(owner, request())
        jdbc.update("update book_glossary set version=? where user_id=?", MAX_BOOK_GLOSSARY_VERSION, owner)
        write(owner, request(1)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("BOOK_GLOSSARY_EXHAUSTED"))
            .andExpect(jsonPath("$.current").doesNotExist())
        jdbc.update("update book_glossary set book_id=? where user_id=?", "different-original-id", owner)
        assertThrows(IllegalStateException::class.java) { service.get(owner, identity) }
    }
}
