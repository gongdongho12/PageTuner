package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import java.io.Closeable
import java.io.IOException
import java.net.HttpCookie
import java.net.URI
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

enum class PdfContentClientFailure {
    AUTHENTICATION, FORBIDDEN, NOT_FOUND, UPLOAD_REUSED, MISMATCH, UNAVAILABLE, TOO_LARGE,
    UNSUPPORTED_ENCODING, INVALID_REQUEST, INVALID_RESPONSE, REDIRECT, SERVER, NETWORK, TIMEOUT, CLOSED,
}
class PdfContentClientException(val failure: PdfContentClientFailure, val status: Int? = null) :
    IOException("PDF storage request failed: $failure${status?.let { " (HTTP $it)" }.orEmpty()}.")

/** Explicit storage operations only. An account/origin change must close this credentials-bound client. */
class HttpPdfContentStore(baseUrl: String, private val auth: TranslationStoreBasicAuth,
    private val transport: PdfContentHttpTransport = DefaultPdfContentHttpTransport(),
    allowInsecureDevelopmentHttp: Boolean = false) : Closeable {
    private val base = safeOrigin(baseUrl, allowInsecureDevelopmentHttp)
    private val lock = Any()
    private var generation = 0L
    private var closed = false
    private val jobs = mutableSetOf<Job>()

    suspend fun upload(request: PdfContentUpload): PdfContentReceipt {
        requestValue {
            require(request.content.paragraphs.size <= PdfContentValidation.MAX_PARAGRAPHS)
            require(request.content.assets.size in 1..PdfContentValidation.MAX_REFERENCES)
            require(request.content.payloads.size in 1..PdfContentValidation.MAX_PAYLOADS)
        }
        // Capture every caller-owned list before the first suspension; the retry ID remains caller-owned.
        val captured = request.copy(content = request.content.copy(paragraphs = request.content.paragraphs.map { it.copy() },
            assets = request.content.assets.map { it.copy() }, payloads = request.content.payloads.map { it.copy() }))
        return operation { ticket ->
            val validated = requestValue { PdfContentValidation.validate(captured) }
            val body = requestValue { PdfContentWireJson.encodeUpload(captured) }
            val response = post(ticket, "/api/v1/pdf-content", body)
            decode { PdfContentWireJson.decodeReceipt(response.body, validated.proof) }
        }
    }

    suspend fun get(recordId: String): PdfContentRecord = operation { ticket ->
        requestValue { PdfContentValidation.validateUuid(recordId) }
        val response = execute(ticket, "/api/v1/pdf-content/$recordId", "GET", limit = 12 * 1024 * 1024)
        decode { PdfContentWireJson.decodeRecord(response.body, recordId) }
    }

    suspend fun verify(recordId: String, proof: PortableContentProof): PdfContentVerification {
        requestValue { require(proof.assets.size in 1..PdfContentValidation.MAX_REFERENCES) }
        val captured = proof.copy(assets = proof.assets.map { it.copy() })
        return operation { ticket ->
            requestValue { PdfContentValidation.validateUuid(recordId) }
            val body = requestValue { PdfContentWireJson.encodeVerification(captured) }
            val response = post(ticket, "/api/v1/pdf-content/$recordId/verify", body)
            decode { PdfContentWireJson.decodeVerification(response.body, recordId, captured) }
        }
    }

    /** Expire pending operations even across A→B→A transitions. No request or cookie is retried here. */
    fun invalidate() = expire(false)
    override fun close() = expire(true)
    private fun expire(permanently: Boolean) {
        val pending = synchronized(lock) { generation++; closed = closed || permanently; jobs.toList() }
        pending.forEach { it.cancel(CancellationException("PDF storage connection changed.")) }
    }

    private suspend fun <T> operation(block: suspend (Long) -> T): T = coroutineScope {
        val job = requireNotNull(currentCoroutineContext()[Job])
        val ticket = synchronized(lock) {
            if (closed) throw PdfContentClientException(PdfContentClientFailure.CLOSED)
            jobs.add(job); generation
        }
        try {
            withContext(Dispatchers.IO) {
                checkCurrent(ticket)
                block(ticket).also { checkCurrent(ticket) }
            }
        } finally { synchronized(lock) { jobs.remove(job) } }
    }
    private suspend fun checkCurrent(ticket: Long) {
        currentCoroutineContext().ensureActive()
        synchronized(lock) { if (closed || generation != ticket) throw CancellationException("PDF storage connection changed.") }
    }
    private suspend fun post(ticket: Long, path: String, body: ByteArray): PdfContentHttpResponse {
        val bootstrap = execute(ticket, "/api/v1/csrf", "GET", limit = 64 * 1024)
        val csrf = decode { PdfContentWireJson.decodeCsrf(bootstrap.body) }
        val header = csrf.first; val token = csrf.second
        if (header.uppercase() !in setOf("X-CSRF-TOKEN", "X-XSRF-TOKEN") || token.isBlank() || token.length > 4096 || token.any { it.code < 33 || it.code > 126 })
            throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE)
        val cookies = decode { sessionCookies(bootstrap, path) }
        if (cookies.isEmpty()) throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE)
        checkCurrent(ticket)
        return execute(ticket, path, "POST", body, mapOf(header to token, "Cookie" to cookies), 2 * 1024 * 1024)
    }
    private suspend fun execute(ticket: Long, path: String, method: String, body: ByteArray? = null,
        extra: Map<String, String> = emptyMap(), limit: Int): PdfContentHttpResponse {
        checkCurrent(ticket)
        val response = try {
            transport.execute(PdfContentHttpRequest(base.resolve(path).toString(), method,
                mapOf("Authorization" to auth.header, "Accept" to "application/json", "Content-Type" to "application/json; charset=utf-8",
                    "Accept-Encoding" to "identity", "X-Requested-With" to "XMLHttpRequest") + extra, body, limit))
        } catch (error: CancellationException) { throw error }
        catch (_: InterruptedIOException) { checkCurrent(ticket); throw PdfContentClientException(PdfContentClientFailure.TIMEOUT) }
        catch (_: PdfContentTransportResponseException) { checkCurrent(ticket); throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE) }
        catch (_: IOException) { checkCurrent(ticket); throw PdfContentClientException(PdfContentClientFailure.NETWORK) }
        checkCurrent(ticket)
        if (response.body.size > if (response.status in 200..299) limit else 64 * 1024)
            throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE)
        if (response.status == 200 || response.body.isNotEmpty()) {
            val types = response.headers.entries.filter { it.key.equals("Content-Type", true) }.flatMap { it.value }
            val media = if (response.status == 200) "application/json" else "application/(?:problem\\+json|json)"
            if (types.size != 1 || !Regex("$media(?:\\s*;\\s*charset\\s*=\\s*(?:\"utf-8\"|utf-8))?", RegexOption.IGNORE_CASE).matches(types.single().trim()))
                throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE)
        }
        decode { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(response.body)) }
        val encoding = response.headers.entries.filter { it.key.equals("Content-Encoding", true) }.flatMap { it.value }
        if (encoding.size > 1 || encoding.any { !it.equals("identity", true) }) throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE)
        if (response.status != 200) throw failure(response)
        return response
    }
    private fun failure(response: PdfContentHttpResponse): PdfContentClientException {
        val status = response.status
        val failure = when (status) {
            401 -> PdfContentClientFailure.AUTHENTICATION
            403 -> PdfContentClientFailure.FORBIDDEN
            in 300..399 -> PdfContentClientFailure.REDIRECT
            in 500..599 -> PdfContentClientFailure.SERVER
            else -> {
                val code = decode { PdfContentWireJson.decodeProblemCode(response.body) }
                when (status to code) {
                    400 to "PDF_CONTENT_INVALID" -> PdfContentClientFailure.INVALID_REQUEST
                    404 to "PDF_CONTENT_NOT_FOUND" -> PdfContentClientFailure.NOT_FOUND
                    409 to "PDF_CONTENT_UPLOAD_REUSED" -> PdfContentClientFailure.UPLOAD_REUSED
                    409 to "PDF_CONTENT_MISMATCH" -> PdfContentClientFailure.MISMATCH
                    409 to "PDF_CONTENT_UNAVAILABLE" -> PdfContentClientFailure.UNAVAILABLE
                    413 to "PDF_CONTENT_TOO_LARGE" -> PdfContentClientFailure.TOO_LARGE
                    415 to "PDF_CONTENT_ENCODING" -> PdfContentClientFailure.UNSUPPORTED_ENCODING
                    else -> PdfContentClientFailure.INVALID_RESPONSE
                }
            }
        }
        return PdfContentClientException(failure, status)
    }
    private fun sessionCookies(response: PdfContentHttpResponse, path: String): String {
        val accepted = response.headers.entries.filter { it.key.equals("Set-Cookie", true) }.flatMap { it.value }.flatMap(HttpCookie::parse)
            .filter { cookie ->
                val domain = cookie.domain?.trimStart('.'); val cookiePath = cookie.path ?: "/"
                (domain == null || base.host.equals(domain, true)) && (path == cookiePath || path.startsWith(cookiePath.trimEnd('/') + "/")) &&
                    cookie.maxAge != 0L && (!cookie.secure || base.scheme == "https")
            }
        require(accepted.map { it.name }.distinct().size == accepted.size)
        return accepted.onEach { require(it.name.matches(Regex("[A-Za-z0-9_-]+")) && it.value.isNotEmpty() &&
            it.value.all { c -> c.code in 33..126 && c != ';' && c != ',' }) }.joinToString("; ") { "${it.name}=${it.value}" }
    }
    private fun <T> decode(action: () -> T): T = try { action() }
        catch (error: CancellationException) { throw error }
        catch (_: Exception) { throw PdfContentClientException(PdfContentClientFailure.INVALID_RESPONSE) }
    private fun <T> requestValue(action: () -> T): T = try { action() }
        catch (_: IllegalArgumentException) { throw PdfContentClientException(PdfContentClientFailure.INVALID_REQUEST) }
    companion object {
        private fun safeOrigin(value: String, development: Boolean): URI = try {
            URI(value).also { uri ->
                require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null)
                require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/")
                require(uri.port == -1 || uri.port in 1..65535)
                require(uri.scheme == "https" || development || uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]"))
            }
        } catch (_: Exception) { throw IllegalArgumentException("A safe HTTP(S) server origin is required.") }
    }
}
