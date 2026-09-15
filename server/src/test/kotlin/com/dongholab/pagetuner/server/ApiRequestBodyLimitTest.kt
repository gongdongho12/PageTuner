package com.dongholab.pagetuner.server

import jakarta.servlet.http.HttpServletRequest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class ApiRequestBodyLimitTest {
    private fun request(path: String, bytes: ByteArray, unknownLength: Boolean = false) = object : MockHttpServletRequest("POST", path) {
        override fun getContentLengthLong(): Long = if (unknownLength) -1 else super.getContentLengthLong()
        override fun getContentLength(): Int = if (unknownLength) -1 else super.getContentLength()
    }.apply { servletPath = path; setContent(bytes); characterEncoding = "UTF-8" }

    @Test fun `known and chunked oversized signup bodies are rejected before binding`() {
        for (unknownLength in listOf(false, true)) {
            val response = MockHttpServletResponse()
            ApiRequestBodyLimit().doFilter(request("/api/v1/accounts/register", ByteArray(16 * 1024 + 1), unknownLength), response) { _, _ -> fail<Unit>("Oversized body reached binding") }
            assertEquals(413, response.status)
            assertEquals("no-store", response.getHeader("Cache-Control"))
            assertTrue(response.contentAsString.contains("\"status\":413"))
        }
    }

    @Test fun `body limit counts encoded bytes and preserves accepted content`() {
        val text = "{\"title\":\"한글 😀\"}"
        val bytes = text.toByteArray(Charsets.UTF_8)
        ApiRequestBodyLimit().doFilter(request("/api/v1/chapters/upload", bytes, true), MockHttpServletResponse()) { wrapped, _ ->
            assertEquals(bytes.size, wrapped.contentLength)
            assertEquals(text, wrapped.reader.readText())
        }
        val response = MockHttpServletResponse()
        ApiRequestBodyLimit().doFilter(request("/api/v1/accounts/register", "한".repeat(6000).toByteArray()), response) { _, _ -> fail<Unit>("UTF-8 byte limit was bypassed") }
        assertEquals(413, response.status)
    }

    @Test fun `exact boundary is accepted and job and chapter budgets are separate`() {
        for ((path, budget) in listOf("/api/v1/accounts/me/password" to 4 * 1024, "/api/v1/accounts/me" to 16 * 1024, "/api/v1/translation-jobs" to 512 * 1024, "/api/v1/catalog-translations" to 512 * 1024, "/api/v1/chapters/upload" to 8 * 1024 * 1024)) {
            var invoked = false
            ApiRequestBodyLimit().doFilter(request(path, ByteArray(budget), true), MockHttpServletResponse()) { wrapped, _ ->
                invoked = true
                assertEquals(budget, (wrapped as HttpServletRequest).inputStream.readBytes().size)
            }
            assertTrue(invoked)
            val response = MockHttpServletResponse()
            ApiRequestBodyLimit().doFilter(request(path, ByteArray(budget + 1), true), response) { _, _ -> fail<Unit>("Limit was bypassed") }
            assertEquals(413, response.status)
        }
    }
}
