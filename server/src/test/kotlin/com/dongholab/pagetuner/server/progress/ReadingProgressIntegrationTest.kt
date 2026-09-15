package com.dongholab.pagetuner.server.progress

import com.dongholab.pagetuner.server.ApiRequestBodyLimit
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.dongholab.pagetuner.server.translation.SaveTranslationRequest
import com.dongholab.pagetuner.server.translation.TranslatedParagraphRequest
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.SourceParagraph
import com.dongholab.pagetuner.server.workflow.StoredChapter
import com.dongholab.pagetuner.server.workflow.UploadedChapterRequest
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
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=reading-progress-test-password"])
@AutoConfigureMockMvc
class ReadingProgressIntegrationTest {
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
    @Autowired lateinit var progress: ReadingProgressService
    @Autowired lateinit var chapters: SourceChapterStore
    @Autowired lateinit var translations: TranslationApplicationService

    private fun reader() = "progress-${UUID.randomUUID()}"
    private fun source(owner: String): StoredChapter = chapters.upload(owner, UploadedChapterRequest(
        UUID.randomUUID().toString(), "Progress book", "chapter-1", "First chapter", "en",
        listOf(SourceParagraph("p-1", 0, "A😀 quiet garden."), SourceParagraph("p-2", 1, "The reader continues.")),
    ))
    private fun request(version: Long = 0, paragraphId: String = "p-1", offset: Int = 0, mutation: UUID = UUID.randomUUID()) =
        PutReadingProgressRequest(version, mutation, ReadingProgressAnchor(paragraphId, offset))
    private fun path(id: UUID, kind: ReadingProgressKind = ReadingProgressKind.ORIGINAL) = "/api/v1/reading-progress/${kind.name}/$id"
    private fun write(owner: String, id: UUID, body: String) = mvc.perform(put(path(id)).with(user(owner)).with(csrf())
        .contentType("application/json").content(body))

    @Test fun `owned records have explicit empty views then round trip their position with no store`() {
        val owner = reader(); val chapter = source(owner)
        val empty = mvc.perform(get(path(chapter.recordId)).with(user(owner)))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.kind").value("ORIGINAL")).andExpect(jsonPath("$.recordId").value(chapter.recordId.toString()))
            .andExpect(jsonPath("$.version").value(0)).andReturn()
        val emptyNode = json.readTree(empty.response.contentAsString)
        assertTrue(emptyNode.has("anchor") && emptyNode["anchor"].isNull)
        assertTrue(emptyNode.has("updatedAt") && emptyNode["updatedAt"].isNull)
        val written = write(owner, chapter.recordId, json.writeValueAsString(request(offset = 3)))
            .andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.anchor.characterOffset").value(3))
            .andExpect(jsonPath("$.updatedAt").isString).andReturn().response.contentAsString
        val reread = mvc.perform(get(path(chapter.recordId)).with(user(owner))).andExpect(status().isOk)
            .andReturn().response.contentAsString
        assertEquals(json.readTree(written), json.readTree(reread))
    }

    @Test fun `lost response retry is stable and latest mutation cannot identify a different request`() {
        val owner = reader(); val id = source(owner).recordId; val request = request(offset = 3)
        val first = progress.put(owner, ReadingProgressKind.ORIGINAL, id, request)
        assertEquals(first, progress.put(owner, ReadingProgressKind.ORIGINAL, id, request))
        for (changed in listOf(request.copy(expectedVersion = 1), request.copy(anchor = ReadingProgressAnchor("p-2", 1)))) {
            write(owner, id, json.writeValueAsString(changed)).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("READING_PROGRESS_MUTATION_REUSED"))
        }
        assertEquals(first, progress.get(owner, ReadingProgressKind.ORIGINAL, id))
    }

    @Test fun `stale writes return current progress and explicit new version resolves a conflict`() {
        val owner = reader(); val id = source(owner).recordId; val firstRequest = request(offset = 3)
        val first = progress.put(owner, ReadingProgressKind.ORIGINAL, id, firstRequest)
        write(owner, id, json.writeValueAsString(request(paragraphId = "p-2")))
            .andExpect(status().isConflict).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("READING_PROGRESS_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(first.version))
            .andExpect(jsonPath("$.current.anchor.characterOffset").value(3))
        val second = progress.put(owner, ReadingProgressKind.ORIGINAL, id, request(version = 1, paragraphId = "p-2"))
        assertEquals(2, second.version)
        write(owner, id, json.writeValueAsString(firstRequest)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.current.version").value(2))
        assertEquals(second, progress.get(owner, ReadingProgressKind.ORIGINAL, id))
    }

    @Test fun `simultaneous first writers and later updates have exactly one winner`() {
        val owner = reader(); val id = source(owner).recordId
        for (version in 0L..1L) {
            val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val go = CountDownLatch(1)
            try {
                val outcomes = (0..5).map { index -> pool.submit(Callable {
                    ready.countDown(); check(go.await(10, TimeUnit.SECONDS))
                    try { progress.put(owner, ReadingProgressKind.ORIGINAL, id, request(version, "p-2", index)); "ok" }
                    catch (error: ReadingProgressFailure) { error.code }
                }) }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
                val values = outcomes.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, values.count { it == "ok" })
                assertEquals(5, values.count { it == "READING_PROGRESS_CONFLICT" })
                assertEquals(version + 1, progress.get(owner, ReadingProgressKind.ORIGINAL, id).version)
            } finally { go.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `foreign nonexistent and wrong kind documents never expose or create progress`() {
        val owner = reader(); val foreign = reader(); val id = source(owner).recordId
        progress.put(owner, ReadingProgressKind.ORIGINAL, id, request())
        for (target in listOf(path(id), path(UUID.randomUUID()), path(id, ReadingProgressKind.TRANSLATION))) {
            mvc.perform(get(target).with(user(foreign))).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("READING_PROGRESS_NOT_FOUND"))
            mvc.perform(put(target).with(user(foreign)).with(csrf()).contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("READING_PROGRESS_NOT_FOUND"))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_progress where user_id=?", Long::class.java, foreign)!!)
    }

    @Test fun `authentication csrf and full UUID paths are required`() {
        val owner = reader(); val id = source(owner).recordId
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized)
        mvc.perform(put(path(id)).with(user(owner)).contentType("application/json").content(json.writeValueAsString(request())))
            .andExpect(status().isForbidden)
        mvc.perform(put(path(id)).with(csrf()).contentType("application/json").content(json.writeValueAsString(request())))
            .andExpect(status().isUnauthorized)
        for (path in listOf("/api/v1/reading-progress/ORIGINAL/1-1-1-1-1", "/api/v1/reading-progress/original/$id")) {
            mvc.perform(get(path).with(user(owner))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("READING_PROGRESS_INVALID"))
        }
    }

    @Test fun `anchors must exist stay in range and respect UTF16 surrogate boundaries`() {
        val owner = reader(); val id = source(owner).recordId
        val invalid = listOf(request(paragraphId = "missing"), request(paragraphId = "x".repeat(201)),
            request(offset = -1), request(offset = 1000), request(offset = 2), request(paragraphId = "p-1\n"))
        for (value in invalid) write(owner, id, json.writeValueAsString(value)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READING_PROGRESS_INVALID"))
        val end = "A😀 quiet garden.".length
        write(owner, id, json.writeValueAsString(request(offset = end))).andExpect(status().isOk)
            .andExpect(jsonPath("$.anchor.characterOffset").value(end))
    }

    @Test fun `translation positions validate target text and remain independent from originals`() {
        val owner = reader(); val source = source(owner)
        val translated = translations.save(owner, SaveTranslationRequest(source.providerId, source.bookId, source.chapterId,
            source.sourceRevision, "en", "ko", "test-provider", paragraphs = listOf(
                TranslatedParagraphRequest("p-1", "정원"), TranslatedParagraphRequest("p-2", "독자가 계속 읽습니다.")),
        ))
        progress.put(owner, ReadingProgressKind.ORIGINAL, source.recordId, request(offset = 4))
        val target = progress.put(owner, ReadingProgressKind.TRANSLATION, translated.recordId, request(offset = 2))
        assertEquals(ReadingProgressKind.TRANSLATION, target.kind)
        mvc.perform(put(path(translated.recordId, ReadingProgressKind.TRANSLATION)).with(user(owner)).with(csrf())
            .contentType("application/json").content(json.writeValueAsString(request(1, offset = 4))))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("READING_PROGRESS_INVALID"))
        assertEquals(4, progress.get(owner, ReadingProgressKind.ORIGINAL, source.recordId).anchor!!.characterOffset)
        assertEquals(2, progress.get(owner, ReadingProgressKind.TRANSLATION, translated.recordId).anchor!!.characterOffset)
        jdbc.update("delete from translation_artifact where id=? and user_id=?", translated.recordId, owner)
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_progress where record_id=?", Long::class.java, translated.recordId)!!)
        assertEquals(1L, progress.get(owner, ReadingProgressKind.ORIGINAL, source.recordId).version)
    }

    @Test fun `JSON numbers fields UUIDs duplicates and trailing content are strictly checked`() {
        val owner = reader(); val id = source(owner).recordId
        val good = json.writeValueAsString(request())
        val invalid = listOf("{}", "null", "[]", good + "{}", good.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9223372036854775808"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"),
            good.replace("\"expectedVersion\":0", "\"unknown\":true,\"expectedVersion\":0"),
            good.replace("\"characterOffset\":0", "\"characterOffset\":0.1"),
            good.replace("\"characterOffset\":0", "\"characterOffset\":2147483648"),
            good.replace("\"characterOffset\":0", "\"characterOffset\":0,\"ignored\":1"),
            good.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "1-1-1-1-1"))
        for (body in invalid) write(owner, id, body).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READING_PROGRESS_INVALID"))
        assertEquals(0, progress.get(owner, ReadingProgressKind.ORIGINAL, id).version)
    }

    @Test fun `progress bodies are bounded even when content length is absent`() {
        val owner = reader(); val id = source(owner).recordId
        val body = " ".repeat(8193)
        mvc.perform(put(path(id)).servletPath(path(id)).with(user(owner)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isPayloadTooLarge).andExpect(header().string("Cache-Control", "no-store"))
        val unknownLength = object : MockHttpServletRequest() {
            override fun getContentLength(): Int = -1
            override fun getContentLengthLong(): Long = -1
        }.apply { method = "PUT"; servletPath = path(id); setContent(body.toByteArray()) }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(unknownLength, response) { _, _ -> fail<Unit>("Oversized chunked body reached JSON binding.") }
        assertEquals(413, response.status)
        assertEquals("no-store", response.getHeader("Cache-Control"))
        assertEquals(0, progress.get(owner, ReadingProgressKind.ORIGINAL, id).version)
    }

    @Test fun `deleting a server source cascades its reading progress`() {
        val owner = reader(); val id = source(owner).recordId
        progress.put(owner, ReadingProgressKind.ORIGINAL, id, request())
        jdbc.update("delete from source_chapter where id=? and user_id=?", id, owner)
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_progress where record_id=?", Long::class.java, id)!!)
        mvc.perform(get(path(id)).with(user(owner))).andExpect(status().isNotFound)
    }

    @Test fun `rate limit yields retry after and does not consume reads`() {
        val owner = reader(); val id = source(owner).recordId; val body = json.writeValueAsString(request())
        repeat(120) { write(owner, id, body).andExpect(status().isOk) }
        write(owner, id, body).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("READING_PROGRESS_LIMIT")).andExpect(header().exists("Retry-After"))
        mvc.perform(get(path(id)).with(user(owner))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        var time = 1_000L
        val limits = ReadingProgressWriteLimits { time }
        repeat(120) { limits.acquire(owner) }
        assertEquals(60, assertThrows(ReadingProgressFailure::class.java) { limits.acquire(owner) }.retryAfterSeconds)
        limits.acquire("another-user")
        time += 60_000
        assertDoesNotThrow { limits.acquire(owner) }
    }
}
