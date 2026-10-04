package com.dongholab.pagetuner.translation.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerPasswordChangeHttpTest {
    @Test fun passwordChangeUsesAuthenticatedCsrfAndAcceptsOnlyEmpty204WithoutFollowup() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = client { request ->
            requests += request
            if (request.url.endsWith("/csrf")) csrf() else TranslationStoreHttpResponse(204)
        }
        client.changeAccountPassword(" old ", " new-password ")
        assertEquals(2, requests.size)
        assertEquals("https://reader.example/api/v1/accounts/csrf", requests[0].url)
        val request = requests[1]
        assertEquals("https://reader.example/api/v1/accounts/me/password", request.url)
        assertEquals("POST", request.method)
        assertTrue(requests.all { it.headers["Authorization"]?.startsWith("Basic ") == true })
        assertEquals("JSESSIONID=session", request.headers["Cookie"])
        assertEquals("csrf-token", request.headers["X-CSRF-TOKEN"])
        val payload = JSONObject(request.body!!)
        assertEquals(2, payload.length())
        assertEquals(" old ", payload.getString("currentPassword"))
        assertEquals(" new-password ", payload.getString("newPassword"))
    }

    @Test fun malformedSuccessIsNotReportedAsConfirmedChange() = runBlocking {
        listOf(TranslationStoreHttpResponse(200), TranslationStoreHttpResponse(204, body = "{}")).forEach { response ->
            val client = client { if (it.url.endsWith("/csrf")) csrf() else response }
            val error = runCatching { client.changeAccountPassword("old-password", "new-password") }.exceptionOrNull()
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, (error as TranslationStoreException).failure)
        }
    }

    @Test fun newPasswordPolicyAndCurrentPasswordValidationHappenBeforeNetwork() = runBlocking {
        var calls = 0
        val client = client { calls++; error("Unexpected request") }
        listOf("", "short", "가".repeat(25), "long-password\n").forEach { password ->
            assertTrue(runCatching { client.changeAccountPassword("old", password) }.exceptionOrNull() is ServerAccountInputException)
        }
        listOf("", "a".repeat(73), "old\n").forEach { current ->
            assertTrue(runCatching { client.changeAccountPassword(current, "new-password") }.exceptionOrNull() is ServerAccountInputException)
        }
        assertEquals(ServerAccountInputField.PasswordUnchanged,
            (runCatching { client.changeAccountPassword("new-password", "new-password") }.exceptionOrNull() as ServerAccountInputException).field)
        assertEquals(0, calls)
    }

    @Test fun onlyMatchingKnownPasswordCodesAreRetainedAndServerMessagesAreDiscarded() = runBlocking {
        ServerPasswordChangeFailure.entries.forEach { failure ->
            val client = client { if (it.url.endsWith("/csrf")) csrf() else
                TranslationStoreHttpResponse(failure.status, body = """{"code":"${failure.name}","message":"new-password secret"}""") }
            val error = runCatching { client.changeAccountPassword("old", "new-password") }.exceptionOrNull()
            assertEquals(failure, (error as ServerPasswordChangeException).failure)
            assertFalse(error.toString().contains("new-password"))
            assertFalse(error.toString().contains("secret"))
        }
        listOf(
            TranslationStoreHttpResponse(401, body = """{"code":"CURRENT_PASSWORD_INCORRECT"}"""),
            TranslationStoreHttpResponse(400, body = """{"code":"new-password"}"""),
            TranslationStoreHttpResponse(400, body = "x".repeat(4_097)),
        ).forEach { response ->
            val client = client { if (it.url.endsWith("/csrf")) csrf() else response }
            val error = runCatching { client.changeAccountPassword("old", "new-password") }.exceptionOrNull()
            assertTrue(error is TranslationStoreException)
            assertFalse(error.toString().contains("new-password"))
        }
    }

    private fun client(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", " old "), TranslationStoreHttpTransport(handler))

    private fun csrf() = TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
        """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
}
