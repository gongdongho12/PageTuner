package com.dongholab.pagetuner.server.pdf

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.server.translation.ExternalPostgresTestDatabase
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.Base64
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer

@SpringBootTest(properties = ["spring.security.user.password=pdf-content-test-password"])
@AutoConfigureMockMvc
class PdfContentIntegrationTest {
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
    @Autowired lateinit var service: PdfContentService
    private val path = "/api/v1/pdf-content"
    private val pdf = "%PDF-1.7\n% immutable original\n%%EOF\n".toByteArray()
    private fun owner() = "pdf-${UUID.randomUUID()}".also {
        jdbc.update("insert into reader_account(id,username,password_hash,display_name) values(?,?,?,?)", UUID.randomUUID(), it, "test-only", "Test")
    }
    private fun input(bytes: ByteArray = pdf, uploadId: String = UUID.randomUUID().toString()): PdfContentUpload {
        val asset = ExchangeAsset(bytes, "application/pdf")
        return PdfContentUpload(uploadId, PdfContentDocument(1, "zh-Hant", listOf(ExchangeParagraph(" p:原 🌏 ", " 原文\n保留 "), ExchangeParagraph("empty", "")),
            listOf(ExchangeAssetReference(asset.path, "pdf", null, " 原始檔 ")),
            listOf(PdfContentPayload(asset.path, asset.mimeType, Base64.getEncoder().encodeToString(bytes)))))
    }
    private fun upload(owner: String, request: PdfContentUpload) = uploadJson(owner, json.writeValueAsString(request))
    private fun uploadJson(owner: String, body: String) = mvc.perform(post(path).servletPath(path).with(user(owner)).with(csrf())
        .contentType("application/json").content(body))
    private fun verify(owner: String, id: UUID, proof: PortableContentProof) = mvc.perform(post("$path/$id/verify").servletPath("$path/$id/verify")
        .with(user(owner)).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(mapOf("proof" to proof))))
    private fun read(owner: String, id: UUID) = mvc.perform(get("$path/$id").with(user(owner)))
    private fun count(owner: String) = jdbc.queryForObject("select count(*) from pdf_content_snapshot where user_id=?", Long::class.java, owner)!!

    @Test fun `upload stores actual original once with exact metadata and restart reads reproduce proof`() {
        val owner = owner(); val input = input()
        val response = upload(owner, input).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().response.contentAsString
        val id = UUID.fromString(json.readTree(response)["recordId"].textValue())
        val receipt = service.upload(owner, input)
        val reopened = PdfContentService(jdbc, json).get(owner, id)
        assertEquals(input.content, reopened.content)
        assertEquals(PdfContentValidation.validate(input).proof, reopened.proof)
        assertEquals(receipt.createdAt, reopened.createdAt)
        assertEquals(1L, count(owner))
        assertEquals(pdf.size.toLong(), jdbc.queryForObject("select sum(octet_length(payload)) from pdf_content_payload where snapshot_id=?", Long::class.java, id))
        assertArrayEquals(pdf, service.original(owner, id))
        read(owner, id).andExpect(status().isOk).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.content.paragraphs[0].paragraphId").value(" p:原 🌏 "))
        val downloaded = mvc.perform(get("$path/$id/original").with(user(owner))).andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=\"$id.pdf\""))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(content().contentType("application/pdf"))
            .andReturn().response.contentAsByteArray
        assertArrayEquals(pdf, downloaded)
    }

    @Test fun `same upload exact replay is stable and all changed ordered inputs conflict without mutation`() {
        val owner = owner(); val image = ExchangeAsset(byteArrayOf(1, 2, 3), "image/png")
        val plain = input()
        val original = plain.copy(content = plain.content.copy(assets = plain.content.assets + ExchangeAssetReference(image.path, "image", "empty", ""),
            payloads = plain.content.payloads + PdfContentPayload(image.path, image.mimeType, Base64.getEncoder().encodeToString(image.bytes))))
        val first = service.upload(owner, original)
        assertEquals(first, service.upload(owner, original))
        for (modified in listOf(original.copy(content = original.content.copy(language = "en")),
            original.copy(content = original.content.copy(paragraphs = original.content.paragraphs.reversed())),
            original.copy(content = original.content.copy(assets = original.content.assets.reversed())),
            original.copy(content = original.content.copy(payloads = original.content.payloads.reversed())),
            original.copy(content = original.content.copy(assets = original.content.assets.map { it.copy(alt = "different") })),
            input("%PDF-1.7\nnew original\n%%EOF".toByteArray(), original.uploadId))) {
            upload(owner, modified).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PDF_CONTENT_UPLOAD_REUSED"))
        }
        assertEquals(original.content, service.get(owner, first.recordId).content)
        assertEquals(1L, count(owner))
        val duplicate = service.upload(owner, original.copy(uploadId = UUID.randomUUID().toString()))
        assertNotEquals(first.recordId, duplicate.recordId); assertEquals(first.proof, duplicate.proof)
        assertEquals(2L, count(owner))
    }

    @Test fun `simultaneous identical uploads commit one immutable record and differing request reuse has one winner`() {
        val owner = owner(); val pool = Executors.newFixedThreadPool(2)
        try {
            val input = input(); val start = CountDownLatch(1)
            val same = (1..2).map { pool.submit(Callable { assertTrue(start.await(10, TimeUnit.SECONDS)); service.upload(owner, input) }) }
            start.countDown()
            assertEquals(1, same.map { it.get(20, TimeUnit.SECONDS).recordId }.toSet().size)
            assertEquals(1L, count(owner))
            val next = input(); val race = CountDownLatch(1)
            val different = listOf(next, next.copy(content = next.content.copy(language = "ko"))).map { request -> pool.submit(Callable {
                assertTrue(race.await(10, TimeUnit.SECONDS))
                try { service.upload(owner, request); "saved" } catch (error: PdfContentFailure) { error.code }
            }) }
            race.countDown()
            assertEquals(setOf("saved", "PDF_CONTENT_UPLOAD_REUSED"), different.map { it.get(20, TimeUnit.SECONDS) }.toSet())
            assertEquals(2L, count(owner))
        } finally { pool.shutdownNow() }
    }

    @Test fun `owner authentication csrf isolation and missing IDs protect every endpoint`() {
        val owner = owner(); val other = owner(); val request = input(); val saved = service.upload(owner, request)
        val id = saved.recordId
        for (suffix in listOf("", "/original")) {
            mvc.perform(get("$path/$id$suffix")).andExpect(status().isUnauthorized)
            mvc.perform(get("$path/$id$suffix").with(user(other))).andExpect(status().isNotFound)
            mvc.perform(get("$path/${UUID.randomUUID()}$suffix").with(user(owner))).andExpect(status().isNotFound)
        }
        mvc.perform(post(path).with(csrf()).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isUnauthorized)
        mvc.perform(post(path).with(user(owner)).contentType("application/json").content(json.writeValueAsBytes(request))).andExpect(status().isForbidden)
        mvc.perform(post("$path/$id/verify").with(user(owner)).contentType("application/json").content("{}")).andExpect(status().isForbidden)
        mvc.perform(post("$path/$id/verify").with(csrf()).contentType("application/json").content("{}")).andExpect(status().isUnauthorized)
        verify(other, id, saved.proof).andExpect(status().isNotFound)
        verify(owner, UUID.randomUUID(), saved.proof).andExpect(status().isNotFound)
        assertNotEquals(id, service.upload(other, request).recordId)
        jdbc.update("delete from reader_account where username=?", owner)
        assertEquals(0L, count(owner))
        assertEquals(0L, jdbc.queryForObject("select count(*) from pdf_content_payload where snapshot_id=?", Long::class.java, id))
        assertEquals(1L, count(other))
    }

    @Test fun `reads and proof verification do not create bindings source identity or S1 through S3 records`() {
        val owner = owner(); val saved = service.upload(owner, input())
        val before = jdbc.queryForMap("select metadata_json,proof_json,request_hash,created_at from pdf_content_snapshot where id=?", saved.recordId)
        verify(owner, saved.recordId, saved.proof).andExpect(status().isOk).andExpect(jsonPath("$.verified").value(true))
        val mismatched = PdfContentValidation.validate(input().copy(content = input().content.copy(language = "ko"))).proof
        verify(owner, saved.recordId, mismatched).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PDF_CONTENT_MISMATCH"))
        service.get(owner, saved.recordId); service.original(owner, saved.recordId)
        assertEquals(before, jdbc.queryForMap("select metadata_json,proof_json,request_hash,created_at from pdf_content_snapshot where id=?", saved.recordId))
        for (table in listOf("reading_progress", "reading_note_document", "reading_note_current", "library_organization", "book_glossary", "source_book_favorite_account")) {
            assertEquals(0L, jdbc.queryForObject("select count(*) from $table where user_id=?", Long::class.java, owner), table)
        }
    }

    @Test fun `changed missing or oversized stored bytes and metadata fail closed on read verify download and replay`() {
        val owner = owner()
        val mutations = listOf<(UUID) -> Unit>(
            { id -> jdbc.update("update pdf_content_payload set payload=? where snapshot_id=?", "%PDF-1.7\nchanged\n%%EOF".toByteArray(), id) },
            { id -> jdbc.update("delete from pdf_content_payload where snapshot_id=?", id) },
            { id -> jdbc.update("update pdf_content_payload set payload=? where snapshot_id=?", ByteArray(4 * 1024 * 1024 + 1), id) },
            { id -> jdbc.update("update pdf_content_snapshot set metadata_json=? where id=?", "x".repeat(2 * 1024 * 1024 + 1), id) },
            { id -> jdbc.update("update pdf_content_snapshot set proof_json=? where id=?", "{}", id) },
            { id -> jdbc.update("update pdf_content_snapshot set request_hash=? where id=?", "0".repeat(64), id) },
            { id -> jdbc.update("update pdf_content_payload set ordinal=1 where snapshot_id=?", id) },
        )
        mutations.forEach { mutate ->
            val input = input(); val saved = service.upload(owner, input); mutate(saved.recordId)
            read(owner, saved.recordId).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PDF_CONTENT_UNAVAILABLE"))
            verify(owner, saved.recordId, saved.proof).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PDF_CONTENT_UNAVAILABLE"))
            mvc.perform(get("$path/${saved.recordId}/original").with(user(owner))).andExpect(status().isConflict)
            upload(owner, input).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PDF_CONTENT_UNAVAILABLE"))
        }
        assertEquals(mutations.size.toLong(), count(owner))
    }

    @Test fun `strict JSON rejects unsupported representations unknown fields duplicate keys trailing tokens and coercions`() {
        val owner = owner(); val valid = json.writeValueAsString(input())
        for (body in listOf("$valid {}", valid.replaceFirst("{", "{\"proof\":{},"), valid.replace("\"version\":1", "\"version\":1.0"),
            valid.replace("\"version\":1", "\"version\":\"1\""), valid.replace("\"version\":1", "\"version\":1,\"version\":1"),
            valid.replace("\"language\":\"zh-Hant\"", "\"language\":false"), valid.replace("\"role\":\"pdf\"", "\"role\":\"epub\""))) {
            uploadJson(owner, body).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("PDF_CONTENT_INVALID"))
        }
        val saved = service.upload(owner, input())
        val proof = json.writeValueAsString(mapOf("proof" to saved.proof))
        for (body in listOf("$proof {}", proof.replace("\"version\":1", "\"version\":1.0"), proof.replaceFirst("{", "{\"pageCount\":2,"))) {
            mvc.perform(post("$path/${saved.recordId}/verify").with(user(owner)).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isBadRequest)
        }
        assertEquals(1L, count(owner))
    }

    @Test fun `all payloads references and base64 must correspond with no inference or partial storage`() {
        val owner = owner(); val original = input(); val content = original.content
        for (invalid in listOf(content.copy(payloads = emptyList()), content.copy(assets = emptyList()),
            content.copy(payloads = content.payloads + content.payloads), content.copy(assets = content.assets + content.assets),
            content.copy(payloads = content.payloads.map { it.copy(path = "assets/" + "0".repeat(64)) }),
            content.copy(payloads = content.payloads.map { it.copy(base64 = it.base64 + "\n") }),
            content.copy(assets = content.assets.map { it.copy(paragraphId = "absent") }),
            content.copy(paragraphs = content.paragraphs + content.paragraphs),
            content.copy(language = "x".repeat(36)), content.copy(paragraphs = listOf(ExchangeParagraph("id", "x".repeat(262145)))))) {
            upload(owner, original.copy(content = invalid)).andExpect(status().isBadRequest)
        }
        upload(owner, input("not PDF".toByteArray())).andExpect(status().isBadRequest)
        assertEquals(0L, count(owner))
        val emptyText = original.copy(content = content.copy(paragraphs = emptyList()))
        upload(owner, emptyText).andExpect(status().isOk)
    }

    @Test fun `decoded total PDF limit accepts its exact boundary and rejects one extra byte atomically`() {
        val owner = owner()
        val maximum = ByteArray(4 * 1024 * 1024) { 32 }.apply { pdf.copyInto(this) }
        val exact = input(maximum)
        assertTrue(json.writeValueAsBytes(exact).size < 8 * 1024 * 1024)
        val saved = service.upload(owner, exact)
        assertArrayEquals(maximum, service.original(owner, saved.recordId))
        upload(owner, input(maximum + byteArrayOf(32))).andExpect(status().isBadRequest)
        assertEquals(1L, count(owner))
    }

    @Test fun `large valid escaped reference metadata roundtrips and verifies above the former proof cap`() {
        val owner = owner(); val original = input()
        val image = ExchangeAsset(byteArrayOf(8, 9, 10), "image/png")
        val alt = "\u0001".repeat(1000) + "書".repeat(1000)
        val refs = listOf(original.content.assets.single()) + List(127) { ExchangeAssetReference(image.path, "image", "empty", alt) }
        val input = original.copy(content = original.content.copy(assets = refs,
            payloads = original.content.payloads + PdfContentPayload(image.path, image.mimeType, Base64.getEncoder().encodeToString(image.bytes))))
        val saved = service.upload(owner, input)
        val proofBody = json.writeValueAsBytes(mapOf("proof" to saved.proof))
        assertTrue(proofBody.size > 128 * 1024)
        assertTrue(proofBody.size < 2 * 1024 * 1024)
        assertEquals(input.content, service.get(owner, saved.recordId).content)
        verify(owner, saved.recordId, saved.proof).andExpect(status().isOk)
        upload(owner, input).andExpect(status().isOk)
    }

    @Test fun `shared independent fixture produces the documented persisted proof and full request fingerprint`() {
        val owner = owner()
        val fixture = json.readTree(requireNotNull(javaClass.getResource("/pdf-content-v1.json")).readText())
        val request = PdfContentJson(json).upload(json.writeValueAsBytes(fixture["upload"]))
        val saved = service.upload(owner, request)
        assertEquals(fixture["expected"]["proof"], json.readTree(json.writeValueAsBytes(saved.proof)))
        assertEquals(fixture["expected"]["requestFingerprint"].textValue(), jdbc.queryForObject(
            "select request_hash from pdf_content_snapshot where id=?", String::class.java, saved.recordId))
        verify(owner, saved.recordId, saved.proof).andExpect(status().isOk)
    }
}
