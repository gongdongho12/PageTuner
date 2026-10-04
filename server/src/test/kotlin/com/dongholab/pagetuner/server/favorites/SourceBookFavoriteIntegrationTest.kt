package com.dongholab.pagetuner.server.favorites

import com.dongholab.pagetuner.core.model.library.SourceBookFavoriteMetadata
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=source-book-favorite-test-password"])
@AutoConfigureMockMvc
class SourceBookFavoriteIntegrationTest {
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
    @Autowired lateinit var service: SourceBookFavoriteService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private val path = "/api/v1/source-book-favorites"
    private fun reader(): String = "favorite-${UUID.randomUUID()}".also {
        jdbc.update("insert into reader_account(id,username,password_hash,display_name) values(?,?,?,?)", UUID.randomUUID(), it, "test-only", "Test")
    }
    private fun book(title: String = "Exact book") = SourceBookFavoriteMetadata(title, listOf("Author", "作者"), "en", "https://example.com/series/Book%20One")
    private fun request(version: Long = 0, id: String = "https://example.com/series/Book One", provider: String = "source-a",
                        metadata: SourceBookFavoriteMetadata? = book(), mutation: UUID = UUID.randomUUID()) =
        PutSourceBookFavoriteRequest(provider, id, version, mutation, metadata == null, metadata)
    private fun write(owner: String, request: PutSourceBookFavoriteRequest) = writeJson(owner, json.writeValueAsString(request))
    private fun writeJson(owner: String, body: String) = mvc.perform(put(path).with(user(owner)).with(csrf()).contentType("application/json").content(body))

    @Test fun `read is empty without creating defaults and exact opaque source identity round trips`() {
        val owner = reader()
        repeat(2) {
            mvc.perform(get(path).with(user(owner))).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items").isEmpty).andExpect(jsonPath("$.watermark").value(0)).andExpect(jsonPath("$.hasMore").value(false))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from source_book_favorite_account where user_id=?", Long::class.java, owner)!!)
        val input = request(id = "原書/Aa,é/e\u0301😀")
        val stored = write(owner, input).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.providerId").value(input.providerId)).andExpect(jsonPath("$.bookId").value(input.bookId))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.book.authors[1]").value("作者")).andReturn().response.contentAsString
        val feed = mvc.perform(get(path).with(user(owner))).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(json.readTree(stored), json.readTree(feed)["items"][0])
    }

    @Test fun `latest response loss replay preserves timestamp revision and rejects any changed payload`() {
        val owner = reader(); val input = request()
        val first = write(owner, input).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(first, write(owner, input).andExpect(status().isOk).andReturn().response.contentAsString)
        for (changed in listOf(input.copy(expectedVersion = 1), input.copy(book = book("Changed")), input.copy(deleted = true, book = null))) {
            write(owner, changed).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_MUTATION_REUSED"))
        }
        assertEquals(1L, service.changes(owner).watermark)
        service.put(owner, request(1, metadata = book("Later")))
        write(owner, input).andExpect(status().isConflict).andExpect(jsonPath("$.current.version").value(2))
    }

    @Test fun `deletion before create leaves a tombstone and restoration requires its version`() {
        val owner = reader()
        val deleted = service.put(owner, request(metadata = null))
        assertTrue(deleted.deleted); assertNull(deleted.book)
        write(owner, request()).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(1)).andExpect(jsonPath("$.current.deleted").value(true))
        write(owner, request(1)).andExpect(status().isOk).andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.deleted").value(false))
        val absent = write(owner, request(2, id = "missing")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.current.version").value(0)).andExpect(jsonPath("$.current.changeRevision").value(0))
            .andExpect(jsonPath("$.current.deleted").value(true)).andReturn().response.contentAsString
        assertTrue(json.readTree(absent)["current"]["updatedAt"].isNull)
        assertEquals(listOf(true, false), service.changes(owner).items.map { it.deleted })
    }

    @Test fun `account provider ID and case are isolated and account deletion cascades all history`() {
        val owner = reader(); val other = reader()
        for (input in listOf(request(), request(provider = "source-b"), request(id = "https://example.com/series/book One"))) service.put(owner, input)
        service.put(other, request())
        assertEquals(3, service.changes(owner).items.size)
        assertEquals(1, service.changes(other).items.size)
        jdbc.update("delete from reader_account where username=?", owner)
        for (table in listOf("source_book_favorite_account", "source_book_favorite_current", "source_book_favorite_change")) {
            assertEquals(0L, jdbc.queryForObject("select count(*) from $table where user_id=?", Long::class.java, owner)!!)
        }
        assertEquals(1L, service.changes(other).watermark)
    }

    @Test fun `fixed watermark pages preserve old versions tombstones and changes after the first page`() {
        val owner = reader()
        repeat(54) { service.put(owner, request(id = "book-$it")) }
        val first = service.changes(owner, limit = 50)
        assertEquals(50, first.items.size); assertTrue(first.hasMore); assertEquals(54L, first.watermark)
        service.put(owner, request(1, id = "book-53", metadata = null))
        service.put(owner, request(1, id = "book-0", metadata = book("Updated")))
        val second = service.changes(owner, first.nextAfterRevision, first.watermark, 50)
        assertEquals(listOf(51L, 52L, 53L, 54L), second.items.map { it.changeRevision })
        assertFalse(second.items.last().deleted); assertFalse(second.hasMore); assertEquals(54L, second.nextAfterRevision)
        val updates = service.changes(owner, second.nextAfterRevision)
        assertEquals(listOf(55L, 56L), updates.items.map { it.changeRevision }); assertTrue(updates.items.first().deleted)
        val empty = service.changes(owner, updates.watermark)
        assertTrue(empty.items.isEmpty()); assertFalse(empty.hasMore); assertEquals(56L, empty.nextAfterRevision)
    }

    @Test fun `simultaneous creates produce exactly one success and a current conflict`() {
        val owner = reader(); val pool = Executors.newFixedThreadPool(2); val start = CountDownLatch(1)
        try {
            val tasks = (1..2).map { index -> pool.submit(Callable {
                start.await(10, TimeUnit.SECONDS)
                try { service.put(owner, request(metadata = book("Book $index"))); "success" }
                catch (failure: SourceBookFavoriteFailure) { assertEquals(1L, failure.current!!.version); failure.code }
            }) }
            start.countDown()
            assertEquals(setOf("success", "SOURCE_BOOK_FAVORITE_CONFLICT"), tasks.map { it.get(15, TimeUnit.SECONDS) }.toSet())
            assertEquals(1L, service.changes(owner).watermark)
        } finally { pool.shutdownNow() }
    }

    @Test fun `account transaction lock prevents a later revision committing before an earlier revision`() {
        val owner = reader(); val pool = Executors.newFixedThreadPool(2)
        val firstStored = CountDownLatch(1); val permitCommit = CountDownLatch(1); val secondStarted = CountDownLatch(1)
        try {
            val first = pool.submit(Callable {
                TransactionTemplate(transactionManager).execute {
                    service.put(owner, request(id = "first"))
                    val pid = jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!
                    firstStored.countDown()
                    assertTrue(permitCommit.await(10, TimeUnit.SECONDS))
                    pid
                }
            })
            assertTrue(firstStored.await(10, TimeUnit.SECONDS))
            val second = pool.submit(Callable { secondStarted.countDown(); service.put(owner, request(id = "second")) })
            assertTrue(secondStarted.await(10, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8)
            var blocked = false
            while (!blocked && System.nanoTime() < deadline) {
                blocked = jdbc.queryForObject("select exists(select 1 from pg_stat_activity where datname=current_database() and wait_event_type='Lock' and query like '%pg_advisory_xact_lock%')", Boolean::class.java)!!
                if (!blocked) Thread.sleep(10)
            }
            assertTrue(blocked, "Second writer must wait on the account lock")
            assertEquals(0L, service.changes(owner).watermark)
            permitCommit.countDown(); first.get(15, TimeUnit.SECONDS)
            assertEquals(2L, second.get(15, TimeUnit.SECONDS).changeRevision)
            assertEquals(listOf("first", "second"), service.changes(owner).items.map { it.bookId })
        } finally { permitCommit.countDown(); pool.shutdownNow() }
    }

    @Test fun `strict body rejects duplicate missing unknown and coerced fields`() {
        val owner = reader(); val body = json.writeValueAsString(request())
        val invalid = listOf("null", "[]", "{}", body + "{}", body.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            body.replace("\"expectedVersion\":0", "\"expectedVersion\":0.1"), body.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
            body.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"), body.replace("\"expectedVersion\":0", "\"unknown\":true,\"expectedVersion\":0"),
            body.replace("\"deleted\":false", "\"deleted\":\"false\""), body.replace("\"deleted\":false", "\"deleted\":true"),
            body.replace("\"title\":\"Exact book\"", "\"title\":\"\\ud800\""), body.replace("\"language\":\"en\",", ""),
            body.replace("\"authors\":[\"Author\",\"作者\"]", "\"authors\":null"),
            body.replace(Regex("\"mutationId\":\"[^\"]+\""), "\"mutationId\":\"1-1-1-1-1\""))
        invalid.forEach { writeJson(owner, it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_INVALID")) }
        assertEquals(0L, service.changes(owner).watermark)
    }

    @Test fun `UTF16 metadata identity and inert URL limits are enforced without silent normalization`() {
        val owner = reader()
        val invalid = listOf(request(id = "x".repeat(501)), request(provider = "x".repeat(101)), request(id = " leading"),
            request(id = "x\u0000y"), request(metadata = book("x".repeat(501))),
            request(metadata = book().copy(authors = List(21) { "A" })), request(metadata = book().copy(authors = listOf(" "))),
            request(metadata = book().copy(language = "en\ufeff")), request(metadata = book().copy(url = "javascript:alert(1)")),
            request(metadata = book().copy(url = "https://user:pass@example.com/")), request(metadata = book().copy(url = "https://example.com\\evil")),
            request(metadata = book().copy(url = "https://example.com/空白")))
        invalid.forEach { write(owner, it).andExpect(status().isBadRequest) }
        // A raw Java unpaired surrogate is replaced during UTF-8 encoding; preserve its JSON escape on the wire.
        writeJson(owner, json.writeValueAsString(request(id = "invalid-identity")).replace("invalid-identity", "\\ud800"))
            .andExpect(status().isBadRequest)
        write(owner, request(id = "書".repeat(500), provider = "源".repeat(100), metadata = book("😀".repeat(250))))
            .andExpect(status().isOk)
    }

    @Test fun `authentication csrf and bounded cursor inputs are required`() {
        val owner = reader()
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(put(path).with(user(owner)).contentType("application/json").content(json.writeValueAsString(request()))).andExpect(status().isForbidden)
        mvc.perform(put(path).with(csrf()).contentType("application/json").content(json.writeValueAsString(request()))).andExpect(status().isUnauthorized)
        for (query in listOf("afterRevision=-1", "afterRevision=1", "afterRevision=0.5", "afterRevision=9007199254740992", "untilRevision=1", "untilRevision=-1", "limit=0", "limit=101", "limit=1.2")) {
            mvc.perform(get("$path?$query").with(user(owner))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_INVALID"))
        }
    }

    @Test fun `body limit includes chunked JSON and rate limit includes exact replay`() {
        val owner = reader(); val body = " ".repeat(32 * 1024 + 1)
        mvc.perform(put(path).servletPath(path).with(user(owner)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isPayloadTooLarge).andExpect(header().string("Cache-Control", "no-store"))
        val raw = object : MockHttpServletRequest() {
            override fun getContentLength() = -1
            override fun getContentLengthLong() = -1L
        }.apply { method = "PUT"; servletPath = path; setContent(body.toByteArray()) }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(raw, response) { _, _ -> fail<Unit>("Oversized favorite reached binding.") }
        assertEquals(413, response.status)
        val input = request()
        repeat(120) { write(owner, input).andExpect(status().isOk) }
        write(owner, input).andExpect(status().isTooManyRequests).andExpect(header().exists("Retry-After"))
            .andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_LIMIT"))
        assertEquals(1L, service.changes(owner).watermark)
        var now = 0L; val limits = SourceBookFavoriteWriteLimits { now }
        repeat(120) { limits.acquire("test") }
        assertThrows(SourceBookFavoriteFailure::class.java) { limits.acquire("test") }
        now += 60_000; assertDoesNotThrow { limits.acquire("test") }
    }

    @Test fun `safe integer exhaustion rejects mutations without advancing history`() {
        val owner = reader(); service.put(owner, request())
        jdbc.update("update source_book_favorite_account set revision=? where user_id=?", MAX_FAVORITE_REVISION, owner)
        write(owner, request(id = "next")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("SOURCE_BOOK_FAVORITE_REVISION_EXHAUSTED")).andExpect(jsonPath("$.current").doesNotExist())
        assertEquals(1L, jdbc.queryForObject("select count(*) from source_book_favorite_change where user_id=?", Long::class.java, owner)!!)
    }
}
