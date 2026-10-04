package com.dongholab.pagetuner.server.organization

import com.dongholab.pagetuner.server.ApiRequestBodyLimit
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.dongholab.pagetuner.server.translation.SaveTranslationRequest
import com.dongholab.pagetuner.server.translation.TranslatedParagraphRequest
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.SourceParagraph
import com.dongholab.pagetuner.server.workflow.UploadedChapterRequest
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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

@SpringBootTest(properties = ["spring.security.user.password=library-organization-test-password"])
@AutoConfigureMockMvc
class LibraryOrganizationIntegrationTest {
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
    @Autowired lateinit var service: LibraryOrganizationService
    @Autowired lateinit var chapters: SourceChapterStore
    @Autowired lateinit var translations: TranslationApplicationService
    @Autowired lateinit var transactionManager: PlatformTransactionManager

    private val original = LibraryOrganizationKind.ORIGINAL
    private fun reader() = "organization-${UUID.randomUUID()}"
    private fun source(owner: String) = chapters.upload(owner, UploadedChapterRequest(
        UUID.randomUUID().toString(), "Organization book", "chapter-1", "First chapter", "en",
        listOf(SourceParagraph("p-1", 0, "A quiet garden.")),
    ))
    private fun value(folder: String = "Reading") = LibraryOrganization(folder, listOf("Fantasy", "한국어"), true)
    private fun request(version: Long = 0, organization: LibraryOrganization = value(), mutation: UUID = UUID.randomUUID()) =
        PutLibraryOrganizationRequest(version, mutation, organization)
    private fun path(id: UUID, kind: LibraryOrganizationKind = original) = "/api/v1/library-organization/${kind.name}/$id"
    private fun write(owner: String, id: UUID, body: String) = mvc.perform(put(path(id)).with(user(owner)).with(csrf())
        .contentType("application/json").content(body))

    private fun document(owner: String, kind: LibraryOrganizationKind): UUID {
        val chapter = source(owner)
        return if (kind == original) chapter.recordId else translations.save(owner, SaveTranslationRequest(
            chapter.providerId, chapter.bookId, chapter.chapterId, chapter.sourceRevision, "en", "ko", "test-provider",
            paragraphs = listOf(TranslatedParagraphRequest("p-1", "고요한 정원")),
        )).recordId
    }

    private fun deleteDocument(owner: String, kind: LibraryOrganizationKind, id: UUID) {
        val table = if (kind == original) "source_chapter" else "translation_artifact"
        assertEquals(1, jdbc.update("delete from $table where id=? and user_id=?", id, owner))
    }

    private fun awaitBlockedBy(backendPid: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        do {
            val blocked = jdbc.queryForObject("""
                select exists(select 1 from pg_stat_activity where datname=current_database()
                    and wait_event_type='Lock' and ?=any(pg_blocking_pids(pid)))
            """.trimIndent(), Boolean::class.java, backendPid)!!
            if (blocked) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        fail<Unit>("The competing transaction never waited on the document transaction's PostgreSQL lock.")
    }

    private fun assertDeleted(owner: String, kind: LibraryOrganizationKind, id: UUID) {
        mvc.perform(get(path(id, kind)).with(user(owner))).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_NOT_FOUND"))
        mvc.perform(put(path(id, kind)).with(user(owner)).with(csrf()).contentType("application/json")
            .content(json.writeValueAsString(request()))).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_NOT_FOUND"))
        assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where user_id=? and record_id=?",
            Long::class.java, owner, id)!!)
    }

    @Test fun `reads do not create defaults and explicit organization round trips with no store`() {
        val owner = reader(); val id = source(owner).recordId
        repeat(2) {
            val empty = mvc.perform(get(path(id)).with(user(owner))).andExpect(status().isOk)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.kind").value("ORIGINAL")).andExpect(jsonPath("$.recordId").value(id.toString()))
                .andExpect(jsonPath("$.version").value(0)).andReturn().response.contentAsString
            assertTrue(json.readTree(empty)["organization"].isNull)
            assertTrue(json.readTree(empty)["updatedAt"].isNull)
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where record_id=?", Long::class.java, id)!!)
        val saved = write(owner, id, json.writeValueAsString(request())).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.version").value(1)).andExpect(jsonPath("$.organization.folder").value("Reading"))
            .andExpect(jsonPath("$.organization.tags[0]").value("Fantasy")).andExpect(jsonPath("$.organization.favorite").value(true))
            .andReturn().response.contentAsString
        val read = mvc.perform(get(path(id)).with(user(owner))).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(json.readTree(saved), json.readTree(read))
    }

    @Test fun `clear stays versioned and latest exact retry is stable including stored timestamp`() {
        val owner = reader(); val id = source(owner).recordId
        service.put(owner, original, id, request())
        val clear = request(1, LibraryOrganization("", emptyList(), false))
        val saved = write(owner, id, json.writeValueAsString(clear)).andExpect(status().isOk)
            .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.organization.folder").value(""))
            .andExpect(jsonPath("$.organization.tags").isEmpty).andExpect(jsonPath("$.organization.favorite").value(false))
            .andReturn().response.contentAsString
        val retry = write(owner, id, json.writeValueAsString(clear)).andExpect(status().isOk).andReturn().response.contentAsString
        assertEquals(saved, retry)
        assertEquals(clear.organization, service.get(owner, original, id).organization)
    }

    @Test fun `latest mutation cannot be reused for values expected version or tag order`() {
        val owner = reader(); val id = source(owner).recordId; val input = request()
        val saved = service.put(owner, original, id, input)
        for (changed in listOf(input.copy(expectedVersion = 1), input.copy(organization = value("Later")),
            input.copy(organization = input.organization.copy(tags = input.organization.tags.reversed())))) {
            write(owner, id, json.writeValueAsString(changed)).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_MUTATION_REUSED"))
        }
        assertEquals(saved, service.get(owner, original, id))
    }

    @Test fun `conflicts expose current complete view including absent version zero`() {
        val owner = reader(); val id = source(owner).recordId
        write(owner, id, json.writeValueAsString(request(1))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.current.version").value(0)).andExpect(jsonPath("$.current.organization").isEmpty)
        val first = request(); service.put(owner, original, id, first)
        write(owner, id, json.writeValueAsString(request(organization = value("Later")))).andExpect(status().isConflict)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_CONFLICT"))
            .andExpect(jsonPath("$.current.version").value(1)).andExpect(jsonPath("$.current.organization.folder").value("Reading"))
        val resolved = service.put(owner, original, id, request(1, value("Later")))
        write(owner, id, json.writeValueAsString(first)).andExpect(status().isConflict).andExpect(jsonPath("$.current.version").value(2))
        assertEquals(resolved, service.get(owner, original, id))
    }

    @Test fun `concurrent creation and updates have exactly one winner`() {
        val owner = reader(); val id = source(owner).recordId
        for (version in 0L..1L) {
            val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val go = CountDownLatch(1)
            try {
                val outcomes = (0..5).map { index -> pool.submit(Callable {
                    ready.countDown(); check(go.await(10, TimeUnit.SECONDS))
                    try { service.put(owner, original, id, request(version, value("Folder $index"))); "ok" }
                    catch (error: LibraryOrganizationFailure) { error.code }
                }) }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
                val values = outcomes.map { it.get(20, TimeUnit.SECONDS) }
                assertEquals(1, values.count { it == "ok" })
                assertEquals(5, values.count { it == "LIBRARY_ORGANIZATION_CONFLICT" })
                assertEquals(version + 1, service.get(owner, original, id).version)
            } finally { go.countDown(); pool.shutdownNow() }
        }
    }

    @Test fun `foreign missing and wrong kind documents return identical not found without creating rows`() {
        val owner = reader(); val foreign = reader(); val id = source(owner).recordId
        service.put(owner, original, id, request())
        for (target in listOf(path(id), path(UUID.randomUUID()), path(id, LibraryOrganizationKind.TRANSLATION))) {
            mvc.perform(get(target).with(user(foreign))).andExpect(status().isNotFound)
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_NOT_FOUND"))
            mvc.perform(put(target).with(user(foreign)).with(csrf()).contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_NOT_FOUND"))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where user_id=?", Long::class.java, foreign)!!)
    }

    @Test fun `authentication csrf strict kind and full UUID path are required`() {
        val owner = reader(); val id = source(owner).recordId; val body = json.writeValueAsString(request())
        mvc.perform(get(path(id))).andExpect(status().isUnauthorized)
        mvc.perform(put(path(id)).with(user(owner)).contentType("application/json").content(body)).andExpect(status().isForbidden)
        mvc.perform(put(path(id)).with(csrf()).contentType("application/json").content(body)).andExpect(status().isUnauthorized)
        for (target in listOf("/api/v1/library-organization/ORIGINAL/1-1-1-1-1", "/api/v1/library-organization/original/$id")) {
            mvc.perform(get(target).with(user(owner))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_INVALID"))
        }
    }

    @Test fun `UTF16 bounds tag ordering and exact Unicode uniqueness round trip without normalization`() {
        val owner = reader(); val id = source(owner).recordId
        val bounds = LibraryOrganization("😀".repeat(100), (0..31).map { "😀".repeat(29) + it.toString().padStart(2, '0') }, false)
        write(owner, id, json.writeValueAsString(request(organization = bounds))).andExpect(status().isOk)
        assertEquals(bounds, service.get(owner, original, id).organization)
        val exact = value().copy(tags = listOf("Fantasy", "fantasy", "Ｆａｎｔａｓｙ", "é", "e\u0301"))
        write(owner, id, json.writeValueAsString(request(1, exact))).andExpect(status().isOk)
            .andExpect(jsonPath("$.organization.tags[2]").value("Ｆａｎｔａｓｙ"))
        assertEquals(exact, service.get(owner, original, id).organization)
    }

    @Test fun `invalid canonical fields are rejected in service and HTTP`() {
        val owner = reader(); val id = source(owner).recordId; val good = value()
        val invalid = listOf(good.copy(folder = "😀".repeat(100) + "x"), good.copy(folder = " Folder"),
            good.copy(folder = "Folder\ufeff"), good.copy(folder = "a\u0000b"), good.copy(folder = "a\u0085b"),
            good.copy(tags = List(33) { "tag$it" }), good.copy(tags = listOf("")), good.copy(tags = listOf("x".repeat(61))),
            good.copy(tags = listOf("same", "same")), good.copy(tags = listOf("\u00a0tag")), good.copy(tags = listOf("tag\u3000")),
            good.copy(tags = listOf("line\nbreak")))
        for (value in invalid) {
            write(owner, id, json.writeValueAsString(request(organization = value))).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_INVALID"))
            assertEquals("LIBRARY_ORGANIZATION_INVALID", assertThrows(LibraryOrganizationFailure::class.java) {
                service.put(owner, original, id, request(organization = value))
            }.code)
        }
        for (edge in listOf('\u0009', '\u000d', '\u0020', '\u00a0', '\u1680', '\u2000', '\u200a', '\u2028', '\u2029', '\u202f', '\u205f', '\u3000', '\ufeff')) {
            assertThrows(LibraryOrganizationFailure::class.java) { service.put(owner, original, id, request(organization = good.copy(folder = "a$edge"))) }
        }
        assertEquals(0, service.get(owner, original, id).version)
    }

    @Test fun `unpaired surrogates are rejected rather than silently replaced`() {
        val owner = reader(); val id = source(owner).recordId
        val good = json.writeValueAsString(request())
        for (escape in listOf("\\ud800", "\\udfff", "\\ud800x", "x\\udfff")) {
            write(owner, id, good.replace("Reading", escape)).andExpect(status().isBadRequest)
                .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_INVALID"))
        }
        for (folder in listOf("\ud800", "\udfff", "a\ud800a")) assertThrows(LibraryOrganizationFailure::class.java) {
            service.put(owner, original, id, request(organization = value(folder)))
        }
        assertEquals(0, service.get(owner, original, id).version)
    }

    @Test fun `strict JSON rejects missing unknown duplicate coerced fractional null and trailing data`() {
        val owner = reader(); val id = source(owner).recordId; val good = json.writeValueAsString(request())
        val invalid = listOf("{}", "null", "[]", good + "{}",
            """{"expectedVersion":0,"mutationId":"${UUID.randomUUID()}","organization":null}""",
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":0.5"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":null"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":-1"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":9223372036854775808"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"),
            good.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"unknown\":1"),
            good.replace("\"favorite\":true", "\"favorite\":\"true\""),
            good.replace("\"favorite\":true", "\"favorite\":1"),
            good.replace("\"favorite\":true", "\"favorite\":null"),
            good.replace("\"favorite\":true", "\"favorite\":true,\"favorite\":true"),
            good.replace("\"favorite\":true", "\"favorite\":true,\"unknown\":1"),
            good.replace("\"folder\":\"Reading\",", ""),
            good.replace("[\"Fantasy\",\"한국어\"]", "null"),
            good.replace("[\"Fantasy\",\"한국어\"]", "[3]"),
            good.replace(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"), "1-1-1-1-1"))
        for (body in invalid) write(owner, id, body).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_INVALID"))
        assertEquals(0, service.get(owner, original, id).version)
    }

    @Test fun `originals translations other records and account mutation identities stay independent`() {
        val owner = reader(); val chapter = source(owner); val second = source(owner)
        val translated = translations.save(owner, SaveTranslationRequest(chapter.providerId, chapter.bookId, chapter.chapterId,
            chapter.sourceRevision, "en", "ko", "test-provider", paragraphs = listOf(TranslatedParagraphRequest("p-1", "고요한 정원"))))
        val mutation = UUID.randomUUID()
        service.put(owner, original, chapter.recordId, request(organization = value("Original"), mutation = mutation))
        service.put(owner, LibraryOrganizationKind.TRANSLATION, translated.recordId, request(organization = value("Translation"), mutation = mutation))
        service.put(owner, original, second.recordId, request(organization = value("Second"), mutation = mutation))
        val other = reader(); val otherId = source(other).recordId
        service.put(other, original, otherId, request(organization = value("Other account"), mutation = mutation))
        assertEquals("Original", service.get(owner, original, chapter.recordId).organization!!.folder)
        assertEquals("Translation", service.get(owner, LibraryOrganizationKind.TRANSLATION, translated.recordId).organization!!.folder)
        assertEquals("Second", service.get(owner, original, second.recordId).organization!!.folder)
        jdbc.update("delete from translation_artifact where id=? and user_id=?", translated.recordId, owner)
        assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where record_id=?", Long::class.java, translated.recordId)!!)
        assertEquals(1, service.get(owner, original, chapter.recordId).version)
        jdbc.update("delete from source_chapter where id=? and user_id=?", chapter.recordId, owner)
        assertEquals(0L, jdbc.queryForObject("select count(*) from library_organization where record_id=?", Long::class.java, chapter.recordId)!!)
        mvc.perform(get(path(chapter.recordId)).with(user(owner))).andExpect(status().isNotFound)
    }

    @Test fun `PUT waiting for a document deletion returns not found without recreating organization`() {
        for (kind in LibraryOrganizationKind.entries) for (version in 0L..1L) {
            val owner = reader(); val id = document(owner, kind)
            if (version == 1L) service.put(owner, kind, id, request())
            val pool = Executors.newFixedThreadPool(2)
            val deleted = CountDownLatch(1); val commitDelete = CountDownLatch(1); val deletingPid = AtomicInteger()
            try {
                val deletion = pool.submit(Callable {
                    TransactionTemplate(transactionManager).execute {
                        deletingPid.set(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                        deleteDocument(owner, kind, id)
                        deleted.countDown()
                        check(commitDelete.await(20, TimeUnit.SECONDS))
                    }
                })
                assertTrue(deleted.await(10, TimeUnit.SECONDS))
                val writing = pool.submit(Callable {
                    mvc.perform(put(path(id, kind)).with(user(owner)).with(csrf()).contentType("application/json")
                        .content(json.writeValueAsString(request(version, value("Racing write")))))
                        .andExpect(status().isNotFound)
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_NOT_FOUND"))
                })
                // A latch alone only shows task start. Observe a real PostgreSQL lock wait before committing DELETE.
                awaitBlockedBy(deletingPid.get())
                assertFalse(writing.isDone)
                commitDelete.countDown()
                deletion.get(10, TimeUnit.SECONDS)
                writing.get(10, TimeUnit.SECONDS)
                assertDeleted(owner, kind, id)
            } finally {
                commitDelete.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `document deletion waits for an accepted PUT and cascades its committed organization`() {
        for (kind in LibraryOrganizationKind.entries) for (version in 0L..1L) {
            val owner = reader(); val id = document(owner, kind)
            if (version == 1L) service.put(owner, kind, id, request())
            val pool = Executors.newFixedThreadPool(2)
            val saved = CountDownLatch(1); val commitWrite = CountDownLatch(1); val writingPid = AtomicInteger()
            try {
                val writing = pool.submit(Callable {
                    TransactionTemplate(transactionManager).execute {
                        writingPid.set(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                        val result = service.put(owner, kind, id, request(version, value("Accepted write")))
                        saved.countDown()
                        check(commitWrite.await(20, TimeUnit.SECONDS))
                        result
                    }
                })
                assertTrue(saved.await(10, TimeUnit.SECONDS))
                val deletion = pool.submit(Callable {
                    TransactionTemplate(transactionManager).execute { deleteDocument(owner, kind, id) }
                })
                awaitBlockedBy(writingPid.get())
                assertFalse(deletion.isDone)
                commitWrite.countDown()
                val accepted = requireNotNull(writing.get(10, TimeUnit.SECONDS))
                assertEquals(version + 1, accepted.version)
                assertEquals(value("Accepted write"), accepted.organization)
                deletion.get(10, TimeUnit.SECONDS)
                assertDeleted(owner, kind, id)
            } finally {
                commitWrite.countDown()
                pool.shutdownNow()
                assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
            }
        }
    }

    @Test fun `version exhaustion is terminal but latest exact retry remains successful`() {
        val owner = reader(); val id = source(owner).recordId
        service.put(owner, original, id, request())
        jdbc.update("update library_organization set version=?,expected_version=? where user_id=? and record_id=?",
            MAX_LIBRARY_ORGANIZATION_VERSION - 1, MAX_LIBRARY_ORGANIZATION_VERSION - 2, owner, id)
        val finalRequest = request(MAX_LIBRARY_ORGANIZATION_VERSION - 1, value("Last"))
        val saved = service.put(owner, original, id, finalRequest)
        assertEquals(MAX_LIBRARY_ORGANIZATION_VERSION, saved.version)
        assertEquals(saved, service.put(owner, original, id, finalRequest))
        write(owner, id, json.writeValueAsString(request())).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_EXHAUSTED")).andExpect(jsonPath("$.current").doesNotExist())
        assertEquals(saved, service.get(owner, original, id))
    }

    @Test fun `body limit applies with known and unknown content lengths`() {
        val owner = reader(); val id = source(owner).recordId; val body = " ".repeat(8193)
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
        assertEquals(0, service.get(owner, original, id).version)
    }

    @Test fun `rate limiting spans documents preserves reads and expires without evicting active accounts`() {
        val owner = reader(); val id = source(owner).recordId; val second = source(owner).recordId
        val body = json.writeValueAsString(request())
        repeat(120) { write(owner, if (it % 2 == 0) id else second, body).andExpect(status().isOk) }
        write(owner, second, body).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("LIBRARY_ORGANIZATION_LIMIT"))
            .andExpect(header().exists("Retry-After")).andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(get(path(id)).with(user(owner))).andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        var time = 1000L; val limits = LibraryOrganizationWriteLimits { time }
        repeat(120) { limits.acquire(owner) }
        assertEquals(60, assertThrows(LibraryOrganizationFailure::class.java) { limits.acquire(owner) }.retryAfterSeconds)
        repeat(9999) { limits.acquire("other-$it") }
        assertThrows(LibraryOrganizationFailure::class.java) { limits.acquire("overflow") }
        assertDoesNotThrow { limits.acquire("other-0") }
        time += 60_000
        assertDoesNotThrow { limits.acquire(owner); limits.acquire("overflow") }
    }
}
