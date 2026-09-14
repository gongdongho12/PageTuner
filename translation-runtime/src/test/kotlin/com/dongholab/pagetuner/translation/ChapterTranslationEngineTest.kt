package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ChapterTranslationEngineTest {
    private val settings = TranslationSettings(TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML, "", sourceLanguage = "auto", batchSize = 2)
    private val chapter = ChapterContent(
        ChapterIdentity(BookIdentity("source", "novel"), "chapter-1"), "Chapter 1", "en",
        listOf(ContentParagraph("intro", 0, "Alice opened the door."), ContentParagraph("body", 1, "The room was quiet."), ContentParagraph("end", 2, "She went inside.")),
    )

    @Test fun resumesByParagraphIdAndPublishesInOriginalOrder() = runTest {
        val requests = mutableListOf<TranslationRequest>()
        val checkpoints = mutableListOf<Pair<String, String>>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val engine = engine { request ->
            requests += request
            request.segments.reversed().map { TranslatedSegment(it.id, "번역 ${it.id}") }
        }
        val result = engine.translate(chapter, settings, completed = mapOf("intro" to "저장된 번역"),
            onParagraph = { id, text -> checkpoints += id to text }, onProgress = { done, total -> progress += done to total })
        assertEquals(listOf("body", "end"), requests.single().segments.map { it.id })
        assertEquals("en", requests.single().sourceLanguage)
        assertEquals(listOf("intro", "body", "end"), result.map { it.paragraphId })
        assertEquals(listOf("body", "end"), checkpoints.map { it.first })
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
    }

    @Test fun rejectsMissingDuplicateExtraAndBlankOutputsBeforeAnyCheckpoint() = runTest {
        val invalid = listOf(
            listOf(TranslatedSegment("intro", "번역")),
            listOf(TranslatedSegment("intro", "번역"), TranslatedSegment("intro", "번역")),
            listOf(TranslatedSegment("intro", "번역"), TranslatedSegment("alien", "번역")),
            listOf(TranslatedSegment("intro", "번역"), TranslatedSegment("body", "  ")),
        )
        invalid.forEach { response ->
            var checkpoints = 0
            val error = runCatching { engine { response }.translate(chapter, settings, onParagraph = { _, _ -> checkpoints++ }) }.exceptionOrNull()
            assertTrue(error is TranslationProviderException)
            assertEquals(TranslationProviderErrorKind.ResponseFormat, (error as TranslationProviderException).failure.kind)
            assertEquals(0, checkpoints)
        }
    }

    @Test fun retryableErrorsAreBoundedAndAuthenticationIsNotRetried() = runTest {
        var attempts = 0
        val engine = engine {
            attempts++
            throw providerHttpException("test", 429, "limited")
        }
        assertTrue(runCatching { engine.translate(chapter, settings) }.exceptionOrNull() is TranslationProviderException)
        assertEquals(3, attempts)
        attempts = 0
        val authEngine = engine {
            attempts++
            throw providerHttpException("test", 401, "denied")
        }
        assertTrue(runCatching { authEngine.translate(chapter, settings) }.exceptionOrNull() is TranslationProviderException)
        assertEquals(1, attempts)
    }

    @Test fun successfulRetryDoesNotDuplicateCheckpoints() = runTest {
        var attempts = 0
        val saved = mutableListOf<String>()
        val engine = engine { request ->
            if (++attempts == 1) throw providerHttpException("test", 503, "unavailable")
            request.segments.map { TranslatedSegment(it.id, "번역") }
        }
        engine.translate(chapter, settings, onParagraph = { id, _ -> saved += id })
        assertEquals(3, attempts)
        assertEquals(listOf("intro", "body", "end"), saved)
    }

    @Test fun providerAndCheckpointCancellationArePropagatedWithoutRetry() = runTest {
        var attempts = 0
        val providerCancellation = runCatching {
            engine { attempts++; throw CancellationException("provider cancelled") }.translate(chapter, settings)
        }.exceptionOrNull()
        assertTrue(providerCancellation is CancellationException)
        assertEquals(1, attempts)
        var saved = 0
        val checkpointCancellation = runCatching {
            engine { request -> request.segments.map { TranslatedSegment(it.id, "번역") } }
                .translate(chapter, settings, onParagraph = { _, _ -> saved++; throw CancellationException("checkpoint cancelled") })
        }.exceptionOrNull()
        assertTrue(checkpointCancellation is CancellationException)
        assertEquals(1, saved)
    }

    @Test fun durableCheckpointFailureStopsFurtherWritesAndProgress() = runTest {
        var saved = 0
        val progress = mutableListOf<Int>()
        val error = runCatching {
            engine { request -> request.segments.map { TranslatedSegment(it.id, "번역") } }.translate(chapter, settings,
                onParagraph = { _, _ -> saved++; error("disk full") }, onProgress = { done, _ -> progress += done })
        }.exceptionOrNull()
        assertEquals("disk full", error?.message)
        assertEquals(1, saved)
        assertEquals(listOf(0), progress)
    }

    @Test fun refusesUnknownAndBlankCheckpointsAndWrongSourceLanguage() = runTest {
        var calls = 0
        val engine = engine { calls++; emptyList() }
        for (checkpoint in listOf(mapOf("unknown" to "번역"), mapOf("intro" to " "))) {
            assertTrue(runCatching { engine.translate(chapter, settings, completed = checkpoint) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertTrue(runCatching { engine.translate(chapter, settings.copy(sourceLanguage = "ja")) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(0, calls)
    }

    @Test fun returnsCompleteCheckpointsWithoutConstructingAProvider() = runTest {
        val engine = ChapterTranslationEngine(providerFactory = { error("must not construct") })
        val result = engine.translate(chapter, settings, completed = chapter.paragraphs.associate { it.paragraphId to "번역 ${it.paragraphId}" })
        assertEquals(3, result.size)
    }

    @Test fun rejectsOversizedParagraphInsteadOfBreakingItsIdentity() = runTest {
        val engine = ChapterTranslationEngine(providerFactory = { error("must not construct") }, maxParagraphCharacters = 5)
        assertTrue(runCatching { engine.translate(chapter, settings) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun refusesAnExternallyMutatedChapterRevisionAndSnapshotsAcrossCallbacks() = runTest {
        val mutableParagraphs = chapter.paragraphs.toMutableList()
        val stale = chapter.copy(paragraphs = mutableParagraphs)
        mutableParagraphs[0] = mutableParagraphs[0].copy(text = "Changed source")
        val engine = engine { request -> request.segments.map { TranslatedSegment(it.id, "번역") } }
        assertTrue(runCatching { engine.translate(stale, settings) }.exceptionOrNull() is IllegalArgumentException)

        val valid = chapter.copy(paragraphs = mutableParagraphs)
        val result = engine.translate(valid, settings, onProgress = { _, _ -> mutableParagraphs.clear() })
        assertEquals(listOf("intro", "body", "end"), result.map { it.paragraphId })
    }

    @Test fun glossaryProtectionIsExecutedByTheSharedProvider() = runTest {
        val glossary = BookGlossary("novel", listOf(BookGlossaryEntry("alice", "Alice", "앨리스")))
        val engine = engine { request ->
            request.segments.map { TranslatedSegment(it.id, it.text) }
        }
        val result = engine.translate(chapter, settings, glossary)
        assertEquals("앨리스 opened the door.", result.first().text)
    }

    @Test fun identityTracksEndpointModelPromptAndTranslationGlossaryButNotSecretsOrDisplayAliases() {
        val llm = settings.copy(providerKind = TranslationProviderKind.OPENAI_COMPATIBLE_LLM, apiKey = "secret-a", llmEndpoint = "https://provider.example/chat", llmModel = "model-a")
        val glossary = BookGlossary("novel", listOf(BookGlossaryEntry("alice", "Alice", "앨리스", displayTerm = "별명")))
        val identity = TranslationRuntimeIdentity.describe(llm, glossary)
        assertFalse(llm.toString().contains("secret-a"))
        assertFalse(identity.toString().contains("secret-a"))
        assertFalse(identity.providerId.contains("https://"))
        assertEquals(identity, TranslationRuntimeIdentity.describe(llm.copy(apiKey = "secret-b"), glossary.copy(entries = glossary.entries.map { it.copy(displayTerm = "다른 별명") })))
        assertNotEquals(identity, TranslationRuntimeIdentity.describe(llm.copy(llmEndpoint = "https://other.example/chat"), glossary))
        assertNotEquals(identity, TranslationRuntimeIdentity.describe(llm.copy(llmModel = "model-b"), glossary))
        assertNotEquals(identity, TranslationRuntimeIdentity.describe(llm, glossary.copy(entries = glossary.entries.map { it.copy(translatedTerm = "앨리") })))
        assertEquals("${OpenAiCompatibleLlmTranslationProvider.PromptRevision}:paragraph-chunks-v1", identity.promptRevision)
    }

    private fun engine(translate: suspend (TranslationRequest) -> List<TranslatedSegment>) = ChapterTranslationEngine(
        providerFactory = { object : TranslationProvider {
            override val id = "fake"
            override suspend fun translate(request: TranslationRequest) = translate.invoke(request)
        } },
    )
}
