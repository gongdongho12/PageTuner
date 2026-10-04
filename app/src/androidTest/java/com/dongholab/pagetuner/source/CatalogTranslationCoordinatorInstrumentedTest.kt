package com.dongholab.pagetuner.source

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android dispatchers; fixture translation, not a live external translation test. */
@RunWith(AndroidJUnit4::class)
class CatalogTranslationCoordinatorInstrumentedTest {
    @Test
    fun translationRunsOffMainAndUpdatesReturnToMain() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val finished = CompletableDeferred<CatalogTranslationUpdate.Completed>()
            val updateThreads = mutableListOf<Boolean>()
            val coordinator = CatalogTranslationCoordinator(scope)
            val item = RemoteBookItem(
                identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "fixture", "book-1"),
                title = "Original", format = DocumentFormat.TEXT,
                downloadUrl = "https://example.test/book/1",
            )
            val settings = TranslationSettings(
                providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML, apiKey = "",
            )
            withContext(Dispatchers.Main.immediate) {
                coordinator.start(listOf(item), settings, createService = {
                    check(Looper.myLooper() != Looper.getMainLooper()) { "Factory ran on Main" }
                    object : RemoteCatalogTranslationService {
                        override suspend fun translate(
                            items: List<RemoteBookItem>, settings: TranslationSettings,
                            onProgress: suspend (TranslationProgress) -> Unit,
                        ): Map<String, CatalogItemTranslation> {
                            check(Looper.myLooper() != Looper.getMainLooper()) { "Translation ran on Main" }
                            onProgress(TranslationProgress(1, 1, "done", "title"))
                            return mapOf(item.translationKey() to CatalogItemTranslation("번역 제목", null, "ko"))
                        }
                    }
                }, onUpdate = { update ->
                    updateThreads += Looper.myLooper() == Looper.getMainLooper()
                    when (update) {
                        is CatalogTranslationUpdate.Completed -> finished.complete(update)
                        is CatalogTranslationUpdate.Failed -> finished.completeExceptionally(update.error)
                        else -> Unit
                    }
                })
            }
            val result = withTimeout(10_000) { finished.await() }
            assertEquals("번역 제목", result.translations.getValue(item.translationKey()).title)
            withContext(Dispatchers.Main.immediate) {
                assertEquals(3, updateThreads.size)
                assertTrue(updateThreads.all { it })
                coordinator.cancel()
                assertFalse(updateThreads.size > 3)
            }
        } finally {
            scope.cancel()
        }
    }
}
