package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.portable.PortableLibraryStore
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PdfContentHttpTransportTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun listener() = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 5000 }
    private fun client(server: ServerSocket) = HttpPdfContentStore("http://127.0.0.1:${server.localPort}", TranslationStoreBasicAuth("reader", "test-password"))
    private fun csrf(socket: Socket) = socket.respond(PdfClientFixture.csrf().body, extra = "Set-Cookie: JSESSIONID=wire; Path=/; HttpOnly\r\n")

    @Test fun realHttpTransportSendsScopedCsrfAndReadsExactStoredBytes() = runBlocking {
        val server = listener(); val worker = Executors.newSingleThreadExecutor(); val input = PdfClientFixture.request()
        try {
            val requests = worker.submit<List<WireRequest>> { (0..4).map { index -> server.accept().use { socket ->
                val request = socket.readRequest()
                when (index) {
                    0, 3 -> csrf(socket)
                    1 -> socket.respond(PdfClientFixture.receipt(input))
                    2 -> socket.respond(PdfClientFixture.record(input))
                    else -> socket.respond(PdfClientFixture.verification(input))
                }; request
            } } }
            val store = client(server)
            val saved = store.upload(input); val got = store.get(saved.recordId); val verified = store.verify(saved.recordId, saved.proof)
            assertEquals(input.content, got.content); assertEquals(got.proof, verified.proof); store.close()
            val wire = requests.get(10, TimeUnit.SECONDS)
            assertEquals("GET /api/v1/csrf HTTP/1.1", wire[0].line)
            assertEquals("POST /api/v1/pdf-content HTTP/1.1", wire[1].line)
            assertArrayEquals(PdfContentWireJson.encodeUpload(input), wire[1].body)
            assertEquals("JSESSIONID=wire", wire[1].headers["cookie"]); assertEquals("csrf-test", wire[1].headers["x-csrf-token"])
            assertTrue(wire.all { it.headers["authorization"]?.startsWith("Basic ") == true && it.headers["accept-encoding"] == "identity" })
            assertNull(wire[2].headers["cookie"])
        } finally { server.close(); worker.shutdownNow() }
    }

    @Test fun droppedUploadResponseIsNotAutomaticallyRetried() = runBlocking {
        val server = listener(); val worker = Executors.newSingleThreadExecutor()
        try {
            val checked = worker.submit<Int> {
                server.accept().use { it.readRequest(); csrf(it) }
                server.accept().use { it.readRequest() } // Persisted-or-not is unknown to the client.
                server.soTimeout = 600
                try { server.accept().use { error("Upload automatically retried") } } catch (_: SocketTimeoutException) { 2 }
            }
            val store = client(server)
            try { store.upload(PdfClientFixture.request()); fail("Expected disconnected response") }
            catch (error: PdfContentClientException) { assertEquals(PdfContentClientFailure.NETWORK, error.failure) }
            assertEquals(2, checked.get(5, TimeUnit.SECONDS)); store.close()
        } finally { server.close(); worker.shutdownNow() }
    }

    @Test fun redirectNeverForwardsAuthorizationToAnotherOrigin() = runBlocking {
        val server = listener(); val target = listener(); val worker = Executors.newSingleThreadExecutor()
        try {
            val sent = worker.submit { server.accept().use { socket -> socket.readRequest()
                socket.respond(ByteArray(0), 302, "Location: http://127.0.0.1:${target.localPort}/capture\r\n") } }
            val store = client(server)
            try { store.get(PdfClientFixture.recordId); fail("Expected redirect rejection") }
            catch (error: PdfContentClientException) { assertEquals(PdfContentClientFailure.REDIRECT, error.failure) }
            sent.get(5, TimeUnit.SECONDS); target.soTimeout = 300
            assertThrows(SocketTimeoutException::class.java) { target.accept().use { error("Credential redirect") } }; store.close()
        } finally { server.close(); target.close(); worker.shutdownNow() }
    }

    @Test fun unavailableAndMisdirectedStatusesReturnUnchangedWithoutAutomaticGetOrPostFollowup() = runBlocking {
        for (method in listOf("GET", "POST")) for (status in listOf(503, 421)) {
            val server = listener(); val worker = Executors.newSingleThreadExecutor()
            try {
                val wire = worker.submit<WireRequest> {
                    val request = server.accept().use { socket ->
                        val captured = socket.readRequest()
                        socket.respond("failure".toByteArray(), status, "Retry-After: 0\r\nX-Original: preserved\r\n")
                        captured
                    }
                    server.soTimeout = 400
                    try { server.accept().use { error("Automatic status follow-up") } } catch (_: SocketTimeoutException) { }
                    request
                }
                val body = if (method == "POST") "explicit body".toByteArray() else null
                val response = DefaultPdfContentHttpTransport().execute(PdfContentHttpRequest(
                    "http://127.0.0.1:${server.localPort}/one-shot", method, emptyMap(), body, 1024))
                assertEquals(status, response.status)
                assertEquals("0", response.headers.entries.single { it.key.equals("Retry-After", true) }.value.single())
                assertEquals("preserved", response.headers.entries.single { it.key.equals("X-Original", true) }.value.single())
                assertArrayEquals("failure".toByteArray(), response.body)
                assertArrayEquals(body ?: ByteArray(0), wire.get(5, TimeUnit.SECONDS).body)
            } finally { server.close(); worker.shutdownNow() }
        }
    }

    @Test fun actualChunkedAndDeclaredBytesRespectPerRequestAndErrorBudgets() = runBlocking {
        for (chunked in listOf(false, true)) for (status in listOf(200, 409)) {
            val server = listener(); val worker = Executors.newSingleThreadExecutor()
            try {
                val sent = worker.submit { server.accept().use { socket -> socket.readRequest(); socket.respond(ByteArray(21) { 32 }, status, chunked = chunked) } }
                val request = PdfContentHttpRequest("http://127.0.0.1:${server.localPort}/bounded", "GET", emptyMap(), null,
                    maxResponseBytes = if (status == 200) 20 else 100, maxErrorBytes = 20)
                try { DefaultPdfContentHttpTransport().execute(request); fail("Exceeded byte budget") }
                catch (_: PdfContentTransportResponseException) { }
                sent.get(5, TimeUnit.SECONDS)
            } finally { server.close(); worker.shutdownNow() }
        }
    }

    @Test fun closeDisconnectsInFlightActualHttpRead() = runBlocking {
        val server = listener(); val worker = Executors.newSingleThreadExecutor(); val entered = CountDownLatch(1)
        try {
            val disconnected = worker.submit<Boolean> { server.accept().use { socket ->
                socket.readRequest()
                socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 500\r\n\r\n{".toByteArray()); flush() }
                entered.countDown(); socket.soTimeout = 4000
                socket.getInputStream().read() == -1
            } }
            val store = client(server); val pending = async(Dispatchers.Default) { store.get(PdfClientFixture.recordId) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); store.close()
            try { pending.await(); fail("Closed response delivered") } catch (_: CancellationException) { }
            assertTrue(disconnected.get(5, TimeUnit.SECONDS))
        } finally { server.close(); worker.shutdownNow() }
    }

    /** Explicit environment opt-in; failures stay failures once a URL has been supplied. */
    @Test fun explicitIsolatedLiveServerRoundTripFromImportedZip() = runBlocking {
        val url = System.getenv("PAGETUNER_PDF_CLIENT_LIVE_URL").orEmpty()
        assumeTrue("Set an isolated PDF test server URL to opt in.", url.isNotBlank())
        val username = requireNotNull(System.getenv("PAGETUNER_PDF_CLIENT_LIVE_USER")) { "Missing PDF test account." }
        val password = requireNotNull(System.getenv("PAGETUNER_PDF_CLIENT_LIVE_PASSWORD")) { "Missing PDF test password." }
        val fixture = PdfClientFixture.request(); val validated = PdfContentValidation.validate(fixture)
        val document = ExchangeDocument("fixture-pdf", "Fixture PDF", "Original", fixture.content.language, "local", fixture.content.paragraphs, assets = fixture.content.assets)
        val zip = LibraryExchangeCodec.write(LibraryExchangePackage("2026-10-05T00:00:00Z", listOf(document), validated.assets))
        val store = PortableLibraryStore(temporary.newFolder()); val entry = store.importArchive(zip).entries.single()
        val prepared = store.preparePdfContent(entry)
        val client = HttpPdfContentStore(url, TranslationStoreBasicAuth(username, password))
        try {
            val request = PdfContentUpload(UUID.randomUUID().toString(), prepared.content)
            val receipt = client.upload(request)
            assertEquals(receipt, client.upload(request))
            val read = client.get(receipt.recordId)
            assertEquals(prepared.content, read.content); assertEquals(prepared.proof, read.proof)
            val actual = PdfContentValidation.validateContent(read.content)
            prepared.assets.forEach { expected -> assertArrayEquals(expected.bytes, actual.assets.single { it.path == expected.path }.bytes) }
            assertTrue(client.verify(receipt.recordId, prepared.proof).verified)
        } finally { client.close() }
        try { client.get(PdfClientFixture.recordId); fail("Closed client accepted work") }
        catch (error: PdfContentClientException) { assertEquals(PdfContentClientFailure.CLOSED, error.failure) }
    }

    private data class WireRequest(val line: String, val headers: Map<String, String>, val body: ByteArray)
    private fun Socket.readRequest(): WireRequest {
        soTimeout = 5000; val input = getInputStream()
        fun line(): String = buildString { while (true) { val next = input.read(); check(next >= 0); if (next == 10) break; if (next != 13) append(next.toChar()) } }
        val first = line(); val headers = mutableMapOf<String, String>()
        while (true) { val value = line(); if (value.isEmpty()) break; headers[value.substringBefore(':').lowercase()] = value.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0); var offset = 0
        while (offset < body.size) { val count = input.read(body, offset, body.size - offset); check(count > 0); offset += count }
        return WireRequest(first, headers, body)
    }
    private fun Socket.respond(body: ByteArray, status: Int = 200, extra: String = "", chunked: Boolean = false) {
        val framing = if (chunked) "Transfer-Encoding: chunked\r\n" else "Content-Length: ${body.size}\r\n"
        getOutputStream().apply {
            write("HTTP/1.1 $status OK\r\nContent-Type: application/json\r\n$framing${extra}Connection: close\r\n\r\n".toByteArray())
            if (chunked) write("${body.size.toString(16)}\r\n".toByteArray())
            write(body); if (chunked) write("\r\n0\r\n\r\n".toByteArray()); flush()
        }
    }
}
