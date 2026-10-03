package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.sharing.SharedAsset
import com.dongholab.pagetuner.core.sharing.SharedBookSummary
import com.dongholab.pagetuner.core.sharing.SharedDocument
import com.dongholab.pagetuner.core.sharing.SharedLibraryPage
import com.dongholab.pagetuner.core.sharing.SharedOutlineItem
import com.dongholab.pagetuner.core.sharing.SharedParagraph
import com.dongholab.pagetuner.core.sharing.SharedReadingAnchor
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class LocalSharingServerTest {
    private val clock = AtomicLong(System.currentTimeMillis())
    private val calls = AtomicInteger()
    private val pdf = "%PDF-local-only".toByteArray()
    private val fixtureJson = JSONObject(File("../contracts/fixtures/local-sharing-v1/document.json").readText())
    private var document = fixtureDocument()
    private var assetRevision: String? = null
    private var assetResult = SharedBinary("application/pdf", pdf.size.toLong()) { pdf.inputStream() }
    private var documentFailure: Exception? = null
    private var listing: (Int, Int) -> SharedLibraryPage = { offset, limit ->
        SharedLibraryPage(listOf(SharedBookSummary(document.id, document.title, document.format, document.edition)), 1, offset, limit)
    }
    private val library = object : SharingLibrary {
        override fun list(offset: Int, limit: Int): SharedLibraryPage { calls.incrementAndGet(); return listing(offset, limit) }
        override fun document(id: String): SharedDocument? { calls.incrementAndGet(); documentFailure?.let { throw it }; return document.takeIf { it.id == id } }
        override fun asset(documentId: String, revision: String, assetId: String): SharedBinary? {
            calls.incrementAndGet()
            assetRevision = revision
            if (revision != document.revision) throw SharingUnavailableException("document_changed")
            return assetResult.takeIf { documentId == document.id && assetId == "pdf" }
        }
    }
    private val assets = SharingWebAssets { path ->
        when (path) {
            "index.html" -> SharedBinary("text/html", 26) { "<html>Local sharing</html>".byteInputStream() }
            "assets/reader.js" -> SharedBinary("text/javascript", 10) { "// reader\n".byteInputStream() }
            else -> null
        }
    }
    private lateinit var server: LocalSharingServer
    private lateinit var info: SharingSessionInfo

    @Before fun start() {
        server = LocalSharingServer("127.0.0.1", 0, library, assets, clockMillis = clock::get)
        info = server.startSharing()
    }

    @After fun stop() { server.stopSharing() }

    @Test fun `public status reveals no pairing or library secrets and list requires header authentication`() {
        val status = request("/api/share/v1/status")
        assertEquals(200, status.status)
        assertEquals(setOf("version", "readOnly", "expiresAt"), status.json().keys().asSequence().toSet())
        assertTrue(status.json().getBoolean("readOnly"))
        assertFalse(status.body.contains(info.pairingCode))
        assertEquals(401, request("/api/share/v1/books").status)
        assertEquals(0, calls.get())
        assertEquals("no-store", status.headers["cache-control"])
        assertEquals("DENY", status.headers["x-frame-options"])
        assertFalse(status.headers.containsKey("access-control-allow-origin"))
        assertFalse(status.headers.containsKey("set-cookie"))
    }

    @Test fun `paired document JSON preserves the shared fixture IDs Unicode and anchor`() {
        val token = pair()
        assertTrue(token.matches(Regex("[a-f0-9]{64}")))
        val response = request("/api/share/v1/books/${document.id}", token = token)
        assertEquals(200, response.status)
        assertTrue(fixtureJson.similar(response.json()))
        val page = request("/api/share/v1/books?offset=12&limit=3", token = token).json()
        assertEquals(12, page.getInt("offset"))
        assertEquals(3, page.getInt("limit"))
        assertEquals(document.id, page.getJSONArray("items").getJSONObject(0).getString("id"))
    }

    @Test fun `library pagination rejects unbounded malformed duplicate and token query parameters`() {
        val token = pair()
        listOf("limit=51", "limit=0", "offset=-1", "offset=2147483648", "offset=x", "limit=1&limit=2", "token=$token").forEach { query ->
            assertEquals(query, 400, request("/api/share/v1/books?$query", token = token).status)
        }
        assertEquals(0, calls.get())
        assertEquals(401, request("/api/share/v1/books?token=$token").status)
        assertEquals(401, request("/api/share/v1/books", headers = mapOf("Cookie" to "X-PageTuner-Session=$token")).status)
    }

    @Test fun `binary assets are authenticated bounded and pinned to revision`() {
        val token = pair()
        val path = "/api/share/v1/books/${document.id}/assets/pdf"
        assertEquals(401, request("$path?revision=${document.revision}").status)
        assertEquals(400, request(path, token = token).status)
        val response = request("$path?revision=${document.revision}", token = token)
        assertEquals(200, response.status)
        assertEquals(pdf.toString(Charsets.UTF_8), response.body)
        assertEquals(document.revision, assetRevision)
        val stale = request("$path?revision=old-revision", token = token)
        assertEquals(409, stale.status)
        assertEquals("document_changed", stale.json().getString("code"))
        assetResult = SharedBinary("application/pdf", 32L * 1024 * 1024 + 1) { error("oversized asset must not open") }
        assertEquals(413, request("$path?revision=${document.revision}", token = token).status)
        assetResult = SharedBinary("image/svg+xml", 0) { error("active asset must not open") }
        assertEquals(422, request("$path?revision=${document.revision}", token = token).status)
    }

    @Test fun `unexpected host origin and fetch site never reach the library or pairing`() {
        val token = pair()
        listOf(
            mapOf("Host" to "attacker.example:${info.port}"),
            mapOf("Host" to "127.0.0.1:${info.port + 1}"),
            mapOf("Origin" to "http://attacker.example"),
            mapOf("Origin" to "null"),
            mapOf("Sec-Fetch-Site" to "cross-site"),
        ).forEach { headers ->
            val response = raw("GET", "/api/share/v1/books", headers + ("X-PageTuner-Session" to token))
            assertEquals(headers.toString(), 403, response.status)
        }
        assertEquals(403, raw("POST", "/api/share/v1/pair", mapOf("Origin" to "https://other.example"), "{\"code\":\"${info.pairingCode}\"}").status)
        assertEquals(0, calls.get())
        assertEquals(200, raw("GET", "/api/share/v1/books", mapOf("X-PageTuner-Session" to token, "Origin" to origin())).status)
    }

    @Test fun `decoded and double encoded traversal cannot access generated assets or library`() {
        listOf("/../books.json", "/assets/../../books.json", "/assets/%2e%2e/books.json", "/assets/%252e%252e/books.json", "/assets/x%5c..%5cbooks.json", "/assets/x%00.js").forEach { path ->
            assertEquals(path, 400, raw("GET", path).status)
        }
        listOf("/books.json", "/sw.js", "/manifest.webmanifest", "/.env", "/api/account").forEach { path ->
            assertEquals(path, 404, request(path).status)
        }
        assertEquals(0, calls.get())
    }

    @Test fun `static entry and generated scripts are available before pairing without a service worker`() {
        assertEquals("<html>Local sharing</html>", request("/").body)
        val script = request("/assets/reader.js")
        assertEquals(200, script.status)
        assertEquals("// reader\n", script.body)
        assertTrue(script.headers.getValue("content-security-policy").contains("script-src 'self'"))
        assertTrue(script.headers.getValue("content-security-policy").contains("'wasm-unsafe-eval'"))
        assertFalse(script.headers.getValue("content-security-policy").contains("'unsafe-eval'"))
        assertEquals(404, request("/assets/missing.js").status)
    }

    @Test fun `pair body must be bounded JSON with exactly one string code`() {
        assertEquals(413, request("/api/share/v1/pair", "POST", " ".repeat(1025)).status)
        assertEquals(415, request("/api/share/v1/pair", "POST", "code=${info.pairingCode}", headers = mapOf("Content-Type" to "application/x-www-form-urlencoded")).status)
        listOf("{}", "{\"code\":12345678}", "{\"code\":\"${info.pairingCode}\",\"remoteAccountId\":\"x\"}", "{\"code\":\"${info.pairingCode}\"} garbage").forEach { payload ->
            assertEquals(payload, 400, request("/api/share/v1/pair", "POST", payload).status)
        }
        assertEquals(400, raw("POST", "/api/share/v1/pair", mapOf("Transfer-Encoding" to "chunked")).status)
    }

    @Test fun `pairing guesses are limited even before a correct code and recover after the window`() {
        val wrong = if (info.pairingCode == "00000000") "99999999" else "00000000"
        repeat(10) { assertEquals(401, request("/api/share/v1/pair", "POST", "{\"code\":\"$wrong\"}").status) }
        val limited = request("/api/share/v1/pair", "POST", "{\"code\":\"${info.pairingCode}\"}")
        assertEquals(429, limited.status)
        assertEquals("60", limited.headers["retry-after"])
        clock.addAndGet(60_001)
        assertTrue(pair().isNotBlank())
    }

    @Test fun `a ninth correct pairing replaces the oldest unused token instead of locking the browser out`() {
        val tokens = List(8) { pair() }
        val replacement = pair()
        assertEquals(401, request("/api/share/v1/books", token = tokens[0]).status)
        tokens.drop(1).forEach { assertEquals(200, request("/api/share/v1/books", token = it).status) }
        assertEquals(200, request("/api/share/v1/books", token = replacement).status)
    }

    @Test fun `authenticated use keeps an older browser ahead of unused sessions when pairing replaces one`() {
        val tokens = List(8) { pair() }
        assertEquals(200, request("/api/share/v1/books", token = tokens[0]).status)
        val replacement = pair()
        assertEquals(401, request("/api/share/v1/books", token = tokens[1]).status)
        (listOf(tokens[0]) + tokens.drop(2) + replacement).forEach { assertEquals(200, request("/api/share/v1/books", token = it).status) }
    }

    @Test fun `incorrect pairing never evicts any existing browser session`() {
        val tokens = List(8) { pair() }
        val wrong = if (info.pairingCode == "00000000") "99999999" else "00000000"
        assertEquals(401, request("/api/share/v1/pair", "POST", "{\"code\":\"$wrong\"}").status)
        tokens.forEach { assertEquals(200, request("/api/share/v1/books", token = it).status) }
    }

    @Test fun `disconnect revokes only the selected browser`() {
        val tokens = List(2) { pair() }
        assertEquals(204, request("/api/share/v1/session", "DELETE", token = tokens[0]).status)
        assertEquals(401, request("/api/share/v1/books", token = tokens[0]).status)
        assertEquals(200, request("/api/share/v1/books", token = tokens[1]).status)
        assertTrue(pair().isNotBlank())
    }

    @Test fun `expiry immediately invalidates existing tokens and start creates a new lifetime`() {
        val token = pair()
        clock.set(info.expiresAt)
        assertEquals(410, request("/api/share/v1/books", token = token).status)
        assertNull(server.sessionInfo)
        assertFalse(server.isRunning)
        info = server.startSharing()
        assertTrue(server.isRunning)
        assertEquals(401, request("/api/share/v1/books", token = token).status)
        assertEquals(200, request("/api/share/v1/books", token = pair()).status)
    }

    @Test fun `stop closes the listening socket and restart cannot reuse a session token`() {
        val token = pair()
        server.stopSharing()
        assertFalse(server.isRunning)
        val connection = runCatching { Socket().use { it.connect(InetSocketAddress(info.address, info.port), 1000) } }
        assertTrue(connection.isFailure)
        info = server.startSharing()
        assertEquals(401, request("/api/share/v1/books", token = token).status)
    }

    @Test fun `expiry timer closes listener even when an earlier request already invalidated the session`() {
        server.stopSharing()
        server = LocalSharingServer("127.0.0.1", 0, library, assets, sessionDurationMillis = 800, clockMillis = clock::get)
        info = server.startSharing()
        clock.set(info.expiresAt)
        assertEquals(410, request("/api/share/v1/status").status)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        var listenerClosed = false
        while (System.nanoTime() < deadline) {
            if (runCatching { Socket().use { it.connect(InetSocketAddress(info.address, info.port), 100) } }.isFailure) {
                listenerClosed = true
                break
            }
            Thread.sleep(10)
        }
        assertTrue("Expiry must close the socket, not just erase authentication", listenerClosed)
    }

    @Test fun `mutations and account routes are unavailable while read-only books remain readable`() {
        val token = pair()
        listOf("POST", "PUT", "PATCH", "DELETE").forEach { method ->
            assertEquals(405, raw(method, "/api/share/v1/books/${document.id}", mapOf("X-PageTuner-Session" to token)).status)
        }
        assertEquals(404, request("/api/share/v1/accounts", token = token).status)
        assertEquals(0, calls.get())
        assertEquals(200, request("/api/share/v1/books/${document.id}", token = token).status)
    }

    @Test fun `large documents and adapter failures return controlled errors with no local details`() {
        val token = pair()
        document = document.copy(paragraphs = listOf(SharedParagraph("p", "a".repeat(4_000_001))))
        assertEquals(413, request("/api/share/v1/books/${document.id}", token = token).status)
        documentFailure = SharingUnavailableException("unsupported_format")
        assertEquals(422, request("/api/share/v1/books/${document.id}", token = token).status)
        documentFailure = IllegalStateException("C:/private/account-credential.json")
        val failed = request("/api/share/v1/books/${document.id}", token = token)
        assertEquals(500, failed.status)
        assertFalse(failed.body.contains("private"))
        assertFalse(failed.body.contains("credential"))
    }

    @Test fun `host binding rejects wildcard public addresses DNS and ambiguous IPv4 syntax`() {
        listOf("0.0.0.0", "8.8.8.8", "192.168.1.999", "127.1", "localhost", "127.000.0.1", "::", "::1").forEach { address ->
            assertTrue(address, runCatching { LocalSharingServer(address, library = library, webAssets = assets) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun `connection workers are bounded when readers stall`() {
        val token = pair()
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        listing = { offset, limit -> entered.countDown(); release.await(5, TimeUnit.SECONDS); SharedLibraryPage(emptyList(), 0, offset, limit) }
        val pool = Executors.newFixedThreadPool(8)
        try {
            val requests = List(8) { pool.submit<Response> { request("/api/share/v1/books", token = token) } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Socket().use { socket ->
                socket.soTimeout = 2000
                socket.connect(InetSocketAddress(info.address, info.port), 1000)
                assertEquals(-1, socket.getInputStream().read())
            }
            release.countDown()
            requests.forEach { assertEquals(200, it.get(5, TimeUnit.SECONDS).status) }
        } finally {
            release.countDown()
            pool.shutdownNow()
        }
    }

    private fun fixtureDocument(): SharedDocument {
        val paragraphs = fixtureJson.getJSONArray("paragraphs")
        val outline = fixtureJson.getJSONArray("outline")
        val anchor = fixtureJson.getJSONObject("anchor")
        return SharedDocument(
            fixtureJson.getString("id"), fixtureJson.getString("title"), fixtureJson.getString("format"), fixtureJson.getString("edition"), fixtureJson.getString("language"), fixtureJson.getString("revision"),
            List(paragraphs.length()) { paragraphs.getJSONObject(it).let { paragraph -> SharedParagraph(paragraph.getString("paragraphId"), paragraph.getString("text")) } },
            List(outline.length()) { outline.getJSONObject(it).let { item -> SharedOutlineItem(item.getString("title"), item.getString("paragraphId")) } },
            emptyList<SharedAsset>(), SharedReadingAnchor(anchor.getString("paragraphId"), anchor.getInt("characterOffset")),
        )
    }

    private fun origin() = "http://${info.address}:${info.port}"
    private fun pair(): String {
        // HttpURLConnection silently strips restricted Origin headers on some JDKs; send real wire bytes.
        val result = raw("POST", "/api/share/v1/pair", mapOf("Origin" to origin()), "{\"code\":\"${info.pairingCode}\"}")
        assertEquals(result.body, 200, result.status)
        return result.json().getString("token")
    }

    private fun request(path: String, method: String = "GET", body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap()): Response {
        val connection = URL(origin() + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 3000
        connection.readTimeout = 7000
        connection.instanceFollowRedirects = false
        if (token != null) connection.setRequestProperty("X-PageTuner-Session", token)
        if (body != null) connection.setRequestProperty("Content-Type", "application/json")
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
        }
        return try {
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(status, stream?.use {
                // Rejections can close a socket with unread request bytes. Read the framed
                // response, not an extra EOF probe after its complete Content-Length body.
                val length = connection.contentLength
                val bytes = if (length >= 0) it.readNBytes(length) else it.readBytes()
                if (length >= 0) assertEquals("Complete HTTP response body", length, bytes.size)
                bytes.toString(Charsets.UTF_8)
            }.orEmpty(), connection.headerFields.filterKeys { it != null }.mapKeys { it.key.lowercase() }.mapValues { it.value.first() })
        } finally { connection.disconnect() }
    }

    private fun raw(method: String, path: String, headers: Map<String, String> = emptyMap(), body: String? = null): Response = Socket().use { socket ->
        socket.soTimeout = 3000
        socket.connect(InetSocketAddress(info.address, info.port), 3000)
        val bytes = body?.toByteArray(Charsets.UTF_8)
        val values = linkedMapOf("Host" to "${info.address}:${info.port}", "Connection" to "close").apply {
            if (bytes != null) { put("Content-Type", "application/json"); put("Content-Length", bytes.size.toString()) }
            putAll(headers)
        }
        val request = "$method $path HTTP/1.1\r\n" + values.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        if (bytes != null) socket.getOutputStream().write(bytes)
        val input = socket.getInputStream().buffered()
        val header = StringBuilder()
        while (!header.endsWith("\r\n\r\n")) {
            val byte = input.read()
            check(byte >= 0 && header.length < 32768) { "Incomplete HTTP headers" }
            header.append(byte.toChar())
        }
        val length = header.toString().split("\r\n").firstOrNull { it.startsWith("Content-Length:", true) }
            ?.substringAfter(':')?.trim()?.toInt() ?: 0
        val payload = input.readNBytes(length)
        assertEquals("Complete raw HTTP response body", length, payload.size)
        val response = header.toString() + payload.toString(Charsets.UTF_8)
        val head = response.substringBefore("\r\n\r\n").split("\r\n")
        Response(head.first().split(' ')[1].toInt(), response.substringAfter("\r\n\r\n"), head.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() })
    }

    private data class Response(val status: Int, val body: String, val headers: Map<String, String>) { fun json() = JSONObject(body) }
}
