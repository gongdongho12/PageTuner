package com.dongholab.pagetuner.server

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import org.springframework.web.filter.OncePerRequestFilter

/** Bound JSON bodies before binding, including requests without a Content-Length header. */
class ApiRequestBodyLimit : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method !in setOf("POST", "PUT", "PATCH") || !request.servletPath.startsWith("/api/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val limit = when {
            request.servletPath.startsWith("/api/v1/accounts/") -> 16 * 1024
            request.servletPath == "/api/v1/translation-jobs" || request.servletPath == "/api/v1/catalog-translations" -> 512 * 1024
            else -> 8 * 1024 * 1024
        }
        if (request.contentLengthLong > limit) return reject(response)
        // Read at most one byte over the limit: chunked bodies cannot bypass the limit.
        val body = request.inputStream.readNBytes(limit + 1)
        if (body.size > limit) return reject(response)
        filterChain.doFilter(BufferedBodyRequest(request, body), response)
    }

    private fun reject(response: HttpServletResponse) {
        response.status = 413
        response.contentType = "application/problem+json"
        response.setHeader("Cache-Control", "no-store")
        response.writer.write("""{"type":"about:blank","title":"Payload Too Large","status":413,"detail":"The request body exceeds the size limit."}""")
    }

    private class BufferedBodyRequest(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = body.size
        override fun getContentLengthLong(): Long = body.size.toLong()
        override fun getInputStream(): ServletInputStream {
            val source = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = source.read()
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int = source.read(bytes, offset, length)
                override fun isFinished(): Boolean = source.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) { throw IllegalStateException("Only synchronous request binding is supported.") }
            }
        }
        override fun getReader(): BufferedReader = BufferedReader(InputStreamReader(inputStream, characterEncoding ?: "UTF-8"))
    }
}
