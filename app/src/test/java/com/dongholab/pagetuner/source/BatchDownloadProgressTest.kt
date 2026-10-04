package com.dongholab.pagetuner.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatchDownloadProgressTest {
    @Test
    fun translationPartsAdvanceWithinCurrentChapter() {
        val early = BatchDownloadProgress(
            currentItemIndex = 2,
            totalItems = 4,
            currentTitle = "Chapter 2",
            stage = BatchDownloadStage.Translating,
            translatedPart = 1,
            totalTranslationParts = 4,
        )
        val late = early.copy(translatedPart = 4)

        assertTrue(late.fraction > early.fraction)
        assertTrue(late.fraction < 0.75f)
    }

    @Test
    fun completedEmptyQueueReportsCompleteFraction() {
        val progress = BatchDownloadProgress(
            currentItemIndex = 0,
            totalItems = 0,
            currentTitle = "Ready",
            stage = BatchDownloadStage.Completed,
            isCompleted = true,
        )

        assertEquals(1f, progress.fraction)
    }
}
