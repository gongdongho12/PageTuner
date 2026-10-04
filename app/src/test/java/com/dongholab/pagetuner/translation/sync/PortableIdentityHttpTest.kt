package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.backup.exchange.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PortableIdentityHttpTest {
    private val identity = DocumentIdentities.original(NotesFixture.document().source.sourceContent!!)
    private fun response() = JSONObject().put("kind", identity.kind.name).put("recordId", NotesFixture.recordId)
        .put("verified", true).put("identity", DocumentIdentityJson.encode(identity))

    @Test fun explicitBoundedVerificationUsesAuthenticatedCsrfAndExactProofOnly() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = NotesFixture.connection { requests += it; NotesFixture.response(response()) }.client
        assertEquals(identity, client.verifyPortableIdentity(NotesFixture.recordId, identity))
        val request = requests.single()
        assertEquals("https://reader.example/api/v1/library-identity/verify", request.url)
        assertEquals("POST", request.method)
        assertEquals("csrf", request.headers["X-CSRF-TOKEN"])
        assertTrue(request.headers["Authorization"]!!.startsWith("Basic "))
        val body = JSONObject(request.body!!)
        assertEquals(setOf("kind", "recordId", "identity"), body.keys().asSequence().toSet())
        assertEquals(identity, DocumentIdentityJson.decode(body.getJSONObject("identity")))
    }

    @Test fun responseMustConfirmExactRecordKindIdentityAndBooleanWithinLimit() {
        listOf(response().put("verified", "true"), response().put("verified", false), response().put("kind", "TRANSLATION"),
            response().put("recordId", NotesFixture.otherId), response().put("unexpected", true),
            response().put("identity", DocumentIdentityJson.encode(identity.copy(bookId = "another-book")))).forEach { json ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { NotesFixture.response(json) }.client.verifyPortableIdentity(NotesFixture.recordId, identity)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(200, body = " ".repeat(131_073)) }.client.verifyPortableIdentity(NotesFixture.recordId, identity)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
    }

    @Test fun mismatchUnavailableAndMissingRemainDistinctFromSuccess() {
        listOf(Triple(409, "MISMATCH", PortableIdentityFailure.Mismatch), Triple(409, "UNAVAILABLE", PortableIdentityFailure.Unavailable),
            Triple(404, "NOT_FOUND", PortableIdentityFailure.NotFound)).forEach { (status, code, expected) ->
            val error = assertThrows(PortableIdentityVerificationException::class.java) { runBlocking {
                NotesFixture.connection { TranslationStoreHttpResponse(status, body = "{\"code\":\"LIBRARY_IDENTITY_$code\"}") }
                    .client.verifyPortableIdentity(NotesFixture.recordId, identity)
            } }
            assertEquals(expected, error.reason)
        }
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            NotesFixture.connection { requests++; error("must not send") }.client.verifyPortableIdentity("invalid", identity)
        } }
        assertEquals(0, requests)
    }
}
