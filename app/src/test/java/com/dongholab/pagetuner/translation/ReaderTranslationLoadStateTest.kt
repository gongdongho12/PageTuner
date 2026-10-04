package com.dongholab.pagetuner.translation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTranslationLoadStateTest {
    @Test
    fun cacheQueueAndTranslationStagesRemainLoading() {
        listOf(
            ReaderTranslationLoadStage.CheckingCache,
            ReaderTranslationLoadStage.Queued,
            ReaderTranslationLoadStage.Translating,
        ).forEach { stage ->
            assertTrue(ReaderTranslationLoadState("book", 0, stage).isLoading)
        }
    }

    @Test
    fun terminalStagesStopLoadingAndIdentityMustMatchCurrentPage() {
        val ready = ReaderTranslationLoadState("book", 4, ReaderTranslationLoadStage.Ready)

        assertFalse(ready.isLoading)
        assertTrue(ready.matches("book", 4))
        assertFalse(ready.matches("book", 5))
    }
}
