package com.dongholab.pagetuner.server.notes

import com.dongholab.pagetuner.server.ApiRequestBodyLimit
import com.dongholab.pagetuner.server.progress.MAX_PROGRESS_VERSION
import com.dongholab.pagetuner.server.progress.ReadingProgressAnchor
import com.dongholab.pagetuner.server.progress.ReadingProgressKind
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.dongholab.pagetuner.server.translation.SaveTranslationRequest
import com.dongholab.pagetuner.server.translation.TranslatedParagraphRequest
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.SourceParagraph
import com.dongholab.pagetuner.server.workflow.StoredChapter
import com.dongholab.pagetuner.server.workflow.UploadedChapterRequest
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
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

@SpringBootTest(properties = ["spring.security.user.password=reading-note-test-password"])
@AutoConfigureMockMvc
class ReadingNoteIntegrationTest {
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
    @Autowired lateinit var service: ReadingNoteService
    @Autowired lateinit var chapters: SourceChapterStore
    @Autowired lateinit var translations: TranslationApplicationService
    private val original = ReadingProgressKind.ORIGINAL
    private val timestamp = Instant.parse("2026-09-16T00:00:00Z")
    private fun owner() = "notes-${UUID.randomUUID()}"
    private fun source(user: String, texts: List<String> = listOf("A😀 quiet garden.", "The reader continues.")): StoredChapter =
        chapters.upload(user, UploadedChapterRequest(UUID.randomUUID().toString(), "Notes book", "chapter-1", "First chapter", "en",
            texts.mapIndexed { index, text -> SourceParagraph("p-${index + 1}", index, text) }))
    private fun note(kind: ReadingNoteKind = ReadingNoteKind.NOTE, text: String = "My note.", anchor: ReadingProgressAnchor = ReadingProgressAnchor("p-1", 0),
                     range: ReadingNoteRange? = null) = ReadingNoteInput(kind, "A note", text, anchor, range, timestamp)
    private fun request(version: Long = 0, note: ReadingNoteInput? = note(), mutation: UUID = UUID.randomUUID()) =
        PutReadingNoteRequest(version, mutation, note == null, note)
    private fun path(recordId: UUID, kind: ReadingProgressKind = original) = "/api/v1/reading-notes/${kind.name}/$recordId"
    private fun write(user: String, recordId: UUID, noteId: UUID, input: PutReadingNoteRequest) = writeJson(user, path(recordId) + "/$noteId", json.writeValueAsString(input))
    private fun writeJson(user: String, path: String, body: String) = mvc.perform(put(path).with(user(user)).with(csrf()).contentType("application/json").content(body))

    @Test fun `empty owned feed and saved note have exact required nullable fields and derived excerpts`() {
        val user = owner(); val chapter = source(user); val id = UUID.randomUUID()
        val empty = mvc.perform(get(path(chapter.recordId)).with(user(user))).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.items").isEmpty)
            .andExpect(jsonPath("$.watermark").value(0)).andExpect(jsonPath("$.nextAfterRevision").value(0))
            .andExpect(jsonPath("$.hasMore").value(false)).andReturn()
        assertEquals(setOf("kind", "recordId", "items", "watermark", "nextAfterRevision", "hasMore"), json.readTree(empty.response.contentAsString).fieldNames().asSequence().toSet())
        val result = write(user, chapter.recordId, id, request()).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.version").value(1))
            .andExpect(jsonPath("$.changeRevision").value(1)).andExpect(jsonPath("$.note.excerpt").value("A😀 quiet garden."))
            .andReturn()
        val item = json.readTree(result.response.contentAsString)
        assertEquals(setOf("noteId", "version", "changeRevision", "deleted", "note", "updatedAt"), item.fieldNames().asSequence().toSet())
        assertTrue(item["note"].has("range") && item["note"]["range"].isNull)
        assertEquals(item, json.readTree(json.writeValueAsString(service.changes(user, original, chapter.recordId).items.single())))
    }

    @Test fun `highlight spans source paragraphs exactly and never accepts client supplied excerpt`() {
        val user = owner(); val chapter = source(user); val id = UUID.randomUUID()
        val anchor = ReadingProgressAnchor("p-1", 3)
        val selection = note(ReadingNoteKind.HIGHLIGHT, "", anchor, ReadingNoteRange(anchor, ReadingProgressAnchor("p-2", 3)))
        val result = service.put(user, original, chapter.recordId, id, request(note = selection))
        assertEquals(" quiet garden.\n\nThe", result.note!!.excerpt)
        val body = json.writeValueAsString(request()).replace("\"title\":\"A note\"", "\"title\":\"A note\",\"excerpt\":\"Spoofed\"")
        writeJson(user, path(chapter.recordId) + "/${UUID.randomUUID()}", body).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READING_NOTE_INVALID"))
    }

    @Test fun `fixed watermark history pagination remains complete while notes change and delete`() {
        val user = owner(); val record = source(user).recordId; val a = UUID.randomUUID(); val b = UUID.randomUUID()
        val a1 = service.put(user, original, record, a, request())
        val b1 = service.put(user, original, record, b, request(note = note(text = "B version 1")))
        val a2 = service.put(user, original, record, a, request(1, note(text = "A version 2")))
        val first = service.changes(user, original, record, limit = 1)
        assertEquals(listOf(a1), first.items); assertEquals(3L, first.watermark); assertTrue(first.hasMore)
        val deleted = service.put(user, original, record, b, request(1, null))
        val a3 = service.put(user, original, record, a, request(2, note(text = "A version 3")))
        val second = service.changes(user, original, record, first.nextAfterRevision, first.watermark, 1)
        val third = service.changes(user, original, record, second.nextAfterRevision, first.watermark, 1)
        assertEquals(listOf(b1), second.items); assertTrue(second.hasMore)
        assertEquals(listOf(a2), third.items); assertFalse(third.hasMore); assertEquals(3L, third.nextAfterRevision)
        val following = service.changes(user, original, record, third.nextAfterRevision)
        assertEquals(listOf(deleted, a3), following.items); assertEquals(5L, following.watermark)
        assertTrue(following.items.first().deleted); assertNull(following.items.first().note)
        assertEquals(5L, service.changes(user, original, record, 5).nextAfterRevision)
    }

    @Test fun `offline delete before creation makes a tombstone that only explicit restore can replace`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID()
        val deleted = service.put(user, original, record, id, request(note = null))
        assertEquals(1L, deleted.version); assertTrue(deleted.deleted); assertNull(deleted.note)
        write(user, record, id, request()).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("READING_NOTE_CONFLICT")).andExpect(jsonPath("$.current.version").value(1))
            .andExpect(jsonPath("$.current.deleted").value(true))
        val restored = service.put(user, original, record, id, request(1))
        assertEquals(2L, restored.version); assertFalse(restored.deleted)
        assertEquals(listOf(deleted, restored), service.changes(user, original, record).items)
    }

    @Test fun `latest mutation retry does not increment item document revision or timestamps`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID(); val firstRequest = request()
        val saved = service.put(user, original, record, id, firstRequest)
        assertEquals(saved, service.put(user, original, record, id, firstRequest))
        assertEquals(1, service.changes(user, original, record).items.size)
        for (different in listOf(firstRequest.copy(expectedVersion = 1), firstRequest.copy(note = note(text = "Changed")), firstRequest.copy(deleted = true, note = null))) {
            write(user, record, id, different).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("READING_NOTE_MUTATION_REUSED"))
        }
        service.put(user, original, record, id, request(1))
        write(user, record, id, firstRequest).andExpect(status().isConflict).andExpect(jsonPath("$.current.version").value(2))
    }

    @Test fun `stale expected version on a never created ID returns explicit version zero empty current`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID()
        val response = write(user, record, id, request(3)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.current.noteId").value(id.toString())).andExpect(jsonPath("$.current.version").value(0))
            .andExpect(jsonPath("$.current.changeRevision").value(0)).andExpect(jsonPath("$.current.deleted").value(true)).andReturn()
        val current = json.readTree(response.response.contentAsString)["current"]
        assertTrue(current.has("note") && current["note"].isNull)
        assertTrue(current.has("updatedAt") && current["updatedAt"].isNull)
        assertEquals(0L, service.changes(user, original, record).watermark)
    }

    @Test fun `same note concurrent writes have one winner while independent notes all receive ordered revisions`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID()
        fun race(sameNote: Boolean): List<String> {
            val pool = Executors.newFixedThreadPool(6); val ready = CountDownLatch(6); val go = CountDownLatch(1)
            return try {
                val futures = (1..6).map { index -> pool.submit(Callable {
                    ready.countDown(); check(go.await(10, TimeUnit.SECONDS))
                    try { service.put(user, original, record, if (sameNote) id else UUID.randomUUID(), request(note = note(text = "Concurrent $index"))); "ok" }
                    catch (error: ReadingNoteFailure) { error.code }
                }) }
                assertTrue(ready.await(10, TimeUnit.SECONDS)); go.countDown()
                futures.map { it.get(20, TimeUnit.SECONDS) }
            } finally { go.countDown(); pool.shutdownNow() }
        }
        val shared = race(true)
        assertEquals(1, shared.count { it == "ok" }); assertEquals(5, shared.count { it == "READING_NOTE_CONFLICT" })
        assertEquals(List(6) { "ok" }, race(false))
        val feed = service.changes(user, original, record)
        assertEquals((1L..7L).toList(), feed.items.map { it.changeRevision }); assertEquals(7L, feed.watermark)
    }

    @Test fun `missing foreign and wrong kind documents do not expose notes or create tombstones`() {
        val user = owner(); val other = owner(); val record = source(user).recordId; val id = UUID.randomUUID()
        service.put(user, original, record, id, request())
        for (target in listOf(path(record), path(UUID.randomUUID()), path(record, ReadingProgressKind.TRANSLATION))) {
            mvc.perform(get(target).with(user(other))).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("READING_NOTE_NOT_FOUND"))
            writeJson(other, "$target/$id", json.writeValueAsString(request(note = null))).andExpect(status().isNotFound)
                .andExpect(jsonPath("$.code").value("READING_NOTE_NOT_FOUND"))
        }
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_note_document where user_id=?", Long::class.java, other)!!)
    }

    @Test fun `authentication csrf canonical paths and bounded cursor inputs are required`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID(); val path = path(record)
        mvc.perform(get(path)).andExpect(status().isUnauthorized)
        mvc.perform(put("$path/$id").with(user(user)).contentType("application/json").content(json.writeValueAsString(request())))
            .andExpect(status().isForbidden)
        mvc.perform(put("$path/$id").with(csrf()).contentType("application/json").content(json.writeValueAsString(request())))
            .andExpect(status().isUnauthorized)
        for (query in listOf("afterRevision=-1", "afterRevision=1", "afterRevision=0.5", "afterRevision=9007199254740992", "untilRevision=1", "untilRevision=-1", "limit=0", "limit=101", "limit=1.2")) {
            mvc.perform(get("$path?$query").with(user(user))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("READING_NOTE_INVALID"))
        }
        mvc.perform(get("/api/v1/reading-notes/ORIGINAL/1-1-1-1-1").with(user(user))).andExpect(status().isBadRequest)
        writeJson(user, "$path/1-1-1-1-1", json.writeValueAsString(request())).andExpect(status().isBadRequest)
    }

    @Test fun `text limits kind range agreement UTF16 boundaries and exact selection are validated`() {
        val user = owner(); val record = source(user, listOf("A😀 quiet garden.", "x".repeat(5000))).recordId
        val start = ReadingProgressAnchor("p-1", 0)
        val range = ReadingNoteRange(start, ReadingProgressAnchor("p-1", 3))
        val invalid = listOf(note().copy(title = " "), note().copy(title = "x".repeat(201)), note(text = "x".repeat(4001)), note(text = " "),
            note(anchor = ReadingProgressAnchor("missing", 0)), note(anchor = ReadingProgressAnchor("p-1", 2)), note(anchor = ReadingProgressAnchor("p-1", -1)),
            note(anchor = ReadingProgressAnchor("p-1", "A😀 quiet garden.".length)), note().copy(range = range),
            note(ReadingNoteKind.HIGHLIGHT, ""), note(ReadingNoteKind.HIGHLIGHT, "", start, range.copy(end = start)),
            note(ReadingNoteKind.HIGHLIGHT, "", start, range.copy(end = ReadingProgressAnchor("p-1", 2))),
            note(ReadingNoteKind.HIGHLIGHT, "", start, range.copy(start = ReadingProgressAnchor("p-1", 1))),
            note(ReadingNoteKind.HIGHLIGHT, "", start, range.copy(end = ReadingProgressAnchor("p-2", 4000))),
            note(ReadingNoteKind.HIGHLIGHT, "", ReadingProgressAnchor("p-2", 1), ReadingNoteRange(ReadingProgressAnchor("p-2", 1), start)))
        for (value in invalid) write(user, record, UUID.randomUUID(), request(note = value)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READING_NOTE_INVALID"))
        val end = ReadingProgressAnchor("p-2", 4000)
        write(user, record, UUID.randomUUID(), request(note = note(ReadingNoteKind.HIGHLIGHT, "", end.copy(characterOffset = 0), ReadingNoteRange(end.copy(characterOffset = 0), end))))
            .andExpect(status().isOk).andExpect(jsonPath("$.note.excerpt").value("x".repeat(4000)))
    }

    @Test fun `ordinary excerpts do not split surrogate pairs and creation metadata may be explicitly edited`() {
        val user = owner(); val record = source(user, listOf("a".repeat(999) + "😀 rest")).recordId; val id = UUID.randomUUID()
        val created = service.put(user, original, record, id, request(note = note(ReadingNoteKind.BOOKMARK, "")))
        assertEquals("a".repeat(999), created.note!!.excerpt)
        val later = timestamp.plusSeconds(5)
        val edited = service.put(user, original, record, id, request(1, note().copy(createdAt = later)))
        assertEquals(later, edited.note!!.createdAt)
    }

    @Test fun `translations validate against target text and cascade notes independently of source records`() {
        val user = owner(); val chapter = source(user)
        val translated = translations.save(user, SaveTranslationRequest(chapter.providerId, chapter.bookId, chapter.chapterId, chapter.sourceRevision,
            "en", "ko", "test-provider", paragraphs = listOf(TranslatedParagraphRequest("p-1", "정원"), TranslatedParagraphRequest("p-2", "책"))))
        val id = UUID.randomUUID()
        service.put(user, original, chapter.recordId, id, request())
        val target = service.put(user, ReadingProgressKind.TRANSLATION, translated.recordId, id, request())
        assertEquals("정원", target.note!!.excerpt)
        writeJson(user, path(translated.recordId, ReadingProgressKind.TRANSLATION) + "/${UUID.randomUUID()}",
            json.writeValueAsString(request(note = note(anchor = ReadingProgressAnchor("p-1", 3)))))
            .andExpect(status().isBadRequest)
        jdbc.update("delete from translation_artifact where id=?", translated.recordId)
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_note_change where record_id=?", Long::class.java, translated.recordId)!!)
        assertEquals(1, service.changes(user, original, chapter.recordId).items.size)
        jdbc.update("delete from source_chapter where id=?", chapter.recordId)
        assertEquals(0L, jdbc.queryForObject("select count(*) from reading_note_current where record_id=?", Long::class.java, chapter.recordId)!!)
    }

    @Test fun `strict JSON rejects duplicate unknown coerced invalid unicode and non UTC timestamp fields`() {
        val user = owner(); val record = source(user).recordId
        val body = json.writeValueAsString(request())
        val invalid = listOf("null", "[]", "{}", body + "{}", body.replace("\"expectedVersion\":0", "\"expectedVersion\":\"0\""),
            body.replace("\"expectedVersion\":0", "\"expectedVersion\":0.1"), body.replace("\"expectedVersion\":0", "\"expectedVersion\":9007199254740991"),
            body.replace("\"expectedVersion\":0", "\"expectedVersion\":0,\"expectedVersion\":0"), body.replace("\"expectedVersion\":0", "\"unknown\":true,\"expectedVersion\":0"),
            body.replace("\"deleted\":false", "\"deleted\":\"false\""), body.replace("\"deleted\":false", "\"deleted\":true"),
            body.replace("\"title\":\"A note\"", "\"title\":\"\\ud800\""), body.replace("\"title\":\"A note\"", "\"title\":\"\\udfff\""),
            body.replace("\"characterOffset\":0", "\"characterOffset\":0.1"), body.replace("\"characterOffset\":0", "\"characterOffset\":2147483648"),
            body.replace("2026-09-16T00:00:00Z", "2026-09-16T00:00:00+09:00"), body.replace("2026-09-16T00:00:00Z", "2026-02-30T00:00:00Z"),
            body.replace("2026-09-16T00:00:00Z", "2026-09-16T24:00:00Z"), body.replace("2026-09-16T00:00:00Z", "2026-09-16T23:59:60Z"),
            body.replace("2026-09-16T00:00:00Z", "0000-09-16T00:00:00Z"), body.replace("2026-09-16T00:00:00Z", "2026-09-16T00:00:00.1234567890Z"))
        for (value in invalid) writeJson(user, path(record) + "/${UUID.randomUUID()}", value).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("READING_NOTE_INVALID"))
        assertEquals(0L, service.changes(user, original, record).watermark)
    }

    @Test fun `body size is bounded before parsing including missing content length`() {
        val user = owner(); val record = source(user).recordId; val path = path(record) + "/${UUID.randomUUID()}"; val body = " ".repeat(32 * 1024 + 1)
        mvc.perform(put(path).servletPath(path).with(user(user)).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isPayloadTooLarge).andExpect(header().string("Cache-Control", "no-store"))
        val request = object : MockHttpServletRequest() {
            override fun getContentLength(): Int = -1
            override fun getContentLengthLong(): Long = -1
        }.apply { method = "PUT"; servletPath = path; setContent(body.toByteArray()) }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(request, response) { _, _ -> fail<Unit>("Oversized note reached JSON binding.") }
        assertEquals(413, response.status)
    }

    @Test fun `rate limit reports retry after without creating duplicate revisions`() {
        val user = owner(); val record = source(user).recordId; val id = UUID.randomUUID(); val request = request()
        repeat(120) { write(user, record, id, request).andExpect(status().isOk) }
        write(user, record, id, request).andExpect(status().isTooManyRequests).andExpect(jsonPath("$.code").value("READING_NOTE_LIMIT"))
            .andExpect(header().exists("Retry-After"))
        assertEquals(1L, service.changes(user, original, record).watermark)
        var now = 1000L; val limits = ReadingNoteWriteLimits { now }
        repeat(120) { limits.acquire(user) }; assertThrows(ReadingNoteFailure::class.java) { limits.acquire(user) }
        now += 60_000; assertDoesNotThrow { limits.acquire(user) }
    }

    @Test fun `exhausted safe integer document revision rejects writes without advancing history`() {
        val user = owner(); val record = source(user).recordId
        service.put(user, original, record, UUID.randomUUID(), request())
        jdbc.update("update reading_note_document set revision=? where user_id=? and kind=? and record_id=?", MAX_PROGRESS_VERSION, user, original.name, record)
        write(user, record, UUID.randomUUID(), request()).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("READING_NOTE_REVISION_EXHAUSTED")).andExpect(jsonPath("$.current").doesNotExist())
        assertEquals(1L, jdbc.queryForObject("select count(*) from reading_note_change where record_id=?", Long::class.java, record)!!)
    }
}
