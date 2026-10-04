package com.dongholab.pagetuner.ui.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationUiState
import com.dongholab.pagetuner.ui.LanguagePreset
import com.dongholab.pagetuner.ui.reader.DiagnosticLogPanel
import com.dongholab.pagetuner.ui.settings.DisplaySettingsPanel
import com.dongholab.pagetuner.ui.settings.ListLayoutSettingsPanel
import com.dongholab.pagetuner.ui.settings.PageTurnSettingsPanel
import com.dongholab.pagetuner.ui.settings.ReaderPreferencesPanel
import com.dongholab.pagetuner.ui.settings.ServerReaderPreferencesPanel
import com.dongholab.pagetuner.translation.sync.ReaderPreferencesUiState
import com.dongholab.pagetuner.translation.sync.ServerReaderPreferencesSync
import androidx.compose.ui.res.stringResource
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.translation.TranslationControls
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import kotlin.math.roundToInt

private enum class SettingsCategoryTab(val title: String) {
    DISPLAY_PAGING("Display & Page Turn"),
    READER_DEFAULTS("Reader Preferences"),
    AI_TRANSLATION("AI Translation"),
    DIAGNOSTICS("Diagnostics Log"),
    ACCOUNT_READER("Account reader settings"),
    LOCAL_SHARING("Device sharing"),
}

/**
 * Settings Screen structured with E-Ink Discrete Sub-Tab Pagination.
 * Eliminates screen overflow and prevents vertical drag-scrolling.
 */
@Composable
fun SettingsScreen(
    readerSettings: ReaderSettings,
    readerPreferencesState: ReaderPreferencesUiState,
    readerPreferencesSync: ServerReaderPreferencesSync,
    translationState: TranslationUiState,
    providerKind: TranslationProviderKind,
    apiKey: String,
    usesLocalDeepSeekSecret: Boolean,
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
    onListLayoutModeChange: (ListLayoutMode) -> Unit,
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
    var selectedCategory by remember { mutableStateOf(SettingsCategoryTab.DISPLAY_PAGING) }
    val accountReaderTitle = stringResource(R.string.reader_preferences_sync_title)
    val sharingTitle = stringResource(R.string.sharing_title)

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // E-Ink Sub-Tab Navigation Bar with Active Tab Indicator
        EinkSegmentedControl(
            options = SettingsCategoryTab.entries,
            selected = selectedCategory,
            onSelect = { selectedCategory = it },
            enabled = !busy,
            itemHeight = 54.dp,
            label = { when (it) {
                SettingsCategoryTab.ACCOUNT_READER -> accountReaderTitle
                SettingsCategoryTab.LOCAL_SHARING -> sharingTitle
                else -> it.title
            } },
        )

        // Active Category Panel Rendering (Discrete Non-Overflowing View)
        when (selectedCategory) {
            SettingsCategoryTab.LOCAL_SHARING -> Box(Modifier.weight(1f)) {
                com.dongholab.pagetuner.sharing.LocalSharingPanel()
            }
            SettingsCategoryTab.ACCOUNT_READER -> Box(Modifier.weight(1f)) {
                ServerReaderPreferencesPanel(readerPreferencesState, readerPreferencesSync)
            }
            SettingsCategoryTab.DISPLAY_PAGING -> {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DisplaySettingsPanel(
                        displayMode = readerSettings.displayMode,
                        busy = busy,
                        onDisplayModeChange = onDisplayModeChange,
                    )
                    ListLayoutSettingsPanel(
                        listLayoutMode = readerSettings.listLayoutMode,
                        busy = busy,
                        onListLayoutModeChange = onListLayoutModeChange,
                    )
                    PageTurnSettingsPanel(
                        pageTurnMode = readerSettings.pageTurnMode,
                        busy = busy,
                        onPageTurnModeChange = onPageTurnModeChange,
                    )
                }
            }
            SettingsCategoryTab.READER_DEFAULTS -> {
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
            }
            SettingsCategoryTab.AI_TRANSLATION -> {
                TranslationControls(
                    providerKind = providerKind,
                    onProviderKindChange = onProviderKindChange,
                    apiKey = apiKey,
                    usesLocalDeepSeekSecret = usesLocalDeepSeekSecret,
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
            }
            SettingsCategoryTab.DIAGNOSTICS -> {
                DiagnosticLogPanel()
            }
        }
    }
}
