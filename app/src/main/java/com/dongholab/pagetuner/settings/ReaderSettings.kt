package com.dongholab.pagetuner.settings

import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind

data class ReaderSettings(
    val displayMode: DisplayMode = DisplayMode.EinkHighContrast,
    val pageTurnMode: PageTurnMode = PageTurnMode.LeftPreviousRightNext,
    val pdfFitMode: PdfFitMode = PdfFitMode.FitPage,
    val readerFontSizeSp: Int = 18,
    val readerLineSpacing: Float = 1.35f,
    val readerPageMarginDp: Int = 18,
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ko",
    val providerKind: TranslationProviderKind = TranslationProviderKind.GOOGLE_CLOUD,
    val llmEndpoint: String = "",
    val llmModel: String = "",
    val readingWordsPerMinute: Int = 210,
    val paceMode: TranslationPaceMode = TranslationPaceMode.READING,
)
