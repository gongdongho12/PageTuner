package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationModelsTest {
    @Test
    fun checksGoogleProviderConfiguration() {
        val missing = TranslationSettings(
            providerKind = TranslationProviderKind.GOOGLE_CLOUD,
            apiKey = "",
        ).checkProviderHealth()

        val ready = TranslationSettings(
            providerKind = TranslationProviderKind.GOOGLE_CLOUD,
            apiKey = "key",
        ).checkProviderHealth()

        assertEquals(ProviderHealthState.MissingConfiguration, missing.state)
        assertEquals(ProviderHealthState.Ready, ready.state)
    }

    @Test
    fun checksGoogleWebTranslateProviderConfiguration() {
        val missing = TranslationSettings(
            providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
            apiKey = "",
        ).checkProviderHealth()

        val ready = TranslationSettings(
            providerKind = TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML,
            apiKey = "key",
        ).checkProviderHealth()

        assertEquals(ProviderHealthState.MissingConfiguration, missing.state)
        assertEquals(ProviderHealthState.Ready, ready.state)
    }

    @Test
    fun validatesLlmEndpointShape() {
        val invalid = TranslationSettings(
            providerKind = TranslationProviderKind.OPENAI_COMPATIBLE_LLM,
            apiKey = "key",
            llmEndpoint = "localhost:11434",
            llmModel = "model",
        ).checkProviderHealth()

        val ready = TranslationSettings(
            providerKind = TranslationProviderKind.OPENAI_COMPATIBLE_LLM,
            apiKey = "key",
            llmEndpoint = "https://example.com/v1",
            llmModel = "model",
        ).checkProviderHealth()

        assertEquals(ProviderHealthState.InvalidConfiguration, invalid.state)
        assertEquals(ProviderHealthState.Ready, ready.state)
    }

    @Test
    fun reportsTranslationQueueCapabilities() {
        val running = TranslationQueueState(
            items = listOf(
                TranslationQueueItem(0, 1, TranslationQueueItemStatus.Saved),
                TranslationQueueItem(1, 2, TranslationQueueItemStatus.Active),
            ),
            running = true,
        )
        val failed = TranslationQueueState(
            items = listOf(TranslationQueueItem(0, 1, TranslationQueueItemStatus.Failed)),
        )

        assertEquals(1, running.completedPages)
        assertEquals(2, running.activePageNumber)
        assertTrue(running.canPause)
        assertFalse(running.canRetry)
        assertTrue(failed.canRetry)
    }
}
