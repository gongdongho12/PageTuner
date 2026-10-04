package com.dongholab.pagetuner.translation.sync

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SourceBookFavoritesHttpTest {
    private fun fixture(name: String): JSONObject {
        val root = sequenceOf(File("../contracts/fixtures/source-book-favorites-v1"), File("contracts/fixtures/source-book-favorites-v1"))
            .first { it.isDirectory }
        return JSONObject(File(root, "$name.json").readText())
    }
    @Test fun commonFixturesRoundTripAndPutCarriesExactIdsBasicAuthAndCsrf() = runBlocking {
        val mutation = SourceFavoritesJson.mutation(fixture("request")); val expected = SourceFavoritesJson.item(fixture("item"))
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = NotesFixture.connection { requests += it; TranslationStoreHttpResponse(200, body = fixture(if (it.method == "PUT") "item" else "changes").toString()) }.client
        assertEquals(expected, client.saveSourceBookFavorite(mutation))
        assertEquals(listOf(expected), client.sourceBookFavorites(0, 50, null).items)
        assertEquals(mutation, SourceFavoritesJson.mutation(JSONObject(requests.first().body!!)))
        assertTrue(requests.first().headers["Authorization"]!!.startsWith("Basic "))
        assertEquals("csrf", requests.first().headers["X-CSRF-TOKEN"])
        assertEquals("JSESSIONID=session", requests.first().headers["Cookie"])
        assertTrue(requests.last().url.endsWith("/api/v1/source-book-favorites?afterRevision=0&limit=50"))
    }
    @Test fun malformedOrMismatchedAckAndConflictCannotChangeAccountFavorites() {
        val mutation = SourceFavoritesJson.mutation(fixture("request"))
        val bad = listOf(fixture("item").put("providerId", "other"), fixture("item").put("version", "1"),
            fixture("item").put("book", JSONObject.NULL), fixture("item").put("extra", true))
        bad.forEach { json ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                NotesFixture.connection { TranslationStoreHttpResponse(200, body = json.toString()) }.client.saveSourceBookFavorite(mutation)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        val conflict = JSONObject().put("code", "SOURCE_BOOK_FAVORITE_CONFLICT").put("current", fixture("item").put("bookId", "different"))
        val mismatch = assertThrows(TranslationStoreException::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(409, body = conflict.toString()) }.client.saveSourceBookFavorite(mutation)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, mismatch.failure)
    }
    @Test fun stableFeedWatermarkProgressAndStrictFieldsAreValidated() {
        val page = fixture("changes")
        listOf(JSONObject(page.toString()).put("hasMore", true).put("nextAfterRevision", 0),
            JSONObject(page.toString()).put("watermark", 0), JSONObject(page.toString()).put("extra", true)).forEach {
            assertThrows(IllegalArgumentException::class.java) { SourceFavoritesJson.page(it, 0, 50, null) }
        }
        assertThrows(IllegalArgumentException::class.java) { SourceFavoritesJson.page(page, 0, 50, 99) }
        assertThrows(IllegalArgumentException::class.java) { SourceFavoritesJson.page(page, 0, 0, null) }
    }
    @Test fun invalidLocalPayloadMakesNoRequestAndRetryAfterIsBounded() {
        var calls = 0
        val client = NotesFixture.connection { calls++; error("Unexpected request") }.client
        val mutation = SourceFavoritesJson.mutation(fixture("request"))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.saveSourceBookFavorite(mutation.copy(expectedVersion = MaxReadingVersion)) } }
        assertEquals(0, calls)
        val limited = assertThrows(SourceFavoritesRateLimited::class.java) { runBlocking {
            NotesFixture.connection { TranslationStoreHttpResponse(429, mapOf("Retry-After" to listOf("999999999999999"))) }.client.sourceBookFavorites(0, 50, null)
        } }
        assertEquals(60L, limited.retryAfterSeconds)
    }
}
