package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.translation.TranslationDisplayMode

internal data class ReaderTranslationLayout(
    val showOriginal: Boolean,
    val showTranslation: Boolean,
    val originalFraction: Float,
    val translationFraction: Float,
    val showTranslationLabel: Boolean,
)

/** Keeps translated text as the primary reading surface, including cache restores. */
internal fun readerTranslationLayout(
    hasTranslation: Boolean,
    displayMode: TranslationDisplayMode,
): ReaderTranslationLayout {
    val showOriginal = !hasTranslation || displayMode != TranslationDisplayMode.TranslationOnly
    val showTranslation = hasTranslation && displayMode != TranslationDisplayMode.OriginalOnly
    val comparing = showOriginal && showTranslation
    return ReaderTranslationLayout(
        showOriginal = showOriginal,
        showTranslation = showTranslation,
        originalFraction = if (comparing) 0.35f else if (showOriginal) 1f else 0f,
        translationFraction = if (comparing) 0.65f else if (showTranslation) 1f else 0f,
        showTranslationLabel = comparing,
    )
}
