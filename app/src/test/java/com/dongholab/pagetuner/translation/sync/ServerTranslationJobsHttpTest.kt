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

class ServerTranslationJobsHttpTest {
    @Test fun createsWithAuthenticatedCsrfAndReadsTheSameJob() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = JobsFixture.client { request ->
            requests += request
            when {
                request.url.endsWith("/translation-providers") -> JobsFixture.response(JobsFixture.providers())
                request.url.endsWith("/csrf") -> JobsFixture.csrf()
                else -> JobsFixture.response(JobsFixture.job())
            }
        }
        assertEquals(2, client.translationProviders().size)
        val draft = ServerJobDraft(chapterRecordId = JobsFixture.chapterId)
        val created = client.createTranslationJob(draft)
        assertEquals(JobsFixture.jobId, client.translationJob(created.jobId).jobId)
        val post = requests.single { it.method == "POST" }
        assertEquals("JSESSIONID=session", post.headers["Cookie"])
        assertEquals("csrf-token", post.headers["X-CSRF-TOKEN"])
        assertTrue(requests.all { it.headers.containsKey("Authorization") })
        assertEquals(draft.idempotencyKey, JSONObject(post.body!!).getString("idempotencyKey"))
        assertFalse(JSONObject(post.body!!).has("apiKey"))
    }

    @Test fun retryUsesExactGlossaryEntriesInsteadOfParsingAnAmbiguousDisplayString() {
        val terms = listOf(ServerJobGlossaryEntry("a=b", "x\ny"))
        val draft = ServerJobDraft(chapterRecordId = JobsFixture.chapterId, glossary = "a=b=x\ny", glossaryEntries = terms,
            retryOf = JobsFixture.jobId, apiKey = "secret-test-key")
        val body = ServerTranslationJobJson.encode(draft)
        assertEquals("a=b", body.getJSONArray("glossary").getJSONObject(0).getString("source"))
        assertEquals("x\ny", body.getJSONArray("glossary").getJSONObject(0).getString("target"))
        assertEquals(JobsFixture.jobId, body.getString("retryOf"))
        assertFalse(draft.toString().contains("secret-test-key"))
        assertThrows(IllegalArgumentException::class.java) { ServerTranslationJobJson.encode(draft.copy(targetLanguage = "AUTO")) }
    }

    @Test fun corruptCompletionOrPaginationCannotPretendToBeACompleteTranslation() {
        listOf(
            JobsFixture.job("COMPLETED").put("completedParagraphs", 0),
            JobsFixture.job("COMPLETED").put("translationRecordId", JSONObject.NULL),
            JobsFixture.job("RUNNING").put("canRetry", true),
        ).forEach { json ->
            val error = assertThrows(TranslationStoreException::class.java) { runBlocking {
                JobsFixture.client { JobsFixture.response(json) }.translationJob(JobsFixture.jobId)
            } }
            assertEquals(TranslationStoreFailure.INVALID_RESPONSE, error.failure)
        }
        assertThrows(TranslationStoreException::class.java) { runBlocking {
            JobsFixture.client { JobsFixture.response(JobsFixture.page(JobsFixture.job()).put("totalItems", 2)) }.translationJobs()
        } }
    }

    @Test fun cancellationIsAPostAndConflictsRemainExplicit() = runBlocking {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val client = JobsFixture.client { request ->
            requests += request
            if (request.url.endsWith("/csrf")) JobsFixture.csrf() else JobsFixture.response(JobsFixture.job("CANCELLED"))
        }
        assertEquals("CANCELLED", client.cancelTranslationJob(JobsFixture.jobId).status)
        assertTrue(requests.last().url.endsWith("/${JobsFixture.jobId}/cancel"))
        assertEquals("POST", requests.last().method)
        try {
            JobsFixture.client { request -> if (request.url.endsWith("/csrf")) JobsFixture.csrf() else TranslationStoreHttpResponse(409) }
                .createTranslationJob(ServerJobDraft(chapterRecordId = JobsFixture.chapterId))
            fail("Conflict accepted")
        } catch (error: TranslationStoreException) { assertEquals(TranslationStoreFailure.CONFLICT, error.failure) }
    }
}

internal object JobsFixture {
    const val chapterId = "11111111-1111-4111-8111-111111111111"
    const val jobId = "22222222-2222-4222-8222-222222222222"
    const val translationId = "33333333-3333-4333-8333-333333333333"
    val chapter = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en",
        listOf(ContentParagraph("p-1", 0, "Original source paragraph.")))
    val entry = ServerLibraryEntry(chapterId, "Book · Chapter", "en", 1, chapter.sourceRevision, ServerLibraryKind.Originals)
    fun original() = JSONObject().put("recordId", chapterId).put("providerId", "source").put("bookId", "book")
        .put("chapterId", "chapter").put("bookTitle", "Book").put("chapterTitle", "Chapter").put("sourceLanguage", "en")
        .put("sourceRevision", chapter.sourceRevision).put("paragraphs", JSONArray().put(JSONObject()
            .put("paragraphId", "p-1").put("ordinal", 0).put("text", "Original source paragraph.")))
    fun providers() = JSONObject().put("providers", JSONArray().put(JSONObject().put("id", "GOOGLE_WEB_TRANSLATE_HTML")
        .put("displayName", "Google Web").put("configured", true).put("requiresKey", false).put("defaultEndpoint", "").put("defaultModel", ""))
        .put(JSONObject().put("id", "DEEPSEEK").put("displayName", "DeepSeek").put("configured", false).put("requiresKey", true)
            .put("defaultEndpoint", "https://api.deepseek.com/chat/completions").put("defaultModel", "deepseek-chat")))
    fun job(status: String = "QUEUED", provider: String = "GOOGLE_WEB_TRANSLATE_HTML", terms: List<ServerJobGlossaryEntry> = emptyList()) =
        JSONObject().put("jobId", jobId).put("status", status).put("chapterRecordId", chapterId).put("bookTitle", "Book")
            .put("chapterTitle", "Chapter").put("providerKind", provider).put("targetLanguage", "ko")
            .put("completedParagraphs", if (status == "COMPLETED") 1 else 0).put("totalParagraphs", 1)
            .put("translationRecordId", if (status == "COMPLETED") translationId else JSONObject.NULL)
            .put("errorCode", JSONObject.NULL).put("createdAt", "2026-09-14T00:00:00Z").put("updatedAt", "2026-09-14T00:00:00Z")
            .put("canRetry", status in setOf("FAILED", "CANCELLED", "INTERRUPTED"))
            .put("settings", JSONObject().put("sourceLanguage", "en").put("targetLanguage", "ko")
                .put("endpoint", if (provider == "DEEPSEEK") "https://api.deepseek.com/chat/completions" else "")
                .put("model", if (provider == "DEEPSEEK") "deepseek-chat" else "")
                .put("glossary", JSONArray().apply { terms.forEach { put(JSONObject().put("source", it.source).put("target", it.target)) } }))
    fun page(job: JSONObject) = JSONObject().put("items", JSONArray().put(job)).put("page", 0).put("size", 12)
        .put("totalItems", 1).put("totalPages", 1).put("hasNext", false)
    fun profile() = JSONObject("""{"accountId":"44444444-4444-4444-8444-444444444444","username":"reader","displayName":"Reader","locale":"ko","targetLanguage":"ko","effectiveLocale":"ko"}""")
    fun response(json: JSONObject) = TranslationStoreHttpResponse(200, body = json.toString())
    fun csrf() = TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
        """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
    fun client(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "long-password"), TranslationStoreHttpTransport(handler))
}
