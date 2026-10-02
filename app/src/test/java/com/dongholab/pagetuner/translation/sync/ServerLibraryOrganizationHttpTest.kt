package com.dongholab.pagetuner.translation.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerLibraryOrganizationHttpTest {
    private val value = LibraryOrganization("Library", listOf("Case", "case", "comma,tag"), true)
    private val mutation = LibraryOrganizationMutation(0, NotesFixture.noteId, value)
    private fun view(version: Long = 1) = ServerLibraryOrganization("TRANSLATION", NotesFixture.noteId, version,
        value.takeIf { version > 0 }, NotesFixture.time.takeIf { version > 0 })
    private fun response(value: JSONObject, status: Int = 200) = TranslationStoreHttpResponse(status, body = value.toString())
    @Test fun endpointUsesExactKindIdentityAuthAndCsrfAndVerifiesAck() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = NotesFixture.connection { requests += it; response(ServerLibraryOrganizationJson.encode(view())) }.client
        assertEquals(value, client.libraryOrganization("TRANSLATION", NotesFixture.noteId).organization)
        assertEquals(1L, client.saveLibraryOrganization("TRANSLATION", NotesFixture.noteId, mutation).version)
        assertEquals(listOf("GET", "PUT"), requests.map { it.method })
        assertTrue(requests.all { it.url.endsWith("/api/v1/library-organization/TRANSLATION/${NotesFixture.noteId}") })
        assertTrue(requests.last().headers["Authorization"]!!.startsWith("Basic "))
        assertEquals("csrf", requests.last().headers["X-CSRF-TOKEN"])
        assertEquals("JSESSIONID=session", requests.last().headers["Cookie"])
        assertEquals(setOf("expectedVersion", "mutationId", "organization"), JSONObject(requests.last().body!!).keys().asSequence().toSet())
    }

    @Test fun invalidVersionsIdentityNullStateAndTimestampCannotReplaceClassification() {
        val invalid = listOf(
            ServerLibraryOrganizationJson.encode(view()).put("kind", "ORIGINAL"),
            ServerLibraryOrganizationJson.encode(view()).put("version", "1"),
            ServerLibraryOrganizationJson.encode(view()).put("version", 1.5),
            ServerLibraryOrganizationJson.encode(view()).put("organization", JSONObject.NULL),
            ServerLibraryOrganizationJson.encode(view()).put("updatedAt", "2026-02-30T00:00:00Z"),
            ServerLibraryOrganizationJson.encode(view(0)).put("organization", ServerLibraryOrganizationJson.encode(value)),
            ServerLibraryOrganizationJson.encode(view()).put("extra", true),
            ServerLibraryOrganizationJson.encode(view()).put("organization", ServerLibraryOrganizationJson.encode(value).put("favorite", "true")),
        )
        invalid.forEach { payload ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { response(payload) }.client.libraryOrganization("TRANSLATION", NotesFixture.noteId)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { response(ServerLibraryOrganizationJson.encode(view(2))) }.client.saveLibraryOrganization("TRANSLATION", NotesFixture.noteId, mutation)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
    }

    @Test fun canonicalWhitespaceUnicodeAndTagIdentityAreStrictWithoutSilentNormalization() {
        assertEquals("Book", trimLibraryOrganizationText("\ufeff\u00a0 Book\u2028\u3000"))
        assertEquals("\u0085Book", trimLibraryOrganizationText("\u0085Book"))
        listOf(value.copy(folder = " leading"), value.copy(folder = "trailing\ufeff"), value.copy(folder = "\uD800"),
            value.copy(folder = "A\u0000B"), value.copy(folder = "A\u0085B"), value.copy(folder = "x".repeat(201)),
            value.copy(tags = listOf("same", "same")), value.copy(tags = listOf("")), value.copy(tags = listOf(" ")),
            value.copy(tags = (0..32).map { it.toString() }), value.copy(tags = listOf("x".repeat(61)))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { ServerLibraryOrganizationJson.validate(invalid) }
        }
        val valid = value.copy(folder = "한글😀", tags = listOf("Case", "case", "comma,tag", "😀", "\u200b"))
        assertEquals(valid, ServerLibraryOrganizationJson.organization(ServerLibraryOrganizationJson.encode(valid)))
    }

    @Test fun localInvalidPayloadNeverRequestsAndResponseLimitIsEnforced() {
        var calls = 0
        val client = NotesFixture.connection { calls++; response(ServerLibraryOrganizationJson.encode(view())) }.client
        listOf(mutation.copy(expectedVersion = MaxReadingVersion), mutation.copy(mutationId = "legacy"),
            mutation.copy(organization = value.copy(tags = listOf("dup", "dup")))).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.saveLibraryOrganization("TRANSLATION", NotesFixture.noteId, invalid) } }
        }
        assertEquals(0, calls)
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(200, body = " ".repeat(8193)) }.client.libraryOrganization("TRANSLATION", NotesFixture.noteId)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
    }

    @Test fun conflictAndExhaustedAreDistinctAndCooldownCannotOverflow() {
        val error = assertThrows(LibraryOrganizationConflict::class.java) { runBlocking {
            NotesFixture.connection { response(JSONObject().put("code", "LIBRARY_ORGANIZATION_CONFLICT")
                .put("current", ServerLibraryOrganizationJson.encode(view(2))), 409) }.client.saveLibraryOrganization("TRANSLATION", NotesFixture.noteId, mutation)
        } }
        assertEquals(2L, error.current.version)
        val exhausted = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { response(JSONObject().put("code", "LIBRARY_ORGANIZATION_EXHAUSTED"), 409) }
                .client.saveLibraryOrganization("TRANSLATION", NotesFixture.noteId, mutation)
        } }
        assertEquals(TranslationStoreFailure.CONFLICT, exhausted.failure)
        listOf("120" to 120L, "0" to 60L, "99999999999999999" to 60L).forEach { (header, expected) ->
            val rate = assertThrows(LibraryOrganizationRateLimited::class.java) { runBlocking {
                NotesFixture.connection { TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf(header))) }.client.libraryOrganization("TRANSLATION", NotesFixture.noteId)
            } }
            assertEquals(expected, rate.retryAfterSeconds)
        }
    }

    @Test fun journalPreservesPendingQueuedConflictAndRejectsDifferentAccountOrIdentity() {
        val target = ServerReadingTarget(serverReadingAccountKey("https://reader.example", "alice"), "TRANSLATION", NotesFixture.noteId)
        val source = DeviceLibraryOrganization(view(1), mutation, value.copy(folder = "Queued"), view(4), 123000)
        val json = encodeLibraryOrganizationJournal(target, source)
        assertEquals(source, decodeLibraryOrganizationJournal(JSONObject(json.toString()), target))
        assertFalse(json.toString().contains("reader.example")); assertFalse(json.toString().contains("password"))
        assertThrows(IllegalArgumentException::class.java) { decodeLibraryOrganizationJournal(json, target.copy(accountKey = "0".repeat(64))) }
        assertThrows(IllegalArgumentException::class.java) { decodeLibraryOrganizationJournal(json, target.copy(kind = "ORIGINAL")) }
        assertThrows(IllegalArgumentException::class.java) { decodeLibraryOrganizationJournal(JSONObject(json.toString()).put("pending", JSONObject.NULL), target) }
    }
}
