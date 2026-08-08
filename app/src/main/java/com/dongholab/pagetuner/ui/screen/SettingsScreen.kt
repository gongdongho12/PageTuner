package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationUiState
import com.dongholab.pagetuner.ui.LanguagePreset
import com.dongholab.pagetuner.ui.reader.DiagnosticLogPanel
import com.dongholab.pagetuner.ui.settings.DisplaySettingsPanel
import com.dongholab.pagetuner.ui.settings.PageTurnSettingsPanel
import com.dongholab.pagetuner.ui.settings.ReaderPreferencesPanel
import com.dongholab.pagetuner.ui.translation.TranslationControls
import kotlin.math.roundToInt

/**
 * Settings 탭 전체를 하나의 Composable로 묶습니다.
 * MainActivity의 Settings when 브랜치 (90줄 인라인) → 이 함수 한 줄 호출로 대체됩니다.
 */
@Composable
fun SettingsScreen(
    readerSettings: ReaderSettings,
    translationState: TranslationUiState,
    providerKind: TranslationProviderKind,
    apiKey: String,
    busy: Boolean,
    canTranslate: Boolean,
    canRetryTranslation: Boolean,
    canClearCache: Boolean,
    providerStatusText: String,
    providerHealthText: String,
    translationCacheStatusText: String,
    translationQueueStatusText: String,
    // Settings change callbacks
    onDisplayModeChange: (DisplayMode) -> Unit,
    onPageTurnModeChange: (PageTurnMode) -> Unit,
    onPdfFitModeChange: (PdfFitMode) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onLineSpacingChange: (Float) -> Unit,
    onPageMarginChange: (Int) -> Unit,
    onProviderKindChange: (TranslationProviderKind) -> Unit,
    onApiKeyChange: (String) -> Unit,
    onLlmEndpointChange: (String) -> Unit,
    onLlmModelChange: (String) -> Unit,
    onSourceLanguageChange: (String) -> Unit,
    onTargetLanguageChange: (String) -> Unit,
    onReadingWpmChange: (Float) -> Unit,
    onBatchSizeChange: (Float) -> Unit,
    onPaceModeChange: (TranslationPaceMode) -> Unit,
    onTranslationDisplayModeChange: (TranslationDisplayMode) -> Unit,
    onLanguagePreset: (LanguagePreset) -> Unit,
    onCheckProvider: () -> Unit,
    onTranslate: () -> Unit,
    onRetryTranslation: () -> Unit,
    onPrefetch: () -> Unit,
    onPausePrefetch: () -> Unit,
    onResumePrefetch: () -> Unit,
    onCancelPrefetch: () -> Unit,
    onRetryPrefetch: () -> Unit,
    onLoadCached: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        DisplaySettingsPanel(
            displayMode = readerSettings.displayMode,
            busy = busy,
            onDisplayModeChange = onDisplayModeChange,
        )
        PageTurnSettingsPanel(
            pageTurnMode = readerSettings.pageTurnMode,
            busy = busy,
            onPageTurnModeChange = onPageTurnModeChange,
        )
        ReaderPreferencesPanel(
            pdfFitMode = readerSettings.pdfFitMode,
            fontSizeSp = readerSettings.readerFontSizeSp,
            lineSpacing = readerSettings.readerLineSpacing,
            pageMarginDp = readerSettings.readerPageMarginDp,
            busy = busy,
            onPdfFitModeChange = onPdfFitModeChange,
            onFontSizeChange = onFontSizeChange,
            onLineSpacingChange = onLineSpacingChange,
            onPageMarginChange = onPageMarginChange,
        )
        TranslationControls(
            providerKind = providerKind,
            onProviderKindChange = onProviderKindChange,
            apiKey = apiKey,
            onApiKeyChange = onApiKeyChange,
            llmEndpoint = readerSettings.llmEndpoint,
            onLlmEndpointChange = onLlmEndpointChange,
            llmModel = readerSettings.llmModel,
            onLlmModelChange = onLlmModelChange,
            sourceLanguage = readerSettings.sourceLanguage,
            onSourceLanguageChange = onSourceLanguageChange,
            targetLanguage = readerSettings.targetLanguage,
            onTargetLanguageChange = onTargetLanguageChange,
            readingWpm = readerSettings.readingWordsPerMinute.toFloat(),
            onReadingWpmChange = { onReadingWpmChange(it) },
            batchSize = readerSettings.translationBatchSize.toFloat(),
            onBatchSizeChange = { onBatchSizeChange(it) },
            paceMode = readerSettings.paceMode,
            onPaceModeChange = onPaceModeChange,
            translationDisplayMode = readerSettings.translationDisplayMode,
            onTranslationDisplayModeChange = onTranslationDisplayModeChange,
            providerStatusText = providerStatusText,
            providerHealthText = providerHealthText,
            translationCacheStatusText = translationCacheStatusText,
            translationQueueStatusText = translationQueueStatusText,
            busy = busy,
            canTranslate = canTranslate,
            canRetryTranslation = canRetryTranslation,
            canClearCache = canClearCache,
            canPausePrefetch = translationState.queue.canPause,
            canResumePrefetch = translationState.queue.canResume,
            canCancelPrefetch = translationState.queue.canCancel,
            canRetryPrefetch = translationState.queue.canRetry,
            onLanguagePreset = onLanguagePreset,
            onCheckProvider = onCheckProvider,
            onTranslate = onTranslate,
            onRetryTranslation = onRetryTranslation,
            onPrefetch = onPrefetch,
            onPausePrefetch = onPausePrefetch,
            onResumePrefetch = onResumePrefetch,
            onCancelPrefetch = onCancelPrefetch,
            onRetryPrefetch = onRetryPrefetch,
            onLoadCached = onLoadCached,
            onClearCache = onClearCache,
        )
        DiagnosticLogPanel()
    }
}
