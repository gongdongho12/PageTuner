package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.PlainTextDocumentParser
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationViewModelTest {
    @get:Rule
    val mainDispatcherRule = TranslationMainDispatcherRule()

    @Test
    fun keepsApiKeyWhenResettingForNewDocument() {
        val viewModel = TranslationViewModel()

        viewModel.updateApiKey("secret")
        viewModel.resetForDocument()

        assertEquals("secret", viewModel.uiState.value.apiKey)
        assertNull(viewModel.uiState.value.translation)
        assertSame(TranslationStatus.Ready, viewModel.uiState.value.status)
    }

    @Test
    fun clearsPageTranslationWithoutClearingApiKey() {
        val viewModel = TranslationViewModel()

        viewModel.updateApiKey("secret")
        viewModel.clearPageTranslation()

        assertEquals("secret", viewModel.uiState.value.apiKey)
        assertEquals(0f, viewModel.uiState.value.progress, 0f)
        assertSame(TranslationStatus.Ready, viewModel.uiState.value.status)
    }

    @Test
    fun appTranslationFlowPublishesTranslatedPage() = runTest(mainDispatcherRule.dispatcher) {
        val document = PlainTextDocumentParser.parse("Chapter 1", "Hello from the app reader.")
        val repository = TranslationRepository(
            provider = ViewModelFakeProvider(),
            cache = ViewModelMemoryCache(),
        )
        val settings = TranslationSettings(
            providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
            apiKey = "",
            sourceLanguage = "en",
            targetLanguage = "ko",
            paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
        )
        val viewModel = TranslationViewModel()

        viewModel.translatePage(document, document.pages.first(), settings, repository)

        val state = viewModel.uiState.value
        assertFalse(state.busy)
        assertNotNull(state.translation)
        assertEquals("ko:Hello from the app reader.", state.translation?.text)
        assertEquals(1f, state.progress, 0f)
    }

    @Test
    fun initialCacheLookupStaysLoadingUntilLookupFinishes() = runTest(mainDispatcherRule.dispatcher) {
        val releaseCache = CompletableDeferred<Unit>()
        val document = PlainTextDocumentParser.parse("Initial", "First page")
        val repository = TranslationRepository(
            provider = ViewModelFakeProvider(),
            cache = BlockingViewModelCache(releaseCache),
        )
        val viewModel = TranslationViewModel()

        viewModel.resetForDocument(document.id, 0)
        viewModel.loadCachedPage(document, document.pages.first(), webTranslationSettings(), repository, false)

        assertEquals(ReaderTranslationLoadStage.CheckingCache, viewModel.uiState.value.readerLoad.stage)
        assertTrue(viewModel.uiState.value.readerLoad.isLoading)

        releaseCache.complete(Unit)
        advanceUntilIdle()

        assertEquals(ReaderTranslationLoadStage.Missing, viewModel.uiState.value.readerLoad.stage)
        assertFalse(viewModel.uiState.value.readerLoad.isLoading)
    }

    @Test
    fun rollingInitialTranslationStaysLoadingUntilTextIsVisible() = runTest(mainDispatcherRule.dispatcher) {
        val releaseProvider = CompletableDeferred<Unit>()
        val document = rollingDocument(pageCount = 1)
        val repository = TranslationRepository(
            ViewModelFakeProvider(beforeResponse = { releaseProvider.await() }),
            ViewModelMemoryCache(),
        )
        val viewModel = TranslationViewModel()

        viewModel.resetForDocument(document.id, 0)
        viewModel.startRollingPrefetch(document, 0, webTranslationSettings(), repository)

        assertEquals(ReaderTranslationLoadStage.Translating, viewModel.uiState.value.readerLoad.stage)
        assertNull(viewModel.uiState.value.translation)

        releaseProvider.complete(Unit)
        advanceUntilIdle()

        assertEquals(ReaderTranslationLoadStage.Ready, viewModel.uiState.value.readerLoad.stage)
        assertNotNull(viewModel.uiState.value.translation)
    }

    @Test
    fun documentResetCancelsOldTranslationWithoutPublishingAnError() =
        runTest(mainDispatcherRule.dispatcher) {
            val releaseProvider = CompletableDeferred<Unit>()
            val document = PlainTextDocumentParser.parse("Old chapter", "Old page text")
            val repository = TranslationRepository(
                provider = ViewModelFakeProvider(beforeResponse = { releaseProvider.await() }),
                cache = ViewModelMemoryCache(),
            )
            val settings = TranslationSettings(
                providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
                apiKey = "",
                sourceLanguage = "en",
                targetLanguage = "ko",
                paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
            )
            val viewModel = TranslationViewModel()

            viewModel.translatePage(document, document.pages.first(), settings, repository)
            assertEquals(true, viewModel.uiState.value.busy)

            viewModel.resetForDocument()
            releaseProvider.complete(Unit)

            assertFalse(viewModel.uiState.value.busy)
            assertNull(viewModel.uiState.value.translation)
            assertSame(TranslationStatus.Ready, viewModel.uiState.value.status)
        }

    @Test
    fun pageNavigationCancelsOldTranslationAndRefreshesTheNewPageState() =
        runTest(mainDispatcherRule.dispatcher) {
            val releaseProvider = CompletableDeferred<Unit>()
            val document = rollingDocument(pageCount = 2)
            val repository = TranslationRepository(
                provider = ViewModelFakeProvider(beforeResponse = { releaseProvider.await() }),
                cache = ViewModelMemoryCache(),
            )
            val viewModel = TranslationViewModel()

            viewModel.translatePage(document, document.pages[0], webTranslationSettings(), repository)
            assertTrue(viewModel.uiState.value.busy)

            viewModel.loadCachedPage(
                document,
                document.pages[1],
                webTranslationSettings(),
                repository,
                showMissingStatus = false,
            )
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.busy)
            assertEquals(1, state.readerLoad.pageIndex)
            assertEquals(ReaderTranslationLoadStage.Missing, state.readerLoad.stage)
            assertSame(TranslationStatus.Ready, state.status)
            releaseProvider.complete(Unit)
        }

    @Test
    fun passiveCachedPageRefreshDoesNotPublishARepeatedCacheMessage() =
        runTest(mainDispatcherRule.dispatcher) {
            val document = rollingDocument(pageCount = 1)
            val repository = TranslationRepository(ViewModelFakeProvider(), ViewModelMemoryCache())
            val viewModel = TranslationViewModel()
            repository.translatePage(document, document.pages[0], webTranslationSettings())

            viewModel.loadCachedPage(
                document,
                document.pages[0],
                webTranslationSettings(),
                repository,
                showMissingStatus = false,
            )
            advanceUntilIdle()

            assertNotNull(viewModel.uiState.value.translation)
            assertSame(TranslationStatus.Ready, viewModel.uiState.value.status)
            assertEquals(ReaderTranslationLoadStage.Ready, viewModel.uiState.value.readerLoad.stage)
        }

    @Test
    fun rollingPrefetchTranslatesTenPagesAndKeepsReaderInteractive() =
        runTest(mainDispatcherRule.dispatcher) {
            val provider = RecordingRollingProvider()
            val document = rollingDocument(pageCount = 25)
            val repository = TranslationRepository(provider, ViewModelMemoryCache())
            val settings = webTranslationSettings()
            val viewModel = TranslationViewModel()

            viewModel.startRollingPrefetch(document, 0, settings, repository)
            advanceUntilIdle()

            val state = viewModel.uiState.value
            assertFalse(state.busy)
            assertFalse(state.rolling.running)
            assertEquals(10, provider.translatedPageIndexes.size)
            assertEquals((0 until 10).toSet(), provider.translatedPageIndexes)
            assertEquals(1, provider.requests)
            assertEquals(10, state.rolling.readyPageCount)
            assertEquals(TranslationPageFlag.Ready, state.rolling.flagFor(0))
            assertEquals("ko:Page 1", state.translation?.text)
        }

    @Test
    fun rollingPrefetchQueuesNextTenAtFivePageThreshold() =
        runTest(mainDispatcherRule.dispatcher) {
            val provider = RecordingRollingProvider()
            val document = rollingDocument(pageCount = 25)
            val repository = TranslationRepository(provider, ViewModelMemoryCache())
            val settings = webTranslationSettings()
            val viewModel = TranslationViewModel()

            viewModel.startRollingPrefetch(document, 0, settings, repository)
            advanceUntilIdle()
            viewModel.onReaderPageChanged(document, 3, settings, repository)
            advanceUntilIdle()
            assertEquals(10, provider.translatedPageIndexes.size)

            viewModel.onReaderPageChanged(document, 4, settings, repository)
            advanceUntilIdle()

            assertEquals(20, provider.translatedPageIndexes.size)
            assertEquals((0 until 20).toSet(), provider.translatedPageIndexes)
            assertEquals(2, provider.requests)
            assertEquals(10, viewModel.uiState.value.rolling.windowStartIndex)
            assertEquals(20, viewModel.uiState.value.rolling.windowEndExclusive)
            assertEquals(14, viewModel.uiState.value.rolling.triggerPageIndex)
        }

    private fun webTranslationSettings() = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
        apiKey = "",
        sourceLanguage = "en",
        targetLanguage = "ko",
        paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
    )

    private fun rollingDocument(pageCount: Int): ReaderDocument {
        return ReaderDocument(
            id = "rolling-document",
            title = "Rolling",
            format = DocumentFormat.TEXT,
            pages = (0 until pageCount).map { pageIndex ->
                ReaderPage(
                    index = pageIndex,
                    segments = listOf(
                        TextSegment(
                            id = "segment-$pageIndex",
                            pageIndex = pageIndex,
                            indexInPage = 0,
                            text = "Page ${pageIndex + 1}",
                        ),
                    ),
                )
            },
        )
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationMainDispatcherRule(
    val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class ViewModelFakeProvider(
    private val beforeResponse: suspend () -> Unit = {},
) : TranslationProvider {
    override val id: String = "view-model-fake"

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        beforeResponse()
        return request.segments.map { segment ->
            TranslatedSegment(segment.id, "ko:${segment.text}")
        }
    }
}

private class ViewModelMemoryCache : TranslationCache {
    private val records = mutableMapOf<String, CachedTranslation>()

    override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> =
        keys.mapNotNull { key -> records[key.id]?.let { key.id to it } }.toMap()

    override suspend fun putAll(records: List<CachedTranslation>) {
        records.forEach { record -> this.records[record.key.id] = record }
    }

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int =
        keys.count { key -> records.remove(key.id) != null }
}

private class BlockingViewModelCache(
    private val release: CompletableDeferred<Unit>,
) : TranslationCache {
    override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> {
        release.await()
        return emptyMap()
    }

    override suspend fun putAll(records: List<CachedTranslation>) = Unit

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int = 0
}

private class RecordingRollingProvider : TranslationProvider {
    override val id: String = "rolling-recording"
    val translatedPageIndexes = mutableSetOf<Int>()
    var requests: Int = 0

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        requests += 1
        request.segments.forEach { segment -> translatedPageIndexes += segment.pageIndex }
        return request.segments.map { segment ->
            TranslatedSegment(segment.id, "ko:${segment.text}")
        }
    }
}
