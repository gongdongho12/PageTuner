package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.translation.CachedTranslation
import com.dongholab.pagetuner.translation.JsonFileTranslationCache
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.net.HttpCookie
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Executed only by :app:translationServerIntegrationTest, against an explicit local disposable server. */
class TranslationServerIntegrationTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun realAccountRegistrationAndLanguageProfileRoundTrip() = runBlocking {
        val endpoint = localEndpoint()
        val username = "android-${UUID.randomUUID().toString().replace("-", "").take(24)}"
        val password = "test-${UUID.randomUUID()}"
        val anonymous = HttpTranslationStore(endpoint)
        val languages = anonymous.accountLanguages()
        assertTrue(languages.items.any { it.tag == "ko" && it.available })
        assertTrue(languages.items.any { it.tag == "en" && it.available })
        val registered = anonymous.registerAccount(username, password, ServerAccountDraft("Android reader", "ko", "ja"))
        val authenticated = HttpTranslationStore(endpoint, TranslationStoreBasicAuth(username, password))
        assertEquals(registered, authenticated.accountProfile())
        val updated = authenticated.updateAccountProfile(ServerAccountDraft("Android updated", "fr-CA", "ja"))
        assertEquals(registered.accountId, updated.accountId)
        assertEquals("fr-CA", updated.locale)
        assertEquals("en", updated.effectiveLocale)
        assertEquals("ja", updated.targetLanguage)
        assertEquals(updated, authenticated.accountProfile())
        println("LIVE_ANDROID_ACCOUNT registered=true profileUpdated=true futureLocaleFallback=true targetPreserved=true")
    }

    @Test
    fun realGoogleJobCreatedByAndroidAdapterCompletesAndIsReadable() = runBlocking {
        require(System.getenv("PAGETUNER_LIVE_JOB_CREATE") == "1") { "Explicit live job opt-in is required." }
        val endpoint = localEndpoint()
        val auth = TranslationStoreBasicAuth(requiredEnvironment("PAGETURNER_INTEGRATION_USERNAME"), requiredEnvironment("PAGETURNER_INTEGRATION_PASSWORD"))
        val client = HttpTranslationStore(endpoint, auth)
        // Fixture setup uploads owned text to the real server; job creation/read use the production adapter.
        val transport = DefaultTranslationStoreHttpTransport()
        val headers = mapOf("Authorization" to auth.header, "Content-Type" to "application/json", "X-Requested-With" to "XMLHttpRequest")
        val csrf = transport.execute(TranslationStoreHttpRequest(URI(endpoint).resolve("/api/v1/csrf").toString(), "GET", headers))
        assertEquals(200, csrf.status)
        val token = JSONObject(csrf.body)
        val cookies = csrf.headers.filterKeys { it.equals("Set-Cookie", true) }.values.flatten()
            .flatMap { HttpCookie.parse(it) }.joinToString("; ") { "${it.name}=${it.value}" }
        assertTrue(cookies.isNotBlank())
        val text = listOf("The traveler opened the old wooden door.", "A bright morning welcomed everyone in the village.")
        val upload = JSONObject().put("bookId", "android-job-${UUID.randomUUID()}").put("bookTitle", "Android translation verification")
            .put("chapterId", "chapter-1").put("chapterTitle", "A new morning").put("sourceLanguage", "en")
            .put("paragraphs", JSONArray().apply { text.forEachIndexed { index, paragraph ->
                put(JSONObject().put("paragraphId", "p-${index + 1}").put("ordinal", index).put("text", paragraph))
            } })
        val uploaded = transport.execute(TranslationStoreHttpRequest(URI(endpoint).resolve("/api/v1/chapters/upload").toString(), "POST",
            headers + mapOf(token.getString("headerName") to token.getString("token"), "Cookie" to cookies), upload.toString()))
        assertEquals(200, uploaded.status)
        val chapterId = JSONObject(uploaded.body).getString("recordId")
        val original = requireNotNull(client.original(chapterId).sourceContent)
        val draft = ServerJobDraft(chapterRecordId = chapterId)
        val submitted = client.createTranslationJob(draft)
        val completed = withTimeout(120_000) {
            var job = submitted
            while (job.active) { delay(1_000); job = client.translationJob(job.jobId) }
            job
        }
        assertEquals("COMPLETED", completed.status)
        assertEquals(2, completed.completedParagraphs)
        val stored = client.get(requireNotNull(completed.translationRecordId))
        assertEquals(original.identity, stored.artifact.chapter)
        assertEquals(original.sourceRevision, stored.artifact.sourceRevision)
        assertEquals(original.paragraphs.map { it.paragraphId }, stored.artifact.paragraphs.map { it.paragraphId })
        assertTrue(stored.artifact.paragraphs.all { it.text.isNotBlank() })
        assertTrue(stored.artifact.paragraphs.any { paragraph -> paragraph.text.any { it in '가'..'힣' } })
        assertEquals(submitted.jobId, client.createTranslationJob(draft).jobId)
        val entry = findEntry(client, ServerLibraryKind.Translations, stored.recordId)
        assertEquals(stored, client.read(entry).storedTranslation)
        println("LIVE_ANDROID_JOB provider=GoogleWeb paragraphs=2 completed=true readable=true idempotencyVerified=true")
    }

    @Test
    fun realAuthenticatedServerPersistsAndRestoresAnAndroidCacheWithoutDuplicatingTheRecord() = runBlocking {
        val endpoint = requiredEnvironment("PAGETURNER_INTEGRATION_BASE_URL")
        require(URI(endpoint).host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "Integration validation requires an explicitly configured local disposable server."
        }
        val store = HttpTranslationStore(
            endpoint,
            TranslationStoreBasicAuth(requiredEnvironment("PAGETURNER_INTEGRATION_USERNAME"), requiredEnvironment("PAGETURNER_INTEGRATION_PASSWORD")),
        )
        val documentId = "integration-${UUID.randomUUID()}"
        val chapter = ChapterContent(
            ChapterIdentity(BookIdentity("android-integration", documentId), "chapter-1"), "Integration chapter", "en",
            listOf(ContentParagraph("p-1", 0, "First paragraph."), ContentParagraph("p-2", 1, "Second paragraph.")),
        )
        val document = ReaderDocument(documentId, "Integration book", DocumentFormat.TEXT, listOf(
            ReaderPage(0, listOf(TextSegment("segment-1", 0, 0, "First paragraph."), TextSegment("segment-2", 0, 1, "Second paragraph."))),
        ))
        val mapping = TranslationCacheChapterMapping(
            document, chapter, mapOf("p-1" to "segment-1", "p-2" to "segment-2"),
            TranslationCacheVariant("en", "ko", "integration:model-v1:glossary-v1", "integration", "model-v1", "prompt-v1", "glossary-v1"),
        )
        val original = mapping.keys.mapIndexed { index, key -> CachedTranslation(key, "서버 왕복 문단 ${index + 1}", 1L) }
        val sourceCache = JsonFileTranslationCache(temporaryFolder.newFolder("source").resolve("cache.json"))
        sourceCache.putAll(original)
        val saved = TranslationCacheSyncService(store, sourceCache).publish(mapping)
        assertTrue(saved.created)
        val fetched = store.get(saved.translation.recordId)
        assertEquals(saved.translation, fetched)
        val targetFile = temporaryFolder.newFolder("restored").resolve("cache.json")
        val targetCache = JsonFileTranslationCache(targetFile)
        TranslationCacheSyncService(store, targetCache) { 456L }.restore(saved.translation.recordId, mapping)
        val restored = targetCache.getMany(mapping.keys)
        assertEquals(original.map { it.key }, mapping.keys.map { restored.getValue(it.id).key })
        assertEquals(original.map { it.text }, mapping.keys.map { restored.getValue(it.id).text })
        assertTrue(targetFile.isFile)
        val diskRecords = JSONObject(targetFile.readText(Charsets.UTF_8)).getJSONObject("records")
        assertEquals(original.map { it.text }, mapping.keys.map { diskRecords.getJSONObject(it.id).getString("text") })
        val repeated = TranslationCacheSyncService(store, targetCache).publish(mapping)
        assertFalse(repeated.created)
        assertEquals(saved.translation.recordId, repeated.translation.recordId)
        assertEquals(saved.translation.artifact, repeated.translation.artifact)
    }

    @Test
    fun realGoogleServerTranslationIsReadableWithMatchingOriginalAndTitles() = runBlocking {
        val endpoint = requiredEnvironment("PAGETURNER_INTEGRATION_BASE_URL")
        require(URI(endpoint).host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "Integration validation requires an explicitly configured local disposable server."
        }
        val translationId = requiredEnvironment("PAGETUNER_LIVE_TRANSLATION_RECORD_ID")
        val chapterId = requiredEnvironment("PAGETUNER_LIVE_CHAPTER_RECORD_ID")
        val store = HttpTranslationStore(endpoint, TranslationStoreBasicAuth(
            requiredEnvironment("PAGETURNER_INTEGRATION_USERNAME"), requiredEnvironment("PAGETURNER_INTEGRATION_PASSWORD")))
        val translationEntry = findEntry(store, ServerLibraryKind.Translations, translationId)
        val originalEntry = findEntry(store, ServerLibraryKind.Originals, chapterId)
        val translation = store.get(translationId)
        val translatedDocument = store.read(translationEntry)
        val originalDocument = store.read(originalEntry)
        val original = requireNotNull(originalDocument.sourceContent)

        assertEquals(translation, translatedDocument.storedTranslation)
        assertEquals(original.identity, translation.artifact.chapter)
        assertEquals(original.sourceRevision, translation.artifact.sourceRevision)
        assertEquals(original.paragraphs.map { it.paragraphId }, translation.artifact.paragraphs.map { it.paragraphId })
        assertEquals(originalEntry.paragraphCount, translationEntry.paragraphCount)
        assertEquals("ko", translation.artifact.targetLanguage)
        assertTrue("This record must have been translated by the actual Google provider", translation.artifact.providerId.startsWith("google"))
        assertTrue("The translated chapter must contain Korean text", translatedDocument.text.any { it in '가'..'힣' })
        assertTrue("Translation must differ from the original chapter", originalDocument.text != translatedDocument.text)
        assertTrue("The server must supply display titles", translationEntry.title != "제목 없는 번역")
        assertEquals(originalEntry.title, translationEntry.title)
        assertTrue(translationEntry.title.contains(original.title))
        println("LIVE_ANDROID_READ provider=${translation.artifact.providerId} paragraphs=${translationEntry.paragraphCount} " +
            "originalCharacters=${originalDocument.text.length} translatedCharacters=${translatedDocument.text.length} titlesVerified=true")
    }

    private suspend fun findEntry(store: HttpTranslationStore, kind: ServerLibraryKind, recordId: String): ServerLibraryEntry {
        var page = 0
        while (page < 100) {
            val result = store.list(kind, page, 50)
            result.items.firstOrNull { it.recordId == recordId }?.let { return it }
            if (!result.hasNext) break
            page += 1
        }
        error("The supplied live record was not found in the authenticated $kind library.")
    }

    private fun requiredEnvironment(name: String): String = requireNotNull(System.getenv(name)?.takeIf { it.isNotBlank() }) {
        "$name must be set for the explicitly requested server integration test."
    }

    private fun localEndpoint(): String = requiredEnvironment("PAGETURNER_INTEGRATION_BASE_URL").also {
        require(URI(it).host in setOf("localhost", "127.0.0.1", "::1", "[::1]")) {
            "Integration validation requires an explicitly configured local disposable server."
        }
    }
}
