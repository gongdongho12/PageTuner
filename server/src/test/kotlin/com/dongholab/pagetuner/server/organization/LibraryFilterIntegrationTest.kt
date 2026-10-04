package com.dongholab.pagetuner.server.organization

import com.dongholab.pagetuner.core.model.library.LibraryFilter
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.dongholab.pagetuner.server.translation.SaveTranslationRequest
import com.dongholab.pagetuner.server.translation.TranslatedParagraphRequest
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.SourceParagraph
import com.dongholab.pagetuner.server.workflow.UploadedChapterRequest
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.dao.InvalidDataAccessApiUsageException
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=library-filter-test-password"])
@AutoConfigureMockMvc
class LibraryFilterIntegrationTest {
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
    @Autowired lateinit var organization: LibraryOrganizationService
    @Autowired lateinit var chapters: SourceChapterStore
    @Autowired lateinit var translations: TranslationApplicationService

    private fun owner() = "library-filter-${UUID.randomUUID()}"
    private fun table(kind: LibraryOrganizationKind) = if (kind == LibraryOrganizationKind.ORIGINAL) "source_chapter" else "translation_artifact"
    private fun path(kind: LibraryOrganizationKind) = if (kind == LibraryOrganizationKind.ORIGINAL) "/api/v1/chapters" else "/api/v1/translations"
    private fun document(owner: String, kind: LibraryOrganizationKind, bookTitle: String = "Quiet book", chapterTitle: String = "Chapter"): UUID {
        val source = chapters.upload(owner, UploadedChapterRequest(UUID.randomUUID().toString(), bookTitle,
            "chapter-1", chapterTitle, "en", listOf(SourceParagraph("p-1", 0, "Private paragraph body."))))
        return if (kind == LibraryOrganizationKind.ORIGINAL) source.recordId else translations.save(owner,
            SaveTranslationRequest(source.providerId, source.bookId, source.chapterId, source.sourceRevision, "en", "ko", "test-provider",
                paragraphs = listOf(TranslatedParagraphRequest("p-1", "비공개 본문")), bookTitle = bookTitle, chapterTitle = chapterTitle)).recordId
    }
    private fun classify(owner: String, kind: LibraryOrganizationKind, id: UUID, folder: String = "Reading", tags: List<String> = listOf("Fantasy"), favorite: Boolean = true) {
        val version = organization.get(owner, kind, id).version
        organization.put(owner, kind, id, PutLibraryOrganizationRequest(version, UUID.randomUUID(), LibraryOrganization(folder, tags, favorite)))
    }
    private fun page(owner: String, kind: LibraryOrganizationKind, page: Int = 0, size: Int = 12, vararg filters: Pair<String, String>): JsonNode {
        val request = get(path(kind)).with(user(owner)).param("page", page.toString()).param("size", size.toString())
        filters.forEach { (name, value) -> request.param(name, value) }
        return json.readTree(mvc.perform(request).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", containsString("no-store"))).andReturn().response.contentAsString)
    }
    private fun ids(page: JsonNode): List<String> = page["items"].map { it["recordId"].asText() }

    @Test fun `filters and counts apply across all pages with stable ties and AND semantics`() {
        for (kind in LibraryOrganizationKind.entries) {
            val owner = owner()
            val expected = (0 until 17).map { index ->
                val id = document(owner, kind, "Needle $index")
                classify(owner, kind, id, tags = listOf("Fantasy", "人物,別名"))
                id.toString()
            }.sortedDescending()
            repeat(4) { document(owner, kind, "Unmatched") }
            val wrongFolder = document(owner, kind, "Needle different folder"); classify(owner, kind, wrongFolder, folder = "Later")
            val wrongTag = document(owner, kind, "Needle different tag"); classify(owner, kind, wrongTag, tags = listOf("fantasy"))
            val notFavorite = document(owner, kind, "Needle not favorite"); classify(owner, kind, notFavorite, favorite = false)
            jdbc.update("update ${table(kind)} set created_at=? where user_id=?", Timestamp.from(Instant.parse("2026-01-01T00:00:00Z")), owner)
            val filter = arrayOf("q" to "nEeDlE", "folder" to "Reading", "tag" to "Fantasy", "favorite" to "true")
            val first = page(owner, kind, filters = filter)
            val last = page(owner, kind, page = 1, filters = filter)
            assertEquals(17, first["totalItems"].asInt()); assertEquals(2, first["totalPages"].asInt())
            assertTrue(first["hasNext"].asBoolean()); assertFalse(last["hasNext"].asBoolean())
            assertEquals(expected.take(12), ids(first)); assertEquals(expected.drop(12), ids(last))
            assertEquals(17, last["totalItems"].asInt())
            val beyond = page(owner, kind, page = 2, filters = filter)
            assertTrue(ids(beyond).isEmpty()); assertEquals(17, beyond["totalItems"].asInt())
        }
    }

    @Test fun `unfiled and not favorite include absence and versioned reset without tag matches`() {
        for (kind in LibraryOrganizationKind.entries) {
            val owner = owner(); val absent = document(owner, kind); val reset = document(owner, kind); val assigned = document(owner, kind)
            classify(owner, kind, reset); classify(owner, kind, reset, folder = "", tags = emptyList(), favorite = false)
            classify(owner, kind, assigned)
            val unfiled = page(owner, kind, filters = arrayOf("folder" to "", "favorite" to "false"))
            assertEquals(setOf(absent.toString(), reset.toString()), ids(unfiled).toSet())
            assertEquals(2, unfiled["totalItems"].asInt())
            assertTrue(ids(page(owner, kind, filters = arrayOf("folder" to "", "tag" to "Fantasy"))).isEmpty())
            assertEquals(ids(page(owner, kind)), ids(page(owner, kind, filters = arrayOf("q" to ""))))
            assertEquals(listOf(assigned.toString()), ids(page(owner, kind, filters = arrayOf("favorite" to "true"))))
        }
    }

    @Test fun `search uses literal wildcard backslash escape and quote characters in either title`() {
        for (kind in LibraryOrganizationKind.entries) {
            val owner = owner(); val literal = "100%_\\!'"
            val book = document(owner, kind, "Book $literal remaining")
            val chapter = document(owner, kind, "Ordinary", "Chapter $literal remaining")
            document(owner, kind, "Book 100anythingX\\!' remaining")
            assertEquals(setOf(book.toString(), chapter.toString()), ids(page(owner, kind, filters = arrayOf("q" to literal))).toSet())
            assertEquals(0, page(owner, kind, filters = arrayOf("q" to "' OR 1=1 --"))["totalItems"].asInt())
            assertEquals(0, page(owner, kind, filters = arrayOf("q" to "Private paragraph body"))["totalItems"].asInt())
        }
    }

    @Test fun `organization is exact Unicode and isolated by account and kind even with colliding record IDs`() {
        val owner = owner(); val foreign = owner()
        val original = document(owner, LibraryOrganizationKind.ORIGINAL)
        val translated = document(owner, LibraryOrganizationKind.TRANSLATION)
        jdbc.update("update translation_artifact set id=? where id=? and user_id=?", original, translated, owner)
        classify(owner, LibraryOrganizationKind.ORIGINAL, original, "読む", listOf("é", "人物,別名"))
        classify(owner, LibraryOrganizationKind.TRANSLATION, original, "Later", listOf("e\u0301"))
        val foreignId = document(foreign, LibraryOrganizationKind.ORIGINAL)
        classify(foreign, LibraryOrganizationKind.ORIGINAL, foreignId, "読む", listOf("é"))
        val filter = arrayOf("folder" to "読む", "tag" to "é")
        assertEquals(listOf(original.toString()), ids(page(owner, LibraryOrganizationKind.ORIGINAL, filters = filter)))
        assertTrue(ids(page(owner, LibraryOrganizationKind.TRANSLATION, filters = filter)).isEmpty())
        assertTrue(ids(page(owner, LibraryOrganizationKind.ORIGINAL, filters = arrayOf("tag" to "e\u0301"))).isEmpty())
        assertEquals(listOf(original.toString()), ids(page(owner, LibraryOrganizationKind.ORIGINAL, filters = arrayOf("tag" to "人物,別名"))))
        assertEquals(listOf(foreignId.toString()), ids(page(foreign, LibraryOrganizationKind.ORIGINAL, filters = filter)))
        assertTrue(ids(page(owner(), LibraryOrganizationKind.ORIGINAL, filters = filter)).isEmpty())
    }

    @Test fun `filtered summary projections never deserialize or return paragraph bodies`() {
        for (kind in LibraryOrganizationKind.entries) {
            val owner = owner(); val id = document(owner, kind)
            classify(owner, kind, id)
            // Valid JSON array, invalid domain paragraphs: materializing an entity/body would fail.
            jdbc.update("update ${table(kind)} set paragraphs_json=? where id=?", "[{\"private-marker\":\"not-a-paragraph\"}]", id)
            val result = page(owner, kind, filters = arrayOf("favorite" to "true"))
            assertEquals(listOf(id.toString()), ids(result))
            assertEquals(1, result["items"][0]["paragraphCount"].asInt())
            assertFalse(result.toString().contains("private-marker"))
            assertFalse(result["items"][0].has("paragraphs"))
        }
    }

    @Test fun `invalid filters fail before querying and favorite uses strict boolean wire values`() {
        val invalid = listOf("q" to "x".repeat(201), "q" to " leading", "q" to "x\u0085y", "folder" to "x".repeat(201),
            "folder" to "Folder\ufeff", "tag" to "", "tag" to "x".repeat(61), "tag" to " Tag", "tag" to "x\u0000y",
            "favorite" to "", "favorite" to "TRUE", "favorite" to "1", "favorite" to "yes")
        for (kind in LibraryOrganizationKind.entries) {
            for ((name, value) in invalid) mvc.perform(get(path(kind)).with(user(owner())).param(name, value))
                .andExpect(status().isBadRequest)
            mvc.perform(get(path(kind)).param("favorite", "true")).andExpect(status().isUnauthorized)
        }
        val wrapped = assertThrows(InvalidDataAccessApiUsageException::class.java) { chapters.list(owner(), 0, 12, LibraryFilter(tag = "\ud800")) }
        assertInstanceOf(IllegalArgumentException::class.java, wrapped.cause)
        assertThrows(IllegalArgumentException::class.java) { translations.list(owner(), 0, 12, LibraryFilter(q = "\udc00")) }
    }
}
