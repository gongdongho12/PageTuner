package com.dongholab.pagetuner.translation.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerPasswordChangeTransportTest {
    @Test fun defaultTransportSendsCsrfCookieAndPasswordPostAndReadsEmpty204() = exerciseTransport(dropResponse = false)

    @Test fun passwordPostIsNotReplayedWhenTheServerDropsTheResponse() = exerciseTransport(dropResponse = true)

    private fun exerciseTransport(dropResponse: Boolean) = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 5_000
        val worker = Executors.newSingleThreadExecutor()
        try {
            val captured = worker.submit<List<WireRequest>> {
                val requests = (0..1).map { index -> listener.accept().use { socket ->
                    val request = socket.readRequest()
                    if (index == 0) socket.respond(200, """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""",
                        "Set-Cookie: JSESSIONID=session; Path=/; HttpOnly\r\n")
                    else if (!dropResponse) socket.respond(204, "")
                    request
                } }
                listener.soTimeout = 700
                try {
                    listener.accept().use { error("Password request was replayed") }
                } catch (_: SocketTimeoutException) { /* No follow-up or automatic retry. */ }
                requests
            }
            val client = HttpTranslationStore("http://127.0.0.1:${listener.localPort}", TranslationStoreBasicAuth("reader", " old "))
            val result = runCatching { client.changeAccountPassword(" old ", " new-password ") }
            if (dropResponse) assertEquals(TranslationStoreFailure.NETWORK, (result.exceptionOrNull() as TranslationStoreException).failure)
            else assertTrue(result.isSuccess)
            val requests = captured.get(5, TimeUnit.SECONDS)
            assertEquals("GET /api/v1/accounts/csrf HTTP/1.1", requests[0].line)
            val post = requests[1]
            assertEquals("POST /api/v1/accounts/me/password HTTP/1.1", post.line)
            assertEquals("JSESSIONID=session", post.headers["cookie"])
            assertEquals("csrf-token", post.headers["x-csrf-token"])
            assertTrue(post.headers["authorization"]?.startsWith("Basic ") == true)
            val body = JSONObject(post.body)
            assertEquals(" old ", body.getString("currentPassword"))
            assertEquals(" new-password ", body.getString("newPassword"))
            assertEquals(2, body.length())
        } finally { listener.close(); worker.shutdownNow() }
    }

    private data class WireRequest(val line: String, val headers: Map<String, String>, val body: String)

    private fun Socket.readRequest(): WireRequest {
        soTimeout = 5_000
        val input = getInputStream()
        fun line(): String = buildString {
            while (true) { val byte = input.read(); check(byte >= 0); if (byte == 10) break; if (byte != 13) append(byte.toChar()) }
        }
        val first = line()
        val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        val length = headers["content-length"]?.toInt() ?: 0
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) { val read = input.read(body, offset, length - offset); check(read > 0); offset += read }
        return WireRequest(first, headers, body.toString(Charsets.UTF_8))
    }

    private fun Socket.respond(status: Int, body: String, extraHeaders: String = "") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val response = "HTTP/1.1 $status ${if (status == 204) "No Content" else "OK"}\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${extraHeaders}Connection: close\r\n\r\n"
        getOutputStream().apply { write(response.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
    }
}
