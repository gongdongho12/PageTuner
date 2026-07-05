package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TranslationViewModelTest {
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
}
