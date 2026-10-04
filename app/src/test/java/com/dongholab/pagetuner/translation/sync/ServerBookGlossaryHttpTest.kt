package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerBookGlossaryHttpTest {
    private val identity = BookGlossarySyncIdentity("source:+%/原", "書".repeat(2000), "zh-hant")
    private val value = BookGlossaryValue(listOf(BookGlossaryEntry("entry", " Original ", " Translation ", " Alias ")))
    private val saved = ServerBookGlossary(identity, 1, value, NotesFixture.time)

    @Test fun sharedFixturesPreserveDuplicateSourceTermsIdsAndAllOrderedMetadata() {
        val folder = sequenceOf(java.io.File("../contracts/fixtures/book-glossary-v1"), java.io.File("contracts/fixtures/book-glossary-v1"))
            .first { it.isDirectory }
        val raw = JSONObject(java.io.File(folder, "request.json").readText())
        val identity = ServerBookGlossaryJson.identity(raw)
        val request = ServerBookGlossaryJson.mutation(raw, identity)
        val view = ServerBookGlossaryJson.view(JSONObject(java.io.File(folder, "view.json").readText()), identity)
        assertEquals(request.value, view.value)
        assertEquals(listOf("original-entry-2", "disabled-place-1", "term-3"), request.value.entries!!.map { it.id })
        assertEquals(" Alice ", request.value.entries.first().sourceTerm)
        assertEquals(" 아리 ", request.value.entries.first().displayTerm)
        assertEquals(listOf("River", "River"), request.value.entries.drop(1).map { it.sourceTerm })
        assertFalse(request.value.entries[1].enabled)
    }

    @Test fun longOriginalIdentityUsesBoundedReadOnlyPostAndStrictAuthenticatedMutation() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = NotesFixture.connection { requests += it; TranslationStoreHttpResponse(200, body = ServerBookGlossaryJson.encode(saved).toString()) }.client
        assertEquals(saved, client.bookGlossary(identity))
        val query = requests.single()
        assertTrue(query.url.endsWith("/api/v1/book-glossary/query")); assertEquals("POST", query.method)
        assertEquals(identity, ServerBookGlossaryJson.identity(JSONObject(query.body!!)))
        assertEquals("csrf", query.headers["X-CSRF-TOKEN"])
        assertTrue(query.headers["Authorization"]!!.startsWith("Basic "))
        val mutation = BookGlossaryMutation(0, NotesFixture.noteId, value)
        assertEquals(saved, client.saveBookGlossary(identity, mutation))
        assertEquals(mutation, ServerBookGlossaryJson.mutation(JSONObject(requests.last().body!!), identity))
    }

    @Test fun invalidAcknowledgmentAndConflictCannotReplaceAnotherIdentity() {
        val mutation = BookGlossaryMutation(0, NotesFixture.noteId, value)
        listOf(ServerBookGlossaryJson.encode(saved).put("bookId", "different"),
            ServerBookGlossaryJson.encode(saved).put("version", "1"),
            ServerBookGlossaryJson.encode(saved).put("extra", true)).forEach { json ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { TranslationStoreHttpResponse(200, body = json.toString()) }.client.saveBookGlossary(identity, mutation)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        val conflict = JSONObject().put("code", "BOOK_GLOSSARY_CONFLICT")
            .put("current", ServerBookGlossaryJson.encode(saved).put("targetLanguage", "ko"))
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(409, body = conflict.toString()) }.client.saveBookGlossary(identity, mutation)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
    }

    @Test fun responseBudgetRetryAfterAndLocalInvalidInputAreBounded() {
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(200, body = " ".repeat(2_097_153)) }.client.bookGlossary(identity)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        val limited = assertThrows(BookGlossaryRateLimited::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("12"))) }.client.bookGlossary(identity)
        } }
        assertEquals(12L, limited.retryAfterSeconds)
        var requests = 0
        assertThrows(IllegalArgumentException::class.java) { runBlocking {
            NotesFixture.connection { requests++; error("unexpected request") }.client.bookGlossary(identity.copy(targetLanguage = "auto"))
        } }
        assertEquals(0, requests)
    }
}
