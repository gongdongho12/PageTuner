package com.dongholab.pagetuner.translation.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import com.dongholab.pagetuner.translation.sync.NotesFixture.recordId
import com.dongholab.pagetuner.translation.sync.NotesFixture.noteId
import com.dongholab.pagetuner.translation.sync.NotesFixture.otherId
import com.dongholab.pagetuner.translation.sync.NotesFixture.time
import com.dongholab.pagetuner.translation.sync.NotesFixture.document
import com.dongholab.pagetuner.translation.sync.NotesFixture.content
import com.dongholab.pagetuner.translation.sync.NotesFixture.item
import com.dongholab.pagetuner.translation.sync.NotesFixture.page
import com.dongholab.pagetuner.translation.sync.NotesFixture.response
import com.dongholab.pagetuner.translation.sync.NotesFixture.conflict
import com.dongholab.pagetuner.translation.sync.NotesFixture.requestMutation
import com.dongholab.pagetuner.translation.sync.NotesFixture.ack
import com.dongholab.pagetuner.translation.sync.NotesFixture.connection
import com.dongholab.pagetuner.translation.sync.NotesFixture.Store

class ServerReadingNotesHttpTest {
    private val mutation = ServerReadingNoteMutation(0, otherId, false, content().copy(createdAt = "2026-09-16T00:00:00.000Z"))
    @Test fun authenticatedCasRequestOmitsExcerptAndAcceptsEquivalentTimestamp() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = connection { requests += it; ack(it) }.client
        val result = client.saveReadingNote("ORIGINAL", recordId, noteId, mutation)
        assertEquals(1L, result.version)
        val request = requests.single()
        assertEquals("Basic cmVhZGVyOnBhc3N3b3Jk", request.headers["Authorization"])
        assertEquals("csrf", request.headers["X-CSRF-TOKEN"])
        assertEquals("JSESSIONID=session", request.headers["Cookie"])
        assertEquals(setOf("expectedVersion", "mutationId", "deleted", "note"), JSONObject(request.body!!).keys().asSequence().toSet())
        assertFalse(JSONObject(request.body).getJSONObject("note").has("excerpt"))
        assertEquals(otherId, requestMutation(request).mutationId)
    }
    @Test fun feedPreservesRepeatedIdsAndFixedWatermark() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val page = connection { requests += it; response(page(listOf(item(2, 5), item(3, 6)), 6, 8, true)) }.client
            .readingNotes("ORIGINAL", recordId, 4, 50, 8)
        assertEquals(listOf(2L, 3L), page.items.map { it.version })
        assertTrue(requests.single().url.endsWith("?afterRevision=4&limit=50&untilRevision=8"))
    }
    @Test fun forgedConflictIdentityAndMissingFieldsAreInvalidResponses() {
        listOf(conflict(item(id = otherId)), TranslationStoreHttpResponse(409, body = "{}"),
            response(ServerReadingNotesJson.encode(item()).put("version", 2))).forEach { result ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                connection { result }.client.saveReadingNote("ORIGINAL", recordId, noteId, mutation)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
    }
    @Test fun zeroConflictAndTombstoneResponsesPreserveDeletion() {
        val conflict = assertThrows(ServerReadingNoteConflict::class.java) { runBlocking {
            connection { conflict(item(0, 0, content = null)) }.client.saveReadingNote("ORIGINAL", recordId, noteId, mutation)
        } }
        assertEquals(0L, conflict.current.version); assertTrue(conflict.current.deleted)
        runBlocking {
            val result = connection { ack(it) }.client.saveReadingNote("ORIGINAL", recordId, noteId, mutation.copy(deleted = true, note = null))
            assertTrue(result.deleted); assertNull(result.note)
        }
    }
    @Test fun invalidWatermarkOrderCursorAndIdentityNeverReachJournal() {
        listOf(page(listOf(item(1, 2), item(2, 1))), page(listOf(item()), 0, 2, true), page().put("kind", "TRANSLATION"),
            page().put("watermark", "0"), page().put("nextAfterRevision", 0.5), page(listOf(item())).put("watermark", 0)).forEach { payload ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                connection { response(payload) }.client.readingNotes("ORIGINAL", recordId)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
    }
    @Test fun unsafeTargetsOrNonUuidLegacyIdsNeverSendCredentials() {
        var requests = 0
        val client = connection { requests++; response(page()) }.client
        listOf("bookmark-0-123", "../../accounts", "2222").forEach { legacy ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.saveReadingNote("ORIGINAL", recordId, legacy, mutation) } }
        }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.readingNotes("ORIGINAL/../accounts", recordId) } }
        assertEquals(0, requests)
    }
    @Test fun rateLimitRetainsBoundedRetryAfter() {
        listOf("120" to 120L, "99999999999999999999" to 60L).forEach { (header, expected) ->
            val error = assertThrows(ServerReadingNotesRateLimited::class.java) { runBlocking {
                connection { TranslationStoreHttpResponse(429, mapOf("retry-after" to listOf(header))) }.client.saveReadingNote("ORIGINAL", recordId, noteId, mutation)
            } }
            assertEquals(expected, error.retryAfterSeconds)
        }
    }
}
