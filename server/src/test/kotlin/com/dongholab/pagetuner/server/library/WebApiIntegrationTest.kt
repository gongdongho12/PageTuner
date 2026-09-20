package com.dongholab.pagetuner.server.library

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest(properties = ["spring.security.user.name=reader", "spring.security.user.password=test-password"])
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class WebApiIntegrationTest {
    companion object {
        @Container @JvmStatic val postgres = PostgreSQLContainer("postgres:17-alpine")
        @JvmStatic @DynamicPropertySource
        fun database(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var library: LibraryService

    @BeforeEach
    fun clean() {
        jdbc.execute("truncate library_bookmark, library_progress, library_chapter, library_book, translation_backup, translation_artifact cascade")
    }
    private val bookBody = """{"title":"웹에서 읽는 책","author":"작가","sourceLanguage":"ko","chapters":[
        {"title":"첫 장","paragraphs":[{"paragraphId":"p1","text":"첫 번째 문단입니다."},{"paragraphId":"p2","text":"다음 문단"}]},
        {"title":"두 번째 장","paragraphs":[{"paragraphId":"p3","text":"끝"}]}]}"""
    private fun importedBook(): String = mapper.readTree(mvc.perform(post("/api/v1/library/books")
        .with(user("reader")).with(csrf()).contentType("application/json").content(bookBody))
        .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
    private fun firstChapter(bookId: String): JsonNode {
        val id = mapper.readTree(mvc.perform(get("/api/v1/library/books/$bookId/chapters").with(user("reader")))
            .andExpect(status().isOk).andReturn().response.contentAsString)["items"][0]["id"].asText()
        return mapper.readTree(mvc.perform(get("/api/v1/library/books/$bookId/chapters/$id").with(user("reader")))
            .andExpect(status().isOk).andReturn().response.contentAsString)
    }

    @Test
    fun `browser login rotates session, refreshes CSRF, and logout ends access`() {
        val bootstrap = mvc.perform(get("/api/v1/csrf")).andExpect(status().isOk).andReturn()
        val session = bootstrap.request.getSession(false) as MockHttpSession
        val token = mapper.readTree(bootstrap.response.contentAsString)["token"].asText()
        val before = session.id
        mvc.perform(post("/api/v1/session").session(session).param("username", "reader").param("password", "test-password"))
            .andExpect(status().isForbidden)
        mvc.perform(post("/api/v1/session").session(session).header("X-CSRF-TOKEN", token)
            .param("username", "reader").param("password", "wrong")).andExpect(status().isUnauthorized)
        mvc.perform(post("/api/v1/session").session(session).header("X-CSRF-TOKEN", token)
            .param("username", "reader").param("password", "test-password")).andExpect(status().isNoContent)
        assertNotEquals(before, session.id)
        mvc.perform(get("/api/v1/session").session(session)).andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("reader"))
        mvc.perform(post("/api/v1/library/books").session(session).header("X-CSRF-TOKEN", token)
            .contentType("application/json").content(bookBody)).andExpect(status().isForbidden)
        val refreshed = mapper.readTree(mvc.perform(get("/api/v1/csrf").session(session))
            .andReturn().response.contentAsString)["token"].asText()
        mvc.perform(post("/api/v1/library/books").session(session).header("X-CSRF-TOKEN", refreshed)
            .contentType("application/json").content(bookBody)).andExpect(status().isCreated)
        mvc.perform(post("/api/v1/session/logout").session(session).header("X-CSRF-TOKEN", refreshed))
            .andExpect(status().isNoContent)
        assertTrue(session.isInvalid)
        mvc.perform(get("/api/v1/session")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `frontend Basic authentication and CSRF remain compatible through its proxy origin`() {
        val bootstrap = mvc.perform(get("/api/v1/csrf").with(httpBasic("reader", "test-password"))
            .header("Origin", "http://127.0.0.1:3000")).andExpect(status().isOk).andReturn()
        val session = bootstrap.request.getSession(false) as MockHttpSession
        val token = mapper.readTree(bootstrap.response.contentAsString)["token"].asText()
        val body = """{"contentProviderId":"pageturner-web","bookId":"book","chapterId":"page-0",
            "sourceRevision":"source-v1","sourceLanguage":"en","targetLanguage":"ko",
            "translationProviderId":"manual","paragraphs":[{"paragraphId":"page-0-text","text":"번역"}]}"""
        mvc.perform(post("/api/v1/translations").session(session).with(httpBasic("reader", "test-password"))
            .header("Origin", "http://127.0.0.1:3000").header("X-CSRF-TOKEN", token)
            .contentType("application/json").content(body)).andExpect(status().isCreated)
        mvc.perform(get("/api/v1/translations").with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk).andExpect(jsonPath("$.totalItems").value(1))
    }

    @Test
    fun `credentialed CORS permits configured frontend and rejects unknown origin`() {
        mvc.perform(options("/api/v1/library/books").header("Origin", "http://127.0.0.1:3000")
            .header("Access-Control-Request-Method", "POST").header("Access-Control-Request-Headers", "content-type,x-csrf-token"))
            .andExpect(status().isOk).andExpect(header().string("Access-Control-Allow-Credentials", "true"))
            .andExpect(header().string("Access-Control-Allow-Origin", "http://127.0.0.1:3000"))
        mvc.perform(options("/api/v1/library/books").header("Origin", "https://unknown.example")
            .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden)
    }

    @Test
    fun `import, search, chapter paging and source contents persist`() {
        val id = importedBook()
        mvc.perform(get("/api/v1/library/books").with(user("reader")).param("query", "웹에서"))
            .andExpect(status().isOk).andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].chapterCount").value(2))
        mvc.perform(get("/api/v1/library/books/$id/chapters").with(user("reader")).param("page", "1").param("size", "1"))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].ordinal").value(1))
            .andExpect(jsonPath("$.totalPages").value(2))
        assertEquals("첫 번째 문단입니다.", firstChapter(id)["paragraphs"][0]["text"].asText())
        mvc.perform(get("/api/v1/library/books").with(user("reader")).param("size", "101"))
            .andExpect(status().isBadRequest)
        mvc.perform(get("/api/v1/library/books").with(user("reader")).param("page", "-1"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `another user cannot read or modify book, chapters, progress or bookmarks`() {
        val id = importedBook()
        val chapterId = firstChapter(id)["id"].asText()
        mvc.perform(get("/api/v1/library/books").with(user("other"))).andExpect(jsonPath("$.totalItems").value(0))
        listOf("", "/chapters", "/chapters/$chapterId", "/progress", "/bookmarks").forEach { suffix ->
            mvc.perform(get("/api/v1/library/books/$id$suffix").with(user("other"))).andExpect(status().isNotFound)
        }
        val anchor = """{"chapterId":"$chapterId","paragraphId":"p1","characterOffset":2}"""
        mvc.perform(put("/api/v1/library/books/$id/progress").with(user("other")).with(csrf())
            .contentType("application/json").content("""{"anchor":$anchor,"version":0}"""))
            .andExpect(status().isNotFound)
        mvc.perform(post("/api/v1/library/books/$id/bookmarks").with(user("other")).with(csrf())
            .contentType("application/json").content("""{"anchor":$anchor}"""))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `position validates anchors and rejects stale writes without overwriting`() {
        val id = importedBook()
        val chapterId = firstChapter(id)["id"].asText()
        val path = "/api/v1/library/books/$id/progress"
        mvc.perform(get(path).with(user("reader"))).andExpect(jsonPath("$.version").value(0))
        val body = """{"anchor":{"chapterId":"$chapterId","paragraphId":"p1","characterOffset":2},"version":0}"""
        mvc.perform(put(path).with(user("reader")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isOk).andExpect(jsonPath("$.version").value(1))
        mvc.perform(put(path).with(user("reader")).with(csrf()).contentType("application/json").content(body))
            .andExpect(status().isConflict)
        mvc.perform(put(path).with(user("reader")).with(csrf()).contentType("application/json")
            .content(body.replace("\"characterOffset\":2", "\"characterOffset\":9999")))
            .andExpect(status().isBadRequest)
        mvc.perform(get(path).with(user("reader"))).andExpect(jsonPath("$.anchor.characterOffset").value(2))
            .andExpect(jsonPath("$.version").value(1))
    }

    @Test
    fun `simultaneous first progress writes accept only one version`() {
        val bookId = UUID.fromString(importedBook())
        val chapterId = UUID.fromString(firstChapter(bookId.toString())["id"].asText())
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val jobs = (1..2).map { offset -> executor.submit(Callable {
                check(start.await(10, TimeUnit.SECONDS))
                try {
                    library.saveProgress("reader", bookId, SaveProgressRequest(AnchorRequest(chapterId, "p1", offset), 0))
                    "saved"
                } catch (_: ProgressConflict) { "conflict" }
            }) }
            start.countDown()
            assertEquals(listOf("conflict", "saved"), jobs.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(1L, library.progress("reader", bookId).version)
        } finally { executor.shutdownNow() }
    }

    @Test
    fun `anchor from a different book is rejected`() {
        val bookId = importedBook()
        val otherChapterId = firstChapter(importedBook())["id"].asText()
        mvc.perform(put("/api/v1/library/books/$bookId/progress").with(user("reader")).with(csrf())
            .contentType("application/json").content("""{"anchor":{"chapterId":"$otherChapterId","paragraphId":"p1"},"version":0}"""))
            .andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/library/books/$bookId/progress").with(user("reader")))
            .andExpect(jsonPath("$.version").value(0))
    }

    @Test
    fun `bookmarks persist and deletion is scoped to owner`() {
        val id = importedBook()
        val chapterId = firstChapter(id)["id"].asText()
        val path = "/api/v1/library/books/$id/bookmarks"
        val bookmark = mapper.readTree(mvc.perform(post(path).with(user("reader")).with(csrf())
            .contentType("application/json").content("""{"anchor":{"chapterId":"$chapterId","paragraphId":"p1"},"note":"다시 읽기"}"""))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["id"].asText()
        mvc.perform(get(path).with(user("reader"))).andExpect(jsonPath("$.items[0].note").value("다시 읽기"))
        mvc.perform(delete("$path/$bookmark").with(user("other")).with(csrf())).andExpect(status().isNotFound)
        mvc.perform(delete("$path/$bookmark").with(user("reader")).with(csrf())).andExpect(status().isNoContent)
        mvc.perform(get(path).with(user("reader"))).andExpect(jsonPath("$.totalItems").value(0))
    }

    @Test
    fun `invalid duplicate paragraphs roll back the complete import`() {
        mvc.perform(post("/api/v1/library/books").with(user("reader")).with(csrf())
            .contentType("application/json").content(bookBody.replace("\"p3\"", "\"p1\"")
                .replace("\"p2\"", "\"p1\""))).andExpect(status().isBadRequest)
        assertEquals(0, jdbc.queryForObject("select count(*) from library_book", Int::class.java)!!)
    }

    @Test
    fun `saved translation can be discovered, backed up, and restored without duplicate writes`() {
        val bookId = importedBook()
        val chapter = firstChapter(bookId)
        val request = mapOf("contentProviderId" to "library", "bookId" to bookId, "chapterId" to chapter["id"].asText(),
            "sourceRevision" to chapter["sourceRevision"].asText(), "sourceLanguage" to "ko", "targetLanguage" to "en",
            "translationProviderId" to "manual", "paragraphs" to listOf(mapOf("paragraphId" to "p1", "text" to "First paragraph.")))
        val record = mapper.readTree(mvc.perform(post("/api/v1/translations").with(user("reader")).with(csrf())
            .contentType("application/json").content(mapper.writeValueAsBytes(request)))
            .andExpect(status().isCreated).andReturn().response.contentAsString)["recordId"].asText()
        mvc.perform(get("/api/v1/translations").with(user("reader")).param("contentProviderId", "library")
            .param("bookId", bookId).param("sourceRevision", chapter["sourceRevision"].asText()))
            .andExpect(status().isOk).andExpect(jsonPath("$.items[0].recordId").value(record))
        mvc.perform(get("/api/v1/translations").with(user("other"))).andExpect(jsonPath("$.totalItems").value(0))
        val backup = mvc.perform(get("/api/v1/translations/$record/backup").with(user("reader")))
            .andExpect(status().isOk).andReturn().response.contentAsByteArray
        mvc.perform(post("/api/v1/translations/restore").with(user("reader")).with(csrf())
            .contentType("application/json").content(backup)).andExpect(status().isOk)
            .andExpect(jsonPath("$.recordId").value(record)).andExpect(jsonPath("$.created").value(false))
        assertEquals(1, jdbc.queryForObject("select count(*) from translation_artifact", Int::class.java)!!)
    }
}
