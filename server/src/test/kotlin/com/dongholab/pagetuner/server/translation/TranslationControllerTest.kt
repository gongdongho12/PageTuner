package com.dongholab.pagetuner.server.translation

import com.dongholab.pagetuner.server.ServerSecurity
import com.dongholab.pagetuner.server.SessionController
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import java.time.Instant
import java.util.UUID

@WebMvcTest(controllers = [TranslationController::class, SessionController::class], properties = ["spring.security.user.password=test-password"])
@Import(ServerSecurity::class)
class TranslationControllerTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @MockitoBean lateinit var service: TranslationApplicationService

    private val id = UUID.randomUUID()
    private val request = SaveTranslationRequest("web", "book", "chapter", "v1", "en", "ko", "provider",
        paragraphs = listOf(TranslatedParagraphRequest("p1", "번역")))
    private val artifact = request.toArtifact()
    private val document = TranslationBackupDocument(1, artifact.artifactId, artifact.revision, artifact.payloadHash, request)

    @Test
    fun `download requires authentication`() {
        mvc.perform(get("/api/v1/translations/$id/backup")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `download is scoped to authenticated owner and sent as attachment`() {
        `when`(service.exportBackup("reader", id)).thenReturn(document)
        mvc.perform(get("/api/v1/translations/$id/backup").with(user("reader")))
            .andExpect(status().isOk)
            .andExpect(header().string("Content-Disposition", "attachment; filename=translation-$id.json"))
            .andExpect(jsonPath("$.translation.paragraphs[0].text").value("번역"))
        `when`(service.exportBackup("other", id)).thenThrow(TranslationNotFound())
        mvc.perform(get("/api/v1/translations/$id/backup").with(user("other")))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `restore requires CSRF and returns existing record without duplication`() {
        val body = mapper.writeValueAsBytes(document)
        mvc.perform(post("/api/v1/translations/restore").with(user("reader"))
            .contentType("application/json").content(body)).andExpect(status().isForbidden)
        `when`(service.save("reader", request)).thenReturn(TranslationResponse(id, artifact.artifactId,
            artifact.revision, artifact.payloadHash, false, Instant.now(), request.paragraphs))
        mvc.perform(post("/api/v1/translations/restore").with(user("reader")).with(csrf())
            .contentType("application/json").content(body)).andExpect(status().isOk)
            .andExpect(jsonPath("$.created").value(false))
    }

    @Test
    fun `tampered backup returns bad request`() {
        mvc.perform(post("/api/v1/translations/restore").with(user("reader")).with(csrf())
            .contentType("application/json").content(mapper.writeValueAsBytes(document.copy(payloadHash = "wrong"))))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `authenticated clients can obtain CSRF token`() {
        mvc.perform(get("/api/v1/csrf").with(user("reader")))
            .andExpect(status().isOk).andExpect(jsonPath("$.token").isNotEmpty)
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
    }
}
