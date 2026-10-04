package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.content.ContentParagraph
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ParagraphTranslationChunkerTest {
    @Test fun preservesEverySourceCharacterAndUsesStableUniqueIds() {
        val source = "  One sentence.\n\nAnother sentence with an emoji \uD83D\uDE00.\t".repeat(80)
        val chunks = ParagraphTranslationChunker.split("paragraph", source, 37, emptyList())
        assertEquals(source, chunks.joinToString("") { it.originalText })
        assertEquals(chunks, ParagraphTranslationChunker.split("paragraph", source, 37, emptyList()))
        assertEquals(chunks.size, chunks.map { it.id }.distinct().size)
        assertTrue(chunks.all { it.originalText.length <= 37 })
        assertTrue(chunks.none { it.originalText.lastOrNull()?.isHighSurrogate() == true })
        assertTrue(chunks.none { it.originalText.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun neverCutsASurrogatePairWithoutWhitespace() {
        val source = "\uD83D\uDE00".repeat(40)
        val chunks = ParagraphTranslationChunker.split("paragraph", source, 7, emptyList())
        assertEquals(source, chunks.joinToString("") { it.originalText })
        assertTrue(chunks.all { it.text.length % 2 == 0 })
    }

    @Test fun keepsGlossaryTermsWholeAtChunkBoundaries() {
        val entries = listOf(BookGlossaryEntry("name", "Alice Queen", "앨리스 여왕"))
        val source = "A hallway. Alice Queen opened the door. ".repeat(20)
        val chunks = ParagraphTranslationChunker.split("paragraph", source, 24, entries)
        assertEquals(source, chunks.joinToString("") { it.originalText })
        assertEquals(20, chunks.sumOf { Regex("Alice Queen").findAll(it.text).count() })
        assertTrue(chunks.all { it.originalText.length <= 24 })
    }

    @Test fun translatesALongParagraphInBoundedRequestsAndCheckpointsOnlyOnce() = runTest {
        val source = "The quiet garden was beautiful. \uD83D\uDE00\n".repeat(900)
        val requests = mutableListOf<TranslationRequest>()
        val engine = engine { request -> requests += request; request.segments.map { TranslatedSegment(it.id, it.text) } }
        var checkpoints = 0
        val result = engine.translate(chapter(source), settings(), onParagraph = { id, text ->
            assertEquals("original-paragraph", id)
            assertEquals(source, text)
            checkpoints++
        })
        assertTrue(source.length > 24_000)
        assertTrue(requests.flatMap { it.segments }.all { it.text.length <= 1_000 })
        assertTrue(requests.size > 1)
        assertEquals(source, result.single().text)
        assertEquals(1, checkpoints)
    }

    @Test fun failedPartialParagraphIsRetranslatedOnResumeWithTheSameChunkIds() = runTest {
        val source = "A full sentence. ".repeat(300)
        val firstIds = mutableListOf<String>()
        var attempts = 0
        var checkpoints = 0
        val failure = runCatching {
            engine { request ->
                firstIds += request.segments.map { it.id }
                if (++attempts == 2) throw providerHttpException("fake", 401, "denied")
                request.segments.map { TranslatedSegment(it.id, it.text) }
            }.translate(chapter(source), settings().copy(batchSize = 1), onParagraph = { _, _ -> checkpoints++ })
        }.exceptionOrNull()
        assertTrue(failure is TranslationProviderException)
        assertEquals(0, checkpoints)
        val resumedIds = mutableListOf<String>()
        engine { request -> resumedIds += request.segments.map { it.id }; request.segments.map { TranslatedSegment(it.id, it.text) } }
            .translate(chapter(source), settings().copy(batchSize = 1), onParagraph = { _, _ -> checkpoints++ })
        assertEquals(firstIds, resumedIds.take(firstIds.size))
        assertEquals(1, checkpoints)
    }

    @Test fun aMalformedLastChunkNeverPublishesAnIncompleteParagraph() = runTest {
        val source = "The garden was beautiful. ".repeat(120)
        var requests = 0
        var checkpoints = 0
        val error = runCatching {
            engine { request ->
                requests++
                request.segments.map { TranslatedSegment(it.id, if (requests == 2) " " else "번역") }
            }.translate(chapter(source), settings().copy(batchSize = 1), onParagraph = { _, _ -> checkpoints++ })
        }.exceptionOrNull()
        assertTrue(error is TranslationProviderException)
        assertEquals(0, checkpoints)
    }

    private fun chapter(source: String) = ChapterContent(ChapterIdentity(BookIdentity("source", "book"), "chapter"), "Chapter", "en", listOf(ContentParagraph("original-paragraph", 0, source)))
    private fun settings() = TranslationSettings(TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML, "", sourceLanguage = "en", paceMode = TranslationPaceMode.OFFLINE_PREFETCH)
    private fun engine(translate: suspend (TranslationRequest) -> List<TranslatedSegment>) = ChapterTranslationEngine(providerFactory = {
        object : TranslationProvider {
            override val id = "fake"
            override suspend fun translate(request: TranslationRequest) = translate.invoke(request)
        }
    })
}
