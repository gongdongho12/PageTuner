package com.dongholab.pagetuner.server.identity

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.server.ApiRequestBodyLimit
import com.dongholab.pagetuner.server.translation.*
import com.dongholab.pagetuner.server.workflow.*
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=library-identity-test-password"])
@AutoConfigureMockMvc
class LibraryIdentityIntegrationTest {
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
    @Autowired lateinit var jdbc: JdbcTemplate
    @Autowired lateinit var json: ObjectMapper
    @Autowired lateinit var sources: SourceChapterStore
    @Autowired lateinit var translations: TranslationApplicationService
    private val path = "/api/v1/library-identity/verify"
    private fun owner() = "identity-${UUID.randomUUID()}"
    private fun source(owner: String, bookId: String = "book:${UUID.randomUUID()}") = sources.upload(owner,
        UploadedChapterRequest(bookId, "Book", "chapter|one", "One", "en", listOf(SourceParagraph("p:1", 0, " Hello 🌏\nX|Y "), SourceParagraph("p|2", 1, "끝"))))
    private fun body(kind: DocumentIdentityKind, recordId: UUID, identity: DocumentIdentity) =
        """{"kind":"${kind.name}","recordId":"$recordId","identity":${DocumentIdentityJson.encode(identity)}}"""
    private fun request(owner: String, content: String) = mvc.perform(post(path).servletPath(path).with(user(owner)).with(csrf()).contentType("application/json").content(content))
    private fun translation(owner: String, source: StoredChapter) = translations.save(owner,
        SaveTranslationRequest(source.providerId, source.bookId, source.chapterId, "source-v1", "auto", "ko", "provider|one", "", "", "",
            listOf(TranslatedParagraphRequest("p:1", " 번역 🌏\nX|Y "), TranslatedParagraphRequest("p|2", "끝"))))
    private fun identity(value: TranslationResponse): DocumentIdentity {
        val artifact = SaveTranslationRequest(value.contentProviderId, value.bookId, value.chapterId, value.sourceRevision,
            value.sourceLanguage, value.targetLanguage, value.translationProviderId, value.modelId, value.promptRevision,
            value.glossaryRevision, value.paragraphs).toArtifact()
        return DocumentIdentities.translation(artifact)
    }

    @Test fun `original verification is exact read only and returns no null translation fields`() {
        val owner = owner(); val source = source(owner, "書".repeat(2000)); val identity = DocumentIdentities.original(source.content())
        val response = request(owner, body(DocumentIdentityKind.ORIGINAL, source.recordId, identity)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.verified").value(true))
            .andExpect(jsonPath("$.recordId").value(source.recordId.toString())).andExpect(jsonPath("$.identity.targetLanguage").doesNotExist())
            .andReturn().response.contentAsString
        assertEquals(DocumentIdentityJson.encode(identity).toString(), DocumentIdentityJson.encode(DocumentIdentityJson.decode(org.json.JSONObject(json.readTree(response)["identity"].toString()))).toString())
        for (table in listOf("reading_progress", "reading_note_document", "reading_note_current", "library_organization", "book_glossary"))
            assertEquals(0L, jdbc.queryForObject("select count(*) from $table where user_id=?", Long::class.java, owner))
        assertEquals(1L, jdbc.queryForObject("select count(*) from source_chapter where user_id=?", Long::class.java, owner))
    }

    @Test fun `every original identity field and paragraph fingerprint must match`() {
        val owner = owner(); val source = source(owner); val expected = DocumentIdentities.original(source.content())
        val variants = listOf(expected.copy(contentProviderId = "another"), expected.copy(bookId = expected.bookId + " "),
            expected.copy(chapterId = "another"), expected.copy(sourceLanguage = "EN"), expected.copy(sourceRevision = "0".repeat(64)),
            expected.copy(paragraphHash = "0".repeat(64)))
        for (identity in variants) request(owner, body(DocumentIdentityKind.ORIGINAL, source.recordId, identity)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_MISMATCH"))
    }

    @Test fun `translation verifies full variant and payload with foreign missing and wrong kind indistinguishable`() {
        val owner = owner(); val source = source(owner); val stored = translation(owner, source); val expected = identity(stored)
        request(owner, body(DocumentIdentityKind.TRANSLATION, stored.recordId, expected)).andExpect(status().isOk)
            .andExpect(jsonPath("$.identity.modelId").value("")).andExpect(jsonPath("$.identity.sourceRevision").value("source-v1"))
        val variants = listOf(expected.copy(targetLanguage = "ja"), expected.copy(translationProviderId = "other"), expected.copy(modelId = "other"),
            expected.copy(promptRevision = "other"), expected.copy(glossaryRevision = "other"), expected.copy(sourceRevision = "other"),
            expected.copy(artifactId = "0".repeat(64)), expected.copy(revision = "0".repeat(64)), expected.copy(payloadHash = "0".repeat(64)), expected.copy(paragraphHash = "0".repeat(64)))
        variants.forEach { request(owner, body(DocumentIdentityKind.TRANSLATION, stored.recordId, it)).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_MISMATCH")) }
        for ((user, id) in listOf(owner() to stored.recordId, owner to UUID.randomUUID(), owner to source.recordId))
            request(user, body(DocumentIdentityKind.TRANSLATION, id, expected)).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_NOT_FOUND"))
        request(owner(), body(DocumentIdentityKind.ORIGINAL, source.recordId, DocumentIdentities.original(source.content())))
            .andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_NOT_FOUND"))
    }

    @Test fun `corrupt persisted body revision and legacy metadata are unavailable without repair`() {
        for (broken in listOf("{not-json", "null", "[null]", "[]", "[{\"paragraphId\":\"p\"}]")) {
            val owner = owner(); val source = source(owner); val expected = DocumentIdentities.original(source.content())
            jdbc.update("update source_chapter set paragraphs_json=? where id=?", broken, source.recordId)
            request(owner, body(DocumentIdentityKind.ORIGINAL, source.recordId, expected)).andExpect(status().isConflict)
                .andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_UNAVAILABLE"))
            assertEquals(broken, jdbc.queryForObject("select paragraphs_json from source_chapter where id=?", String::class.java, source.recordId))
        }
        val owner = owner(); val source = source(owner); val stored = translation(owner, source); val expected = identity(stored)
        jdbc.update("update translation_artifact set content_provider_id=null, book_id=null where id=?", stored.recordId)
        request(owner, body(DocumentIdentityKind.TRANSLATION, stored.recordId, expected)).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_UNAVAILABLE"))
        jdbc.update("update source_chapter set source_revision=? where id=?", "0".repeat(64), source.recordId)
        request(owner, body(DocumentIdentityKind.ORIGINAL, source.recordId, DocumentIdentities.original(source.content()))).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_UNAVAILABLE"))
    }

    @Test fun `strict JSON kind unicode and CSRF are enforced`() {
        val owner = owner(); val source = source(owner); val identity = DocumentIdentities.original(source.content())
        val valid = body(DocumentIdentityKind.ORIGINAL, source.recordId, identity)
        val invalid = listOf(valid + " {}", valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"version\":1", "\"version\":1,\"modelId\":null"),
            valid.replace("\"sourceLanguage\":\"en\"", "\"sourceLanguage\":\"\\ud800\""),
            valid.replaceFirst("\"ORIGINAL\"", "\"TRANSLATION\""), valid.dropLast(1) + ",\"extra\":true}")
        invalid.forEach { request(owner, it).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("LIBRARY_IDENTITY_INVALID")) }
        mvc.perform(post(path).servletPath(path).with(user(owner)).contentType("application/json").content(valid)).andExpect(status().isForbidden)
        mvc.perform(post(path).servletPath(path).with(csrf()).contentType("application/json").content(valid)).andExpect(status().isUnauthorized)
    }

    @Test fun `body budget also stops an unknown content length before binding`() {
        val request = object : MockHttpServletRequest("POST", path) {
            override fun getContentLength() = -1
            override fun getContentLengthLong() = -1L
        }.apply { servletPath = path; setContent(ByteArray(64 * 1024 + 1) { 32 }) }
        val response = MockHttpServletResponse(); var continued = false
        ApiRequestBodyLimit().doFilter(request, response) { _, _ -> continued = true }
        assertFalse(continued); assertEquals(413, response.status); assertEquals("no-store", response.getHeader("Cache-Control"))
    }
}
