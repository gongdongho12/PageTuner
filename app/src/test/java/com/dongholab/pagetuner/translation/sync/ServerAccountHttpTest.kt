package com.dongholab.pagetuner.translation.sync

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerAccountHttpTest {
    @Test fun registrationUsesPublicCsrfAndItsCookieWithoutAuthorization() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("other", "private"),
            TranslationStoreHttpTransport { request ->
                requests += request
                if (request.url.endsWith("/csrf")) csrf() else TranslationStoreHttpResponse(201, body = profile())
            })
        val result = client.registerAccount("reader", "long-password", ServerAccountDraft("Reader", "fr", "ja"))
        assertEquals("reader", result.username)
        assertEquals("en", result.effectiveLocale)
        assertEquals("fr", result.locale)
        assertEquals("ja", result.targetLanguage)
        assertTrue(requests.all { "Authorization" !in it.headers })
        assertTrue(requests.first().url.endsWith("/accounts/csrf"))
        assertEquals("JSESSIONID=session", requests.last().headers["Cookie"])
        assertEquals("csrf-token", requests.last().headers["X-CSRF-TOKEN"])
        assertEquals("long-password", JSONObject(requests.last().body!!).getString("password"))
    }

    @Test fun profilePatchIncludesAllPreferencesAndAuthenticatedSession() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "long-password"),
            TranslationStoreHttpTransport { request ->
                requests += request
                if (request.url.endsWith("/csrf")) csrf() else TranslationStoreHttpResponse(200, body = profile())
            })
        val result = client.updateAccountProfile(ServerAccountDraft("Reader", "fr", "ja"))
        assertEquals("ja", result.targetLanguage)
        assertEquals("PATCH", requests.last().method)
        assertTrue(requests.all { it.headers.containsKey("Authorization") })
        val payload = JSONObject(requests.last().body!!)
        assertEquals(3, payload.length())
        assertEquals("fr", payload.getString("locale"))
        assertFalse(payload.has("password"))
    }

    @Test fun invalidAccountFieldsAndCsrfNeverSendTheMutation() = runBlocking {
        var calls = 0
        val client = HttpTranslationStore("https://reader.example", transport = TranslationStoreHttpTransport {
            calls++; TranslationStoreHttpResponse(200, body = """{"headerName":"Authorization","token":"invalid"}""")
        })
        val draft = ServerAccountDraft("Reader", "ko", "ko")
        assertTrue(runCatching { client.registerAccount("UPPER", "long-password", draft) }.exceptionOrNull() is ServerAccountInputException)
        assertTrue(runCatching { client.registerAccount("reader", "short", draft) }.exceptionOrNull() is ServerAccountInputException)
        assertTrue(runCatching { client.registerAccount("reader", "가".repeat(25), draft) }.exceptionOrNull() is ServerAccountInputException)
        assertTrue(runCatching { client.registerAccount("reader", "long-password", draft.copy(targetLanguage = "auto")) }.exceptionOrNull() is ServerAccountInputException)
        assertEquals(0, calls)
        val failure = runCatching { client.registerAccount("reader", "long-password", draft) }.exceptionOrNull()
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, (failure as TranslationStoreException).failure)
        assertEquals(1, calls)
    }

    @Test fun languageCatalogIsPublicAndCorruptPreferencesAreRejected() = runBlocking {
        val client = HttpTranslationStore("https://reader.example", transport = TranslationStoreHttpTransport { request ->
            assertFalse(request.headers.containsKey("Authorization"))
            TranslationStoreHttpResponse(200, body = """{"defaultTag":"ko","items":[{"tag":"fr","nativeName":"Français","displayName":"French","available":false,"fallbackTag":"en"}]}""")
        })
        assertEquals("fr", client.accountLanguages().items.single().tag)
        val corrupt = HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "long-password"),
            TranslationStoreHttpTransport { TranslationStoreHttpResponse(200, body = JSONObject(profile()).put("targetLanguage", "auto").toString()) })
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE,
            (runCatching { corrupt.accountProfile() }.exceptionOrNull() as TranslationStoreException).failure)
    }

    @Test fun defaultTransportPerformsRealCsrfGetAndPatchOverLoopbackHttp() = runBlocking {
        val listener = ServerSocket(0, 2, InetAddress.getByName("127.0.0.1"))
        listener.soTimeout = 5_000
        val worker = Executors.newSingleThreadExecutor()
        try {
            val requests = worker.submit<List<Pair<String, String>>> {
                (0..1).map { index -> listener.accept().use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.getInputStream()
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
                    val json = if (index == 0) """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""" else profile()
                    val bytes = json.toByteArray(Charsets.UTF_8)
                    val cookie = if (index == 0) "Set-Cookie: JSESSIONID=session; Path=/; HttpOnly\r\n" else ""
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\n${cookie}Connection: close\r\n\r\n"
                    socket.getOutputStream().apply { write(response.toByteArray(Charsets.US_ASCII)); write(bytes); flush() }
                    if (index == 1) {
                        check(headers["cookie"] == "JSESSIONID=session")
                        check(headers["x-csrf-token"] == "csrf-token")
                        check(headers["authorization"]?.startsWith("Basic ") == true)
                    }
                    first to body.toString(Charsets.UTF_8)
                } }
            }
            val client = HttpTranslationStore("http://127.0.0.1:${listener.localPort}", TranslationStoreBasicAuth("reader", "long-password"))
            assertEquals("fr", client.updateAccountProfile(ServerAccountDraft("Reader", "fr", "ja")).locale)
            val captured = requests.get(5, TimeUnit.SECONDS)
            assertEquals("GET /api/v1/accounts/csrf HTTP/1.1", captured[0].first)
            assertEquals("PATCH /api/v1/accounts/me HTTP/1.1", captured[1].first)
            assertEquals("ja", JSONObject(captured[1].second).getString("targetLanguage"))
        } finally { listener.close(); worker.shutdownNow() }
    }

    private fun csrf() = TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
        """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
    private fun profile() = """{"accountId":"11111111-1111-4111-8111-111111111111","username":"reader","displayName":"Reader","locale":"fr","targetLanguage":"ja","effectiveLocale":"en"}"""
}
