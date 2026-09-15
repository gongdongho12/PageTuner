package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ServerLibraryHttpTest {
    @Test
    fun sharedListAndStoredFixturesLoadWithSameAuthenticationAndValidatedHashes() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = client { request ->
            requests += request
            TranslationStoreHttpResponse(200, body = fixture(if ('?' in request.url) "list-response.json" else "stored-response.json"))
        }
        val page = client.list(ServerLibraryKind.Translations)
        val document = client.read(page.items.single())
        assertEquals(2, document.paragraphs.size)
        assertEquals("제목 없는 번역", document.entry.title)
        assertTrue(requests.all { it.headers["Authorization"] == "Basic cmVhZGVyOnBhc3M=" })
        assertTrue(requests.first().url.endsWith("/translations?page=0&size=12"))
    }

    @Test
    fun corruptListMetadataFailsInsteadOfDisplayingAnEmptyLibrary() {
        val payload = JSONObject(fixture("list-response.json")).put("totalItems", 2)
        val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
            client { TranslationStoreHttpResponse(200, body = payload.toString()) }.list(ServerLibraryKind.Translations)
        } }
        assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
    }

    @Test
    fun originalChapterRevisionIsRecomputedBeforeItCanBeRead() = runBlocking {
        val chapter = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en",
            listOf(ContentParagraph("p-1", 0, "Original source paragraph.")))
        val entry = ServerLibraryEntry("11111111-1111-4111-8111-111111111111", "Book · Chapter", "en", 1,
            chapter.sourceRevision, ServerLibraryKind.Originals)
        val payload = JSONObject().put("recordId", entry.recordId).put("providerId", "source").put("bookId", "book")
            .put("chapterId", "chapter").put("chapterTitle", "Chapter").put("sourceLanguage", "en")
            .put("sourceRevision", chapter.sourceRevision).put("paragraphs", JSONArray().put(JSONObject()
                .put("paragraphId", "p-1").put("ordinal", 0).put("text", "Original source paragraph.")))
        val client = client { TranslationStoreHttpResponse(200, body = payload.toString()) }
        assertEquals("Original source paragraph.", client.read(entry).text)
        payload.getJSONArray("paragraphs").getJSONObject(0).put("text", "Corrupted")
        try { client.read(entry); fail("Corrupt original accepted") }
        catch (error: TranslationStoreException) { assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure) }
    }

    @Test
    fun displayTitlesAreSentWithExistingCsrfSaveContract() = runBlocking {
        val stored = fixture("stored-response.json")
        var saved: JSONObject? = null
        val client = client { request ->
            if (request.url.endsWith("/csrf")) TranslationStoreHttpResponse(200,
                headers = mapOf("Set-Cookie" to listOf("JSESSIONID=test; Path=/; HttpOnly")),
                body = """{"headerName":"X-CSRF-TOKEN","token":"token"}""")
            else {
                saved = JSONObject(requireNotNull(request.body))
                assertEquals("token", request.headers["X-CSRF-TOKEN"])
                TranslationStoreHttpResponse(201, body = stored)
            }
        }
        client.save(TranslationStoreJson.decode(JSONObject(stored)).artifact, "Book", "Chapter")
        assertEquals("Book", saved!!.getString("bookTitle"))
        assertEquals("Chapter", saved!!.getString("chapterTitle"))
    }

    private fun client(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "pass"), TranslationStoreHttpTransport(handler))
    private fun fixture(name: String) = requireNotNull(javaClass.classLoader?.getResource("translation-v1/$name")).readText()
}
