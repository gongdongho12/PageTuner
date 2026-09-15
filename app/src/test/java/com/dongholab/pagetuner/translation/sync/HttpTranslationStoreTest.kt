package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HttpTranslationStoreTest {
    @Test
    fun sharedContractFixtureRoundTripsThroughCsrfAuthenticatedSaveAndGet() = runTest {
        val stored = fixture("stored-response.json")
        val expected = TranslationStoreJson.decode(JSONObject(stored))
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val store = store { request ->
            requests += request
            if (request.url.endsWith("/csrf")) csrf() else TranslationStoreHttpResponse(200, body = stored)
        }
        val result = store.save(expected.artifact)
        assertTrue(result.created)
        assertEquals(expected, result.translation)
        assertEquals(expected, store.get(expected.recordId))
        assertEquals(listOf("GET", "POST", "GET"), requests.map { it.method })
        assertEquals("https://reader.example/api/v1/translations", requests[1].url)
        assertEquals("Basic dXNlcjpwYXNz", requests[1].headers["Authorization"])
        assertEquals("csrf-token", requests[1].headers["X-CSRF-TOKEN"])
        assertEquals("JSESSIONID=session-1", requests[1].headers["Cookie"])
        assertEquals(normalizeJson(JSONObject(fixture("save-request.json"))), normalizeJson(JSONObject(requireNotNull(requests[1].body))))
        assertFalse(requests[2].headers.containsKey("Cookie"))
    }

    @Test
    fun snapshotsMutableParagraphsBeforeSuspendingAndRunsAdapterWorkOffTheCallingThread() = runTest {
        val response = fixture("stored-response.json")
        val original = TranslationStoreJson.decode(JSONObject(response)).artifact
        val mutableParagraphs = original.paragraphs.toMutableList()
        val supplied = original.copy(paragraphs = mutableParagraphs)
        val caller = Thread.currentThread()
        val client = store { request ->
            assertFalse("Adapter I/O must leave the caller thread", Thread.currentThread() === caller)
            if (request.url.endsWith("/csrf")) {
                mutableParagraphs.clear()
                csrf()
            } else {
                assertEquals(normalizeJson(TranslationStoreJson.encode(original)), normalizeJson(JSONObject(requireNotNull(request.body))))
                TranslationStoreHttpResponse(201, body = response)
            }
        }
        assertEquals(original, client.save(supplied).translation.artifact)
    }

    @Test
    fun rejectsCorruptHashesMetadataAndMalformedBodies() = runTest {
        val good = JSONObject(fixture("stored-response.json"))
        val recordId = good.getString("recordId")
        for (field in listOf("artifactId", "revision", "payloadHash", "sourceRevision", "targetLanguage", "translationProviderId", "modelId", "promptRevision", "glossaryRevision")) {
            val invalid = JSONObject(good.toString()).put(field, "changed")
            expectFailure(TranslationStoreFailure.INVALID_RESPONSE) {
                store { TranslationStoreHttpResponse(200, body = invalid.toString()) }.get(recordId)
            }
        }
        for (body in listOf("not-json", good.toString().replace("\"modelId\":\"model-v1\"", "\"modelId\":null"))) {
            expectFailure(TranslationStoreFailure.INVALID_RESPONSE) {
                store { TranslationStoreHttpResponse(200, body = body) }.get(recordId)
            }
        }
    }

    @Test
    fun rejectsRecordIdMismatchAndAValidButDifferentSavedArtifact() = runTest {
        val json = JSONObject(fixture("stored-response.json"))
        val expected = TranslationStoreJson.decode(json)
        expectFailure(TranslationStoreFailure.INVALID_RESPONSE) {
            store { TranslationStoreHttpResponse(200, body = json.toString()) }.get("different-record")
        }
        expectFailure(TranslationStoreFailure.INVALID_RESPONSE) {
            store { if (it.url.endsWith("/csrf")) csrf() else TranslationStoreHttpResponse(201, body = json.toString()) }
                .save(expected.artifact.copy(targetLanguage = "ja"))
        }
    }

    @Test
    fun exposesAuthConflictMissingServerAndRedirectFailuresWithoutFollowingRedirects() = runTest {
        for ((status, expected) in mapOf(401 to TranslationStoreFailure.AUTHENTICATION, 403 to TranslationStoreFailure.FORBIDDEN,
            404 to TranslationStoreFailure.NOT_FOUND, 409 to TranslationStoreFailure.CONFLICT, 400 to TranslationStoreFailure.INVALID_REQUEST,
            500 to TranslationStoreFailure.SERVER, 302 to TranslationStoreFailure.REDIRECT)) {
            var requests = 0
            expectFailure(expected, status) {
                store {
                    requests++
                    TranslationStoreHttpResponse(status, mapOf("Location" to listOf("https://attacker.example")), "secret details")
                }.get("record-1")
            }
            assertEquals(1, requests)
        }
    }

    @Test
    fun csrfFailureAndUnusableCookiesNeverIssuePost() = runTest {
        val artifact = TranslationStoreJson.decode(JSONObject(fixture("stored-response.json"))).artifact
        for (response in listOf(
            csrf().copy(headers = emptyMap()),
            csrf().copy(headers = mapOf("Set-Cookie" to listOf("JSESSIONID=s; Domain=attacker.example; Path=/"))),
            csrf().copy(body = "{\"headerName\":\"Authorization\",\"token\":\"override\"}"),
        )) {
            var requests = 0
            expectFailure(TranslationStoreFailure.INVALID_RESPONSE) {
                store { requests++; response }.save(artifact)
            }
            assertEquals(1, requests)
        }
    }

    @Test
    fun validatesOriginAndRecordIdBeforeSendingCredentials() = runTest {
        for (url in listOf("http://reader.example", "https://user:pass@reader.example", "https://reader.example/api", "https://reader.example?q=token", "https://reader.example/#fragment", "file:///tmp/key")) {
            try {
                HttpTranslationStore(url, TranslationStoreBasicAuth("user", "pass"))
                error("Origin should have been rejected: $url")
            } catch (_: IllegalArgumentException) { }
        }
        for (url in listOf("http://localhost:8080", "http://127.0.0.1:8080", "http://[::1]:8080", "https://reader.example")) {
            HttpTranslationStore(url, TranslationStoreBasicAuth("user", "pass"))
        }
        HttpTranslationStore("http://10.0.2.2:8080", TranslationStoreBasicAuth("user", "pass"), allowInsecureDevelopmentHttp = true)
        val client = store { error("Invalid IDs must not perform I/O") }
        for (recordId in listOf("../csrf", "record?id=secret", "", "record/path")) {
            try { client.get(recordId); error("Invalid ID accepted") } catch (_: IllegalArgumentException) { }
        }
    }

    @Test
    fun preservesCoroutineCancellationAndClassifiesTimeoutsAndNetworkFailure() = runTest {
        val cancellation = CancellationException("cancelled")
        try {
            store { throw cancellation }.get("record-1")
            error("Cancellation was swallowed")
        } catch (caught: CancellationException) {
            assertEquals(cancellation.message, caught.message)
            assertEquals(cancellation.javaClass, caught.javaClass)
        }
        expectFailure(TranslationStoreFailure.TIMEOUT) { store { throw SocketTimeoutException() }.get("record-1") }
        expectFailure(TranslationStoreFailure.NETWORK) { store { throw IOException("credential-sensitive diagnostic") }.get("record-1") }
    }

    private fun fixture(name: String): String = requireNotNull(javaClass.classLoader?.getResourceAsStream("translation-v1/$name"))
        .bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun normalizeJson(value: Any?): Any? = when (value) {
        is JSONObject -> value.keys().asSequence().associateWith { normalizeJson(value.get(it)) }
        is JSONArray -> List(value.length()) { normalizeJson(value.get(it)) }
        JSONObject.NULL -> null
        else -> value
    }

    private fun store(transport: TranslationStoreHttpTransport): HttpTranslationStore =
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("user", "pass"), transport)

    private fun csrf() = TranslationStoreHttpResponse(
        200, mapOf("Set-Cookie" to listOf("JSESSIONID=session-1; Path=/; HttpOnly; Secure")),
        "{\"headerName\":\"X-CSRF-TOKEN\",\"token\":\"csrf-token\"}",
    )

    private suspend fun expectFailure(failure: TranslationStoreFailure, status: Int? = null, block: suspend () -> Unit) {
        try { block(); error("Expected $failure") } catch (error: TranslationStoreException) {
            assertEquals(failure, error.failure)
            assertEquals(status, error.status)
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }
}
