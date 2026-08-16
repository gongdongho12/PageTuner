package com.dongholab.pagetuner.translation

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level verification of the 10-page rolling translation workflow. */
@RunWith(AndroidJUnit4::class)
class RollingTranslationWorkflowInstrumentedTest {
    @Test
    fun fifthVisiblePageQueuesTheNextTenExactlyOnce() {
        runBlocking {
            val provider = DeviceRecordingProvider()
            val repository = TranslationRepository(provider, DeviceMemoryCache())
            val document = workflowDocument(pageCount = 25)
            val settings = TranslationSettings(
                providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
                apiKey = "",
                sourceLanguage = "en",
                targetLanguage = "ko",
                paceMode = TranslationPaceMode.OFFLINE_PREFETCH,
            )
            val viewModel = TranslationViewModel()

            viewModel.startRollingPrefetch(document, currentPageIndex = 0, settings, repository)
            awaitReady(viewModel, expectedReadyPageIndex = 9)
            assertEquals(1, provider.requestCount.get())
            assertEquals((0 until 10).toSet(), provider.translatedPageIndexes.toSet())
            Log.i(Tag, "STEP_1 initial-window pages=1-10 requests=1")

            viewModel.onReaderPageChanged(document, currentPageIndex = 3, settings, repository)
            delay(NoRequestObservationMillis)
            assertEquals(1, provider.requestCount.get())
            assertFalse(viewModel.uiState.value.rolling.pageFlags.containsKey(10))
            Log.i(Tag, "STEP_2 visible-page=4 next-window-requested=false")

            viewModel.onReaderPageChanged(document, currentPageIndex = 4, settings, repository)
            awaitReady(viewModel, expectedReadyPageIndex = 19)
            assertEquals(2, provider.requestCount.get())
            assertEquals((0 until 20).toSet(), provider.translatedPageIndexes.toSet())
            Log.i(Tag, "STEP_3 visible-page=5 next-window=11-20 requests=2")

            repeat(3) {
                viewModel.onReaderPageChanged(document, currentPageIndex = 4, settings, repository)
            }
            delay(NoRequestObservationMillis)
            assertEquals(2, provider.requestCount.get())
            assertTrue((0 until 20).all {
                viewModel.uiState.value.rolling.flagFor(it) == TranslationPageFlag.Ready
            })
            Log.i(Tag, "STEP_4 recompositions=3 duplicate-requests=0 ready-pages=20")
        }
    }

    private suspend fun awaitReady(viewModel: TranslationViewModel, expectedReadyPageIndex: Int) {
        withTimeout(WaitTimeoutMillis) {
            while (viewModel.uiState.value.rolling.flagFor(expectedReadyPageIndex) != TranslationPageFlag.Ready) {
                delay(PollMillis)
            }
        }
    }

    private fun workflowDocument(pageCount: Int): ReaderDocument = ReaderDocument(
        id = "device-rolling-workflow",
        title = "Rolling translation workflow",
        format = DocumentFormat.TEXT,
        pages = (0 until pageCount).map { pageIndex ->
            ReaderPage(
                index = pageIndex,
                segments = listOf(
                    TextSegment(
                        id = "device-segment-$pageIndex",
                        pageIndex = pageIndex,
                        indexInPage = 0,
                        text = "Reader workflow page ${pageIndex + 1}",
                    ),
                ),
            )
        },
    )

    private companion object {
        const val Tag = "PageTurnerWorkflowTest"
        const val WaitTimeoutMillis = 10_000L
        const val PollMillis = 25L
        const val NoRequestObservationMillis = 200L
    }
}

private class DeviceRecordingProvider : TranslationProvider {
    override val id: String = "device-recording"
    val requestCount = AtomicInteger(0)
    val translatedPageIndexes = mutableListOf<Int>()

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        requestCount.incrementAndGet()
        synchronized(translatedPageIndexes) {
            translatedPageIndexes += request.segments.map(TextSegment::pageIndex)
        }
        return request.segments.map { segment ->
            TranslatedSegment(segment.id, "ko:${segment.text}")
        }
    }
}

private class DeviceMemoryCache : TranslationCache {
    private val records = mutableMapOf<String, CachedTranslation>()

    override suspend fun getMany(keys: List<TranslationCacheKey>): Map<String, CachedTranslation> =
        synchronized(records) {
            keys.mapNotNull { key -> records[key.id]?.let { key.id to it } }.toMap()
        }

    override suspend fun putAll(records: List<CachedTranslation>) {
        synchronized(this.records) {
            records.forEach { record -> this.records[record.key.id] = record }
        }
    }

    override suspend fun deleteMany(keys: List<TranslationCacheKey>): Int = synchronized(records) {
        keys.count { key -> records.remove(key.id) != null }
    }
}
