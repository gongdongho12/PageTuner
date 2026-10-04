package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.server.ServerSecurity
import com.dongholab.pagetuner.server.translation.TranslationApplicationService
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*

@WebMvcTest(controllers = [TranslationProviderCheckController::class], properties = [
    "spring.security.user.name=reader", "spring.security.user.password=test-password", "spring.mvc.problemdetails.enabled=true",
])
@Import(ServerSecurity::class, TranslationProviderChecks::class, TranslationProviderCheckMvcTest.Configuration::class)
class TranslationProviderCheckMvcTest {
    class SampleTranslator : WorkflowTranslator {
        val calls = AtomicInteger()
        override suspend fun translate(chapter: ChapterContent, config: JobConfiguration, apiKey: String,
            completed: Map<String, String>, onParagraph: suspend (String, String) -> Unit): List<TranslatedParagraph> {
            calls.incrementAndGet()
            assertEquals("auto", config.sourceLanguage)
            assertEquals(1, chapter.paragraphs.size)
            return listOf(TranslatedParagraph("sample-1", "private-translated-sample"))
        }
    }
    @TestConfiguration class Configuration {
        @Bean fun providers() = WorkflowProviders(emptyMap())
        @Bean fun translator() = SampleTranslator()
    }
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var checks: TranslationProviderChecks
    @Autowired lateinit var translator: SampleTranslator
    @MockitoBean lateinit var chapters: SourceChapterStore
    @MockitoBean lateinit var jobs: TranslationJobStore
    @MockitoBean lateinit var artifacts: TranslationApplicationService
    private val path = "/api/v1/translation-providers/check"
    private val body = """{"providerKind":"GOOGLE_CLOUD","apiKey":"private-test-key"}"""
    companion object { private val sequence = AtomicInteger() }
    @BeforeEach fun reset() {
        val time = Instant.EPOCH.plusSeconds(sequence.incrementAndGet() * 20L)
        checks.now = { time }; translator.calls.set(0)
    }
    private fun authorized() = post(path).servletPath(path).with(httpBasic("reader", "test-password")).with(csrf()).contentType(MediaType.APPLICATION_JSON)

    @Test fun `check requires authentication and csrf before a provider can be called`() {
        mvc.perform(post(path).servletPath(path).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized)
        mvc.perform(post(path).servletPath(path).with(httpBasic("reader", "test-password")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden)
        assertEquals(0, translator.calls.get()); verifyNoInteractions(chapters, jobs, artifacts)
    }

    @Test fun `actual async check returns safe metadata with no store writes and applies account cooldown`() {
        val pending = mvc.perform(authorized().content(body)).andExpect(request().asyncStarted()).andReturn()
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.status").value("SUCCESS"))
            .andExpect(jsonPath("$.code").value("PROVIDER_CHECK_OK"))
            .andExpect(jsonPath("$.providerKind").value("GOOGLE_CLOUD"))
            .andExpect(jsonPath("$.sourceLanguage").value("auto"))
            .andExpect(jsonPath("$.targetLanguage").value("ko"))
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(jsonPath("$.endpoint").doesNotExist())
            .andDo { assertFalse(it.response.contentAsString.contains("private-")) }
        mvc.perform(authorized().content(body)).andExpect(status().isTooManyRequests)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.code").value("PROVIDER_CHECK_BUSY"))
        assertEquals(1, translator.calls.get()); verifyNoInteractions(chapters, jobs, artifacts)
    }

    @Test fun `binding settings and body limits reject before provider invocation without leaking inputs`() {
        for (invalid in listOf("{", "{}", """{"providerKind":"GOOGLE_CLOUD","apiKey":"private-test-key\nsecond-line"}""")) {
            mvc.perform(authorized().content(invalid)).andExpect(status().isBadRequest)
                .andDo { assertFalse(it.response.contentAsString.contains("private-test-key")) }
        }
        for ((input, code) in listOf(
            """{"providerKind":"OTHER"}""" to "INVALID_PROVIDER",
            """{"providerKind":"DEEPSEEK"}""" to "PROVIDER_NOT_CONFIGURED",
            """{"providerKind":"DEEPSEEK","apiKey":"private-test-key","endpoint":"https://private-endpoint.example/completions"}""" to "ENDPOINT_NOT_ALLOWED",
        )) mvc.perform(authorized().content(input)).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value(code)).andDo { assertFalse(it.response.contentAsString.contains("private-")) }
        val overLimit = body + " ".repeat(16 * 1024)
        mvc.perform(authorized().content(overLimit)).andExpect(status().isPayloadTooLarge)
            .andExpect(header().string("Cache-Control", "no-store"))
        assertEquals(0, translator.calls.get()); verifyNoInteractions(chapters, jobs, artifacts)
    }
}
