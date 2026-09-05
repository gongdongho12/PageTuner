package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationSettings
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogTranslationCoordinatorTest {
    private val settings = TranslationSettings(
        providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML, apiKey = "", targetLanguage = "ko",
    )
    private val book = RemoteBookItem(
        identity = RemoteBookIdentity(RemoteSourceType.WebNovel, "site", "book-1"),
        title = "Original title", format = DocumentFormat.TEXT, downloadUrl = "https://example.test/book/1",
    )
    private val translated = mapOf(book.translationKey() to CatalogItemTranslation("번역 제목", null, "ko"))

    @Test
    fun emitsProgressAndResultAndUsesOfflinePace() = runTest {
        val updates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        val service = service { items, options, progress ->
            assertEquals(listOf(book), items)
            assertEquals(TranslationPaceMode.OFFLINE_PREFETCH, options.paceMode)
            progress(TranslationProgress(1, 1, "translated", "title"))
            translated
        }
        assertTrue(coordinator.start(listOf(book), settings, { service }, updates::add))
        advanceUntilIdle()
        assertEquals(0, (updates.first() as CatalogTranslationUpdate.Running).progress.completedItems)
        assertEquals(1, (updates[1] as CatalogTranslationUpdate.Running).progress.completedItems)
        assertEquals(translated, (updates.last() as CatalogTranslationUpdate.Completed).translations)
        coordinator.cancel()
        assertEquals(3, updates.size) // Finished work is not cancelled again.
    }

    @Test
    fun factoryFailureIsReportedInsteadOfLeavingLoadingActive() = runTest {
        val updates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        val failure = IllegalStateException("provider setup failed")
        coordinator.start(listOf(book), settings, { throw failure }, updates::add)
        advanceUntilIdle()
        val reported = (updates.last() as CatalogTranslationUpdate.Failed).error
        // Coroutine stack-trace recovery may copy the exception across dispatchers.
        assertEquals(failure.javaClass, reported.javaClass)
        assertEquals(failure.message, reported.message)
        assertEquals(2, updates.size)
    }

    @Test
    fun providerFailureIsReportedAndAnotherRequestCanSucceed() = runTest {
        val updates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start(listOf(book), settings, { service { _, _, _ -> throw IOException("offline") } }, updates::add)
        advanceUntilIdle()
        assertTrue(updates.last() is CatalogTranslationUpdate.Failed)
        coordinator.start(listOf(book), settings, { service { _, _, _ -> translated } }, updates::add)
        advanceUntilIdle()
        assertTrue(updates.last() is CatalogTranslationUpdate.Completed)
    }

    @Test
    fun cancellationEmitsOnceAndNeverBecomesAnError() = runTest {
        val updates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start(listOf(book), settings, { service { _, _, _ -> awaitCancellation() } }, updates::add)
        runCurrent()
        coordinator.cancel()
        coordinator.cancel()
        advanceUntilIdle()
        assertEquals(listOf(updates.first(), CatalogTranslationUpdate.Cancelled), updates)
        assertFalse(updates.any { it is CatalogTranslationUpdate.Failed })
    }

    @Test
    fun replacedRequestCannotPublishLateResultsOrClearNewLoading() = runTest {
        val oldRelease = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        val oldUpdates = mutableListOf<CatalogTranslationUpdate>()
        val newUpdates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start(listOf(book), settings, { service { _, _, _ ->
            withContext(NonCancellable) { oldRelease.await() }
            translated
        } }, oldUpdates::add)
        runCurrent()
        coordinator.start(listOf(book), settings, { service { _, _, _ ->
            newRelease.await()
            translated
        } }, newUpdates::add)
        runCurrent()
        oldRelease.complete(Unit)
        runCurrent()
        assertEquals(2, oldUpdates.size)
        assertEquals(CatalogTranslationUpdate.Cancelled, oldUpdates.last())
        assertEquals(1, newUpdates.size)
        assertTrue(newUpdates.single() is CatalogTranslationUpdate.Running)
        newRelease.complete(Unit)
        advanceUntilIdle()
        assertTrue(newUpdates.last() is CatalogTranslationUpdate.Completed)
    }

    @Test
    fun emptyOrUnconfiguredRequestsDoNotCreateAService() = runTest {
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        val factory = { error("should not be called") }
        val updates = mutableListOf<CatalogTranslationUpdate>()
        assertFalse(coordinator.start(emptyList(), settings, factory, updates::add))
        assertFalse(coordinator.start(listOf(book), settings.copy(providerKind = TranslationProviderKind.GOOGLE_CLOUD), factory, updates::add))
        advanceUntilIdle()
        assertTrue(updates.isEmpty())
    }

    @Test
    fun snapshotsItemsAndClampsProviderProgress() = runTest {
        val items = mutableListOf(book)
        val updates = mutableListOf<CatalogTranslationUpdate>()
        val coordinator = CatalogTranslationCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start(items, settings, { service { snapshot, _, progress ->
            assertEquals(1, snapshot.size)
            progress(TranslationProgress(-5, 1, "bad", ""))
            progress(TranslationProgress(50, 1, "bad", ""))
            translated
        } }, updates::add)
        items.clear()
        advanceUntilIdle()
        assertEquals(listOf(0, 0, 1), updates.filterIsInstance<CatalogTranslationUpdate.Running>().map { it.progress.completedItems })
    }

    private fun service(
        block: suspend (List<RemoteBookItem>, TranslationSettings, suspend (TranslationProgress) -> Unit) -> Map<String, CatalogItemTranslation>,
    ) = object : RemoteCatalogTranslationService {
        override suspend fun translate(
            items: List<RemoteBookItem>, settings: TranslationSettings,
            onProgress: suspend (TranslationProgress) -> Unit,
        ): Map<String, CatalogItemTranslation> = block(items, settings, onProgress)
    }
}
