package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real HTTP sockets exercise OkHttp's follow-up engine, without changing any account. */
class AccountMutationTransportTest {
    @Test fun profilePatch503RetryAfterZeroIsSentOnce() = runBlocking { exercise(Patch, 503) }
    @Test fun passwordPost503RetryAfterZeroIsSentOnce() = runBlocking { exercise(Password, 503) }

    // HTTP/1 verifies status preservation; an HTTP/2 coalesced connection is not simulated here.
    @Test fun misdirectedStatusIsPreservedWithoutResendingEitherMutation() = runBlocking {
        listOf(Patch, Password).forEach { exercise(it, 421) }
    }
    @Test fun requestTimeoutResponseIsPreservedWithoutResendingEitherMutation() = runBlocking {
        listOf(Patch, Password).forEach { exercise(it, 408) }
    }
    @Test fun temporaryAndPermanentRedirectsPreserveFirstResponseWithoutForwardingCredentials() = runBlocking {
        listOf(Patch, Password).forEach { mutation -> listOf(307, 308).forEach { exercise(mutation, it) } }
    }
    @Test fun droppedResponseLeavesBothMutationOutcomesUncertainWithoutAutomaticRetry() = runBlocking {
        listOf(Patch, Password).forEach { exercise(it, null) }
    }

    @Test fun registrationAndCsrfStillUseTheExistingStandardTransport() = runBlocking {
        val selected = mutableListOf<String>()
        fun handler(name: String) = TranslationStoreHttpTransport { selected += name; TranslationStoreHttpResponse(200) }
        val transport = DefaultTranslationStoreHttpTransport(handler("standard"), handler("patch"), handler("password"))
        transport.execute(TranslationStoreHttpRequest("https://reader.example/api/v1/accounts/register", "POST", emptyMap(), "{}"))
        transport.execute(TranslationStoreHttpRequest("https://reader.example/api/v1/accounts/csrf", "GET", emptyMap()))
        transport.execute(TranslationStoreHttpRequest("https://reader.example${Patch.path}", Patch.method, emptyMap(), Patch.body))
        transport.execute(TranslationStoreHttpRequest("https://reader.example${Password.path}", Password.method, emptyMap(), Password.body))
        assertEquals(listOf("standard", "standard", "patch", "password"), selected)
    }

    private suspend fun exercise(mutation: Mutation, status: Int?) {
        val listener = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
        val worker = Executors.newSingleThreadExecutor()
        val responseBody = """{"code":"UNCHANGED_RESPONSE","detail":" 원래 응답 🌏 "}"""
        // A different hostname is a different origin. Any redirected request is captured by the same socket.
        val location = "http://localhost:${listener.localPort}/credential-capture"
        try {
            val received = worker.submit<List<WireRequest>> {
                val requests = mutableListOf<WireRequest>()
                while (requests.size < 4) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { break }
                    socket.use {
                        requests += it.readRequest()
                        if (status != null) it.respond(status, responseBody, location)
                    }
                    listener.soTimeout = 700
                }
                requests
            }
            val request = TranslationStoreHttpRequest("http://127.0.0.1:${listener.localPort}${mutation.path}", mutation.method,
                mapOf("Authorization" to "Basic dGVzdDp0ZXN0", "Cookie" to "JSESSIONID=fixture", "X-CSRF-TOKEN" to "fixture-token"), mutation.body)
            val result = runCatching { DefaultTranslationStoreHttpTransport().execute(request) }
            val calls = received.get(10, TimeUnit.SECONDS)
            assertEquals("${mutation.method} must be transmitted once for ${status ?: "a dropped response"}", 1, calls.size)
            val sent = calls.single()
            assertEquals("${mutation.method} ${mutation.path} HTTP/1.1", sent.line)
            assertArrayEquals(mutation.body.toByteArray(Charsets.UTF_8), sent.body)
            assertEquals(mutation.body.toByteArray(Charsets.UTF_8).size.toString(), sent.headers["content-length"])
            assertEquals("Basic dGVzdDp0ZXN0", sent.headers["authorization"])
            assertEquals("JSESSIONID=fixture", sent.headers["cookie"])
            assertEquals("fixture-token", sent.headers["x-csrf-token"])
            if (status == null) {
                assertTrue("An unacknowledged mutation must remain a transport failure.", result.exceptionOrNull() is IOException)
            } else {
                val response = result.getOrThrow()
                assertEquals(status, response.status)
                assertEquals(responseBody, response.body)
                assertEquals("0", response.header("Retry-After"))
                assertEquals(location, response.header("Location"))
                assertEquals("original", response.header("X-Account-Response"))
            }
        } finally { listener.close(); worker.shutdownNow() }
    }

    private data class Mutation(val method: String, val path: String, val body: String)
    private data class WireRequest(val line: String, val headers: Map<String, String>, val body: ByteArray)
    private fun TranslationStoreHttpResponse.header(name: String) = headers.entries.single { it.key.equals(name, true) }.value.single()
    private fun Socket.readRequest(): WireRequest {
        soTimeout = 5000; val input = getInputStream()
        fun line(): String = buildString {
            while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break; if (byte != 13) append(byte.toChar()) }
        }
        val first = line(); val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0); var offset = 0
        while (offset < body.size) { val count = input.read(body, offset, body.size - offset); check(count > 0); offset += count }
        return WireRequest(first, headers, body)
    }
    private fun Socket.respond(status: Int, text: String, location: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        getOutputStream().apply {
            write(("HTTP/1.1 $status Original\r\nContent-Type: application/problem+json; charset=utf-8\r\nContent-Length: ${bytes.size}\r\n" +
                "Retry-After: 0\r\nLocation: $location\r\nX-Account-Response: original\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
            write(bytes); flush()
        }
    }
    companion object {
        private val Patch = Mutation("PATCH", "/api/v1/accounts/me", """{"displayName":" 원문 🌏 ","locale":"fr","targetLanguage":"ja"}""")
        private val Password = Mutation("POST", "/api/v1/accounts/me/password", """{"currentPassword":" old ","newPassword":" new-password "}""")
    }
}
