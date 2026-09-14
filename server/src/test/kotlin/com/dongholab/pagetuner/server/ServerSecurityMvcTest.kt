package com.dongholab.pagetuner.server

import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import com.dongholab.pagetuner.server.translation.TranslationController
import com.dongholab.pagetuner.server.workflow.WorkflowController
import com.dongholab.pagetuner.server.workflow.SourceChapterStore
import com.dongholab.pagetuner.server.workflow.WorkflowProviders
import com.dongholab.pagetuner.server.workflow.TranslationWorkflowService
import com.dongholab.pagetuner.source.service.NovelSourceService
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpSession
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(
    controllers = [CsrfController::class, TranslationController::class, WorkflowController::class],
    properties = ["spring.security.user.name=reader", "spring.security.user.password=test-password"],
)
@Import(ServerSecurity::class)
class ServerSecurityMvcTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockitoBean lateinit var service: TranslationApplicationService
    @MockitoBean lateinit var sources: NovelSourceService
    @MockitoBean lateinit var chapters: SourceChapterStore
    @MockitoBean lateinit var providers: WorkflowProviders
    @MockitoBean lateinit var workflow: TranslationWorkflowService

    @Test
    fun `workflow routes require authentication and every mutation requires csrf`() {
        listOf("/api/v1/chapters", "/api/v1/translation-jobs", "/api/v1/translation-providers").forEach {
            mvc.perform(get(it)).andExpect(status().isUnauthorized)
        }
        listOf("/api/v1/chapters/import", "/api/v1/chapters/upload", "/api/v1/translation-jobs", "/api/v1/translation-jobs/00000000-0000-0000-0000-000000000001/cancel").forEach {
            mvc.perform(post(it).with(httpBasic("reader", "test-password")).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(sources, chapters, workflow, providers)
    }

    @Test
    fun `public web shell paths do not expose authenticated API routes`() {
        // The standalone server test has no packaged web assets; public missing assets return 404, not 401.
        mvc.perform(get("/index.html")).andExpect(status().isNotFound)
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound)
        mvc.perform(get("/api/v1/translations")).andExpect(status().isUnauthorized)
        verifyNoInteractions(service)
    }

    @Test
    fun `csrf endpoint requires authenticated credentials`() {
        mvc.perform(get("/api/v1/csrf")).andExpect(status().isUnauthorized)
        mvc.perform(get("/api/v1/csrf").with(httpBasic("reader", "wrong-password")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `authenticated write without csrf token is rejected`() {
        mvc.perform(
            post("/api/v1/translations")
                .with(httpBasic("reader", "test-password"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isForbidden)
        verifyNoInteractions(service)
    }

    @Test
    fun `fetched csrf token permits validation only with the original session`() {
        val result = mvc.perform(get("/api/v1/csrf").with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andExpect(jsonPath("$.token").isNotEmpty)
            .andReturn()
        val token = objectMapper.readTree(result.response.contentAsString)
        val session = requireNotNull(result.request.getSession(false)) as MockHttpSession

        // Cover JSON binding and Bean Validation using a real fetched CSRF token.
        // These standard MVC errors must match the documented ProblemDetail shape.
        val blankRequiredField = """
            {
              "contentProviderId":" ", "bookId":"book", "chapterId":"chapter",
              "sourceRevision":"source-v1", "sourceLanguage":"en", "targetLanguage":"ko",
              "translationProviderId":"translator", "modelId":"model",
              "promptRevision":"prompt-v1", "glossaryRevision":"glossary-v1",
              "paragraphs":[{"paragraphId":"p-1","text":"Translation"}]
            }
        """.trimIndent()
        for (body in listOf("{}", blankRequiredField)) {
            mvc.perform(
                post("/api/v1/translations")
                    .with(httpBasic("reader", "test-password"))
                    .session(session)
                    .header(token["headerName"].asText(), token["token"].asText())
                    .accept(MediaType.APPLICATION_JSON)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body),
            ).andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
        }

        mvc.perform(
            get("/api/v1/translations/not-a-uuid")
                .with(httpBasic("reader", "test-password"))
                .accept(MediaType.APPLICATION_JSON),
        ).andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))

        mvc.perform(
            post("/api/v1/translations")
                .with(httpBasic("reader", "test-password"))
                .header(token["headerName"].asText(), token["token"].asText())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isForbidden)
        verifyNoInteractions(service)
    }
}
