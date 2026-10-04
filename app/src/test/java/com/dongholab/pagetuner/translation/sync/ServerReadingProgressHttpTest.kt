package com.dongholab.pagetuner.translation.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerReadingProgressHttpTest {
    private val id = "11111111-1111-4111-8111-111111111111"
    private val mutation = ServerReadingMutation(4, "22222222-2222-4222-8222-222222222222", ServerReadingAnchor("paragraph-one", 7))
    private fun payload() = JSONObject().put("kind", "ORIGINAL").put("recordId", id).put("version", 5)
        .put("anchor", JSONObject().put("paragraphId", "paragraph-one").put("characterOffset", 7)).put("updatedAt", "2026-09-16T00:00:00Z")

    @Test fun writesUseSameAccountCsrfSessionAndCompleteCasBody() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = client { request -> requests += request; TranslationStoreHttpResponse(200, body = payload().toString()) }
        val value = client.saveReadingProgress("ORIGINAL", id, mutation)
        assertEquals(5L, value.version)
        val request = requests.single()
        assertEquals("PUT", request.method)
        assertTrue(request.url.endsWith("/reading-progress/ORIGINAL/$id"))
        assertEquals("Basic cmVhZGVyOnBhc3N3b3Jk", request.headers["Authorization"])
        assertEquals("csrf", request.headers["X-CSRF-TOKEN"])
        assertEquals("JSESSIONID=session", request.headers["Cookie"])
        assertEquals(mutation, ServerReadingProgressJson.mutation(JSONObject(request.body!!)))
    }

    @Test fun conflictRetainsCurrentPositionButRejectsForgedCurrentIdentity() = runBlocking {
        val error = assertThrows(ServerReadingProgressConflict::class.java) { runBlocking {
            client { TranslationStoreHttpResponse(409, body = JSONObject().put("code", "READING_PROGRESS_CONFLICT").put("current", payload()).toString()) }
                .saveReadingProgress("ORIGINAL", id, mutation)
        } }
        assertEquals(mutation.anchor, error.current.anchor)
        val invalid = assertThrows(TranslationStoreException::class.java) { runBlocking {
            client { TranslationStoreHttpResponse(409, body = JSONObject().put("code", "READING_PROGRESS_CONFLICT").put("current", payload().put("recordId", mutation.mutationId)).toString()) }
                .saveReadingProgress("ORIGINAL", id, mutation)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, invalid.failure)
    }

    @Test fun rejectsMismatchedAcknowledgementAndInvalidMissingState() {
        listOf(payload().put("version", 4), payload().put("version", 9007199254740992L), payload().put("version", 5.5),
            payload().put("anchor", JSONObject().put("paragraphId", "paragraph-other").put("characterOffset", 7)),
            payload().put("updatedAt", JSONObject.NULL), payload().put("kind", "TRANSLATION")).forEach { json ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                client { TranslationStoreHttpResponse(200, body = json.toString()) }.saveReadingProgress("ORIGINAL", id, mutation)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        assertThrows(IllegalArgumentException::class.java) { ServerReadingProgressJson.decode(payload().put("version", 0), "ORIGINAL", id) }
    }

    @Test fun invalidTargetDoesNotSendCredentialsOrRequests() {
        var calls = 0
        val client = client { calls++; TranslationStoreHttpResponse(200, body = payload().toString()) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.readingProgress("ORIGINAL/../accounts", id) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.saveReadingProgress("ORIGINAL", id, mutation.copy(expectedVersion = MaxReadingVersion)) } }
        assertEquals(0, calls)
    }

    @Test fun rateLimitUsesBoundedRetryAfterInsteadOfGenericInvalidRequest() {
        listOf("60" to 60L, "120" to 120L, "invalid" to 60L, "999999999999999999" to 60L).forEach { (header, expected) ->
            val error = assertThrows(ServerReadingProgressRateLimited::class.java) { runBlocking {
                client { TranslationStoreHttpResponse(429, mapOf("retry-after" to listOf(header)), """{"code":"READING_PROGRESS_LIMIT"}""") }
                    .saveReadingProgress("ORIGINAL", id, mutation)
            } }
            assertEquals(expected, error.retryAfterSeconds)
        }
    }

    private fun client(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "password"), TranslationStoreHttpTransport { request ->
            if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
                """{"headerName":"X-CSRF-TOKEN","token":"csrf"}""") else handler(request)
        })
}
