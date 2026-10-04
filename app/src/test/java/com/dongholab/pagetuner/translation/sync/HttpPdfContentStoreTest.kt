package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

internal object PdfClientFixture {
    val recordId = "d8b05db5-5e72-42cf-a41b-0a6ec421ec3e"
    val createdAt = "2026-10-05T00:00:00Z"
    fun request(): PdfContentUpload {
        val root = JSONObject(requireNotNull(javaClass.getResource("/pdf-content-v1.json")).readText()).getJSONObject("upload")
        val content = root.getJSONObject("content")
        val paragraphs = content.getJSONArray("paragraphs"); val refs = content.getJSONArray("assets"); val payloads = content.getJSONArray("payloads")
        return PdfContentUpload(root.getString("uploadId"), PdfContentDocument(1, content.getString("language"),
            List(paragraphs.length()) { paragraphs.getJSONObject(it).let { value -> ExchangeParagraph(value.getString("paragraphId"), value.getString("text")) } },
            List(refs.length()) { refs.getJSONObject(it).let { value -> ExchangeAssetReference(value.getString("path"), value.getString("role"),
                if (value.isNull("paragraphId")) null else value.getString("paragraphId"), if (value.isNull("alt")) null else value.getString("alt")) } },
            List(payloads.length()) { payloads.getJSONObject(it).let { value -> PdfContentPayload(value.getString("path"), value.getString("mimeType"), value.getString("base64")) } }))
    }
    fun receipt(request: PdfContentUpload = request()): ByteArray = JSONObject().put("recordId", recordId).put("createdAt", createdAt)
        .put("proof", proof(request)).toString().toByteArray()
    fun record(request: PdfContentUpload = request()): ByteArray = JSONObject(String(receipt(request), Charsets.UTF_8))
        .put("content", JSONObject(String(PdfContentWireJson.encodeUpload(request), Charsets.UTF_8)).getJSONObject("content")).toString().toByteArray()
    fun verification(request: PdfContentUpload = request()): ByteArray = JSONObject().put("recordId", recordId).put("verified", true)
        .put("proof", proof(request)).toString().toByteArray()
    fun proof(request: PdfContentUpload) = JSONObject(String(PdfContentWireJson.encodeVerification(PdfContentValidation.validate(request).proof), Charsets.UTF_8)).getJSONObject("proof")
    fun response(body: ByteArray, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
        PdfContentHttpResponse(status, mapOf("Content-Type" to listOf("application/json")) + headers, body)
    fun csrf() = response("""{"headerName":"X-CSRF-TOKEN","token":"csrf-test"}""".toByteArray(),
        headers = mapOf("Set-Cookie" to listOf("JSESSIONID=test-session; Path=/; HttpOnly")))
}

class HttpPdfContentStoreTest {
    private fun client(transport: PdfContentHttpTransport) = HttpPdfContentStore("https://reader.example", TranslationStoreBasicAuth("reader", "password"), transport)
    private suspend fun failure(expected: PdfContentClientFailure, operation: suspend () -> Unit) {
        try { operation(); fail("Expected $expected") }
        catch (error: PdfContentClientException) { assertEquals(expected, error.failure); assertFalse(error.message.orEmpty().contains("password")) }
    }
    @Test fun explicitUploadReadVerifyAndCallerRetryPreserveExactBytesAndScope() = runBlocking {
        val calls = mutableListOf<PdfContentHttpRequest>(); val original = PdfClientFixture.request()
        val store = client { request -> calls += request
            when {
                request.url.endsWith("/csrf") -> PdfClientFixture.csrf()
                request.url.endsWith("/verify") -> PdfClientFixture.response(PdfClientFixture.verification(original))
                request.method == "GET" -> PdfClientFixture.response(PdfClientFixture.record(original))
                else -> PdfClientFixture.response(PdfClientFixture.receipt(original))
            }
        }
        val first = store.upload(original); val read = store.get(first.recordId); val verified = store.verify(first.recordId, first.proof)
        assertEquals(original.content, read.content); assertEquals(first.proof, read.proof); assertTrue(verified.verified)
        assertEquals(first, store.upload(original))
        val posts = calls.filter { it.method == "POST" }
        assertEquals(3, posts.size)
        assertArrayEquals(posts.first().body, posts.last().body)
        assertEquals(original.uploadId, JSONObject(String(posts.first().body!!)).getString("uploadId"))
        assertTrue(posts.all { it.headers["Cookie"] == "JSESSIONID=test-session" && it.headers["X-CSRF-TOKEN"] == "csrf-test" })
        assertTrue(calls.all { it.headers["Authorization"]?.startsWith("Basic ") == true && it.headers["Accept-Encoding"] == "identity" })
        assertTrue(calls.filter { it.url.endsWith("/csrf") }.all { "Cookie" !in it.headers && it.maxResponseBytes == 64 * 1024 })
        assertEquals(12 * 1024 * 1024, calls.single { it.method == "GET" && !it.url.endsWith("/csrf") }.maxResponseBytes)
        assertTrue(posts.all { it.maxResponseBytes == 2 * 1024 * 1024 && it.maxErrorBytes == 64 * 1024 })
        store.close(); failure(PdfContentClientFailure.CLOSED) { store.get(first.recordId) }
    }
    @Test fun callerListsAreCapturedBeforeCsrfAndExplicitRetryNeverRegeneratesUploadId() = runBlocking {
        val original = PdfClientFixture.request(); val paragraphs = original.content.paragraphs.toMutableList()
        val request = original.copy(content = original.content.copy(paragraphs = paragraphs))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val bodies = mutableListOf<ByteArray>()
        val store = client { value -> if (value.url.endsWith("/csrf")) { entered.complete(Unit); release.await(); PdfClientFixture.csrf() }
            else { bodies += value.body!!; PdfClientFixture.response(PdfClientFixture.receipt(original)) } }
        val saving = async(start = CoroutineStart.UNDISPATCHED) { store.upload(request) }
        entered.await(); paragraphs.clear(); release.complete(Unit)
        assertEquals(PdfContentValidation.validate(original).proof, saving.await().proof)
        assertArrayEquals(PdfContentWireJson.encodeUpload(original), bodies.single())
        store.close()
    }
    @Test fun generationChangeAndCloseDiscardLateUncooperativeCsrfAndGetResponses() = runBlocking {
        for (duringCsrf in listOf(true, false)) {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var calls = 0
            val store = client { calls++; withContext(NonCancellable) { entered.complete(Unit); release.await() }
                if (duringCsrf) PdfClientFixture.csrf() else PdfClientFixture.response(PdfClientFixture.record()) }
            val pending = async { if (duringCsrf) store.upload(PdfClientFixture.request()) else store.get(PdfClientFixture.recordId) }
            entered.await(); store.invalidate(); store.close(); release.complete(Unit)
            try { pending.await(); fail("Stale operation returned") } catch (_: CancellationException) { }
            assertEquals(1, calls)
            // Same account and origin on a replacement client cannot revive the old generation.
            val replacement = client { PdfClientFixture.response(PdfClientFixture.record()) }
            assertEquals(PdfClientFixture.recordId, replacement.get(PdfClientFixture.recordId).recordId); replacement.close()
        }
    }
    @Test fun typedServerFailuresRetainNoUntrustedDetails() = runBlocking {
        for ((status, code, expected) in listOf(
            Triple(400, "PDF_CONTENT_INVALID", PdfContentClientFailure.INVALID_REQUEST), Triple(404, "PDF_CONTENT_NOT_FOUND", PdfContentClientFailure.NOT_FOUND),
            Triple(409, "PDF_CONTENT_UPLOAD_REUSED", PdfContentClientFailure.UPLOAD_REUSED), Triple(409, "PDF_CONTENT_MISMATCH", PdfContentClientFailure.MISMATCH),
            Triple(409, "PDF_CONTENT_UNAVAILABLE", PdfContentClientFailure.UNAVAILABLE), Triple(413, "PDF_CONTENT_TOO_LARGE", PdfContentClientFailure.TOO_LARGE),
            Triple(415, "PDF_CONTENT_ENCODING", PdfContentClientFailure.UNSUPPORTED_ENCODING), Triple(409, "other", PdfContentClientFailure.INVALID_RESPONSE))) {
            val store = client { PdfClientFixture.response(JSONObject().put("status", status).put("code", code).put("detail", "password secret").toString().toByteArray(), status,
                mapOf("Content-Type" to listOf("application/problem+json"))) }
            failure(expected) { store.get(PdfClientFixture.recordId) }; store.close()
        }
        for ((status, expected) in listOf(401 to PdfContentClientFailure.AUTHENTICATION, 403 to PdfContentClientFailure.FORBIDDEN,
            302 to PdfContentClientFailure.REDIRECT, 503 to PdfContentClientFailure.SERVER)) {
            val store = client { PdfClientFixture.response(ByteArray(0), status) }; failure(expected) { store.get(PdfClientFixture.recordId) }; store.close()
        }
    }
    @Test fun wrongMediaEncodingMalformedUtf8OversizedAndForgedPayloadResponsesAreRejected() = runBlocking {
        val valid = PdfClientFixture.response(PdfClientFixture.record())
        val tampered = JSONObject(String(valid.body)).apply { getJSONObject("content").put("language", "fr") }.toString().toByteArray()
        val invalid = listOf(valid.copy(headers = emptyMap()), valid.copy(headers = mapOf("Content-Type" to listOf("text/html"))),
            valid.copy(headers = mapOf("Content-Type" to listOf("application/json", "application/json"))),
            valid.copy(headers = mapOf("Content-Type" to listOf("application/json;charset=latin1"))),
            valid.copy(headers = valid.headers + ("Content-Encoding" to listOf("gzip"))),
            valid.copy(body = byteArrayOf(0xc3.toByte(), 0x28)), valid.copy(body = ByteArray(12 * 1024 * 1024 + 1)), valid.copy(body = tampered),
            valid.copy(status = 409, body = ByteArray(64 * 1024 + 1)))
        for (response in invalid) {
            val store = client { response }; failure(PdfContentClientFailure.INVALID_RESPONSE) { store.get(PdfClientFixture.recordId) }; store.close()
        }
    }
    @Test fun csrfCookieDomainPathAndHeaderCannotLeakCredentialsOrStartUpload() = runBlocking {
        for (bad in listOf(PdfClientFixture.csrf().copy(headers = PdfClientFixture.csrf().headers + ("Set-Cookie" to listOf("JSESSIONID=x; Domain=evil.example; Path=/"))),
            PdfClientFixture.csrf().copy(headers = PdfClientFixture.csrf().headers + ("Set-Cookie" to listOf("JSESSIONID=x; Path=/elsewhere"))),
            PdfClientFixture.csrf().copy(body = """{"headerName":"Authorization","token":"evil"}""".toByteArray()),
            PdfClientFixture.csrf().copy(body = """{"headerName":"X-CSRF-TOKEN","token":"one","token":"two"}""".toByteArray()))) {
            var calls = 0; val store = client { calls++; bad }
            failure(PdfContentClientFailure.INVALID_RESPONSE) { store.upload(PdfClientFixture.request()) }; assertEquals(1, calls); store.close()
        }
        for (url in listOf("https://user:secret@reader.example", "https://reader.example/path", "https://reader.example?query", "https://reader.example#fragment", "http://remote.example")) {
            assertThrows(IllegalArgumentException::class.java) { HttpPdfContentStore(url, TranslationStoreBasicAuth("reader", "secret")) }
        }
    }
}
