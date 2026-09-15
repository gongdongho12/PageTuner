package com.dongholab.pagetuner.server.readingtranslation

import com.dongholab.pagetuner.server.ServerSecurity
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
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
    controllers = [ReadingTranslationController::class],
    properties = [
        "spring.security.user.name=reader",
        "spring.security.user.password=test-password",
        "spring.mvc.problemdetails.enabled=true",
    ],
)
@Import(ServerSecurity::class)
class ReadingTranslationMvcTest {
    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @MockitoBean lateinit var jobs: ReadingTranslationJobs

    @Test
    fun `read and start require authentication and both mutations require csrf`() {
        mvc.perform(get("$path/$requestId"))
            .andExpect(status().isUnauthorized)
        // Supply CSRF so that this request reaches the authentication check.
        mvc.perform(post(path).servletPath(path).with(csrf())
            .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
            .andExpect(status().isUnauthorized)

        listOf(path, "$path/$requestId/cancel").forEach { route ->
            mvc.perform(post(route).servletPath(route).with(httpBasic("reader", "test-password"))
                .contentType(MediaType.APPLICATION_JSON).content(requestJson()))
                .andExpect(status().isForbidden)
        }
        verifyNoInteractions(jobs)
    }

    @Test
    fun `authenticated lifecycle forwards principal and exposes only uncached reading preview`() {
        stubStart()
        doReturn(view()).`when`(jobs).get("reader", requestId)
        doReturn(view().copy(status = "CANCELLED", items = emptyList())).`when`(jobs).cancel("reader", requestId)

        val start = mvc.perform(authenticatedPost(path).content(requestJson()))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.scope").value("READING_PREVIEW"))
            .andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.requestId").value(requestId.toString()))
            .andExpect(jsonPath("$.chapterRecordId").value(chapterId.toString()))
            .andExpect(jsonPath("$.sourceHash").value(request().sourceHash))
            .andExpect(jsonPath("$.items[0].paragraphId").value("문단-1"))
            .andExpect(jsonPath("$.items[0].start").value(0))
            .andExpect(jsonPath("$.items[0].end").value(5))
            .andExpect(jsonPath("$.items[0].text").value("번역 미리 보기"))
            .andExpect(jsonPath("$.apiKey").doesNotExist())
            .andExpect(jsonPath("$.endpoint").doesNotExist())
            .andReturn()
        val read = mvc.perform(get("$path/$requestId").with(httpBasic("reader", "test-password")))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.scope").value("READING_PREVIEW"))
            .andReturn()
        val cancel = mvc.perform(authenticatedPost("$path/$requestId/cancel"))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.scope").value("READING_PREVIEW"))
            .andExpect(jsonPath("$.status").value("CANCELLED"))
            .andReturn()
        listOf(start, read, cancel).forEach { assertFalse(it.response.contentAsString.contains(secret)) }

        val captured = ArgumentCaptor.forClass(ReadingTranslationRequest::class.java)
        verify(jobs).start(eq("reader") ?: "reader", captured.capture() ?: request())
        assertEquals(requestId, captured.value.requestId)
        assertEquals(chapterId, captured.value.chapterRecordId)
        assertEquals(listOf(ReadingFragment("문단-1", 0, 5)), captured.value.fragments)
        assertEquals(secret, captured.value.apiKey)
        assertEquals("GOOGLE_WEB_TRANSLATE_HTML", captured.value.providerKind)
        assertEquals("ko", captured.value.targetLanguage)
        assertEquals("READING", captured.value.paceMode)
        assertEquals(210, captured.value.readingWordsPerMinute)
        verify(jobs).get("reader", requestId)
        verify(jobs).cancel("reader", requestId)
        verifyNoMoreInteractions(jobs)
    }

    @Test
    fun `malformed JSON missing fields and invalid identifiers fail before jobs`() {
        listOf("{", "{}").forEach { body ->
            mvc.perform(authenticatedPost(path).content(body))
                .andExpect(status().isBadRequest)
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
        }
        mvc.perform(get("$path/not-a-uuid").with(httpBasic("reader", "test-password")))
            .andExpect(status().isBadRequest)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400))
        verifyNoInteractions(jobs)
    }

    @Test
    fun `reading start accepts exactly 512 KiB and rejects one additional byte before binding`() {
        stubStart()
        val json = requestJson().toByteArray(Charsets.UTF_8)
        val atLimit = json + ByteArray(512 * 1024 - json.size) { ' '.code.toByte() }
        mvc.perform(authenticatedPost(path).content(atLimit))
            .andExpect(status().isOk)
            .andExpect(header().string("Cache-Control", "no-store"))
        mvc.perform(authenticatedPost(path).content(atLimit + byteArrayOf(' '.code.toByte())))
            .andExpect(status().isPayloadTooLarge)
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(413))
            .andExpect(jsonPath("$.title").value("Payload Too Large"))
            .andDo { assertFalse(it.response.contentAsString.contains(secret)) }
        verify(jobs).start(eq("reader") ?: "reader", any(ReadingTranslationRequest::class.java) ?: request())
        verifyNoMoreInteractions(jobs)
    }

    private fun authenticatedPost(route: String) = post(route)
        // Match the real servlet mapping so the security-chain body limit is exercised.
        .servletPath(route)
        .with(httpBasic("reader", "test-password"))
        .with(csrf())
        .contentType(MediaType.APPLICATION_JSON)

    private fun stubStart() {
        doReturn(view()).`when`(jobs).start(anyString(), any(ReadingTranslationRequest::class.java) ?: request())
    }

    private fun request() = ReadingTranslationRequest(
        requestId, chapterId, "source-revision", listOf(ReadingFragment("문단-1", 0, 5)), apiKey = secret,
    )

    private fun requestJson() = objectMapper.writeValueAsString(mapOf(
        "requestId" to requestId,
        "chapterRecordId" to chapterId,
        "sourceRevision" to "source-revision",
        "fragments" to listOf(mapOf("paragraphId" to "문단-1", "start" to 0, "end" to 5)),
        "apiKey" to secret,
    ))

    private fun view() = ReadingTranslationView(
        requestId, "COMPLETED", chapterId, "source-revision", request().sourceHash,
        "GOOGLE_WEB_TRANSLATE_HTML", "ko", 1, 1,
        listOf(ReadingTranslationItem("문단-1", 0, 5, "번역 미리 보기")), null,
        Instant.parse("2026-09-15T00:00:00Z"),
    )

    private companion object {
        const val path = "/api/v1/reading-translations"
        const val secret = "provider-secret-must-stay-private"
        val requestId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val chapterId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000002")
    }
}
