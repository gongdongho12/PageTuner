package com.dongholab.pagetuner.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settingsStore: ReaderSettingsStore,
) : ViewModel() {
    val settings: StateFlow<ReaderSettings> = settingsStore.settings.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReaderSettings(),
    )

    fun updateDisplayMode(displayMode: DisplayMode) {
        update { settingsStore.updateDisplayMode(displayMode) }
    }

    fun updatePageTurnMode(pageTurnMode: PageTurnMode) {
        update { settingsStore.updatePageTurnMode(pageTurnMode) }
    }

    fun updatePdfFitMode(pdfFitMode: PdfFitMode) {
        update { settingsStore.updatePdfFitMode(pdfFitMode) }
    }

    fun updateReaderFontSize(fontSizeSp: Int) {
        update { settingsStore.updateReaderFontSize(fontSizeSp) }
    }

    fun updateReaderLineSpacing(lineSpacing: Float) {
        update { settingsStore.updateReaderLineSpacing(lineSpacing) }
    }

    fun updateReaderPageMargin(pageMarginDp: Int) {
        update { settingsStore.updateReaderPageMargin(pageMarginDp) }
    }

    fun updateSourceLanguage(sourceLanguage: String) {
        update { settingsStore.updateSourceLanguage(sourceLanguage) }
    }

    fun updateTargetLanguage(targetLanguage: String) {
        update { settingsStore.updateTargetLanguage(targetLanguage) }
    }

    fun updateLanguages(sourceLanguage: String, targetLanguage: String) {
        update { settingsStore.updateLanguages(sourceLanguage, targetLanguage) }
    }

    fun updateProviderKind(providerKind: TranslationProviderKind) {
        update { settingsStore.updateProviderKind(providerKind) }
    }

    fun updateLlmEndpoint(llmEndpoint: String) {
        update { settingsStore.updateLlmEndpoint(llmEndpoint) }
    }

    fun updateLlmModel(llmModel: String) {
        update { settingsStore.updateLlmModel(llmModel) }
    }

    fun updateReadingWordsPerMinute(readingWordsPerMinute: Int) {
        update { settingsStore.updateReadingWordsPerMinute(readingWordsPerMinute) }
    }

    fun updateTranslationBatchSize(batchSize: Int) {
        update { settingsStore.updateTranslationBatchSize(batchSize) }
    }

    fun updatePaceMode(paceMode: TranslationPaceMode) {
        update { settingsStore.updatePaceMode(paceMode) }
    }

    fun updateTranslationDisplayMode(displayMode: TranslationDisplayMode) {
        update { settingsStore.updateTranslationDisplayMode(displayMode) }
    }

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch {
            block()
        }
    }

    class Factory(
        private val settingsStore: ReaderSettingsStore,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
                return SettingsViewModel(settingsStore) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
