package com.dongholab.pagetuner.source

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
class OfflineBookDownloadCoordinatorTest {
    private val result = BatchDownloadResult(1, 1, 0, 0, "ko")

    @Test
    fun progressIsDrainedBeforeCompletion() = runTest {
        val updates = mutableListOf<OfflineDownloadUpdate>()
        val coordinator = OfflineBookDownloadCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start({ progress ->
            progress(BatchDownloadProgress(1, 1, "Book", stage = BatchDownloadStage.Completed, isCompleted = true))
            result
        }, updates::add)
        advanceUntilIdle()
        assertEquals(OfflineDownloadUpdate.Started, updates.first())
        assertTrue(updates[1] is OfflineDownloadUpdate.Progress)
        assertEquals(result, (updates.last() as OfflineDownloadUpdate.Completed).result)
        coordinator.cancel()
        assertEquals(3, updates.size)
    }

    @Test
    fun cancellationAndFailureHaveDistinctTerminalStates() = runTest {
        val updates = mutableListOf<OfflineDownloadUpdate>()
        val coordinator = OfflineBookDownloadCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start({ awaitCancellation() }, updates::add)
        runCurrent()
        coordinator.cancel()
        advanceUntilIdle()
        assertEquals(OfflineDownloadUpdate.Cancelled, updates.last())
        coordinator.start({ throw IOException("offline") }, updates::add)
        advanceUntilIdle()
        assertTrue(updates.last() is OfflineDownloadUpdate.Failed)
    }

    @Test
    fun oldDownloadCannotOverwriteNewRequestAfterCancellation() = runTest {
        val release = CompletableDeferred<Unit>()
        val old = mutableListOf<OfflineDownloadUpdate>()
        val current = mutableListOf<OfflineDownloadUpdate>()
        val coordinator = OfflineBookDownloadCoordinator(this, StandardTestDispatcher(testScheduler))
        coordinator.start({ progress ->
            withContext(NonCancellable) { release.await(); progress(BatchDownloadProgress(1, 1, "old")) }
            result
        }, old::add)
        runCurrent()
        coordinator.start({ result }, current::add)
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(OfflineDownloadUpdate.Started, OfflineDownloadUpdate.Cancelled), old)
        assertEquals(2, current.size)
        assertTrue(current.last() is OfflineDownloadUpdate.Completed)
    }
}
