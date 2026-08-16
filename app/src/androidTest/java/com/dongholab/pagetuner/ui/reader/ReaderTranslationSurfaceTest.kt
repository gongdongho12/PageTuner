package com.dongholab.pagetuner.ui.reader

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import org.junit.Rule
import org.junit.Test

class ReaderTranslationSurfaceTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun translationOnlyShowsBodyAsMainContentWithoutSavedTranslationHeading() {
        composeRule.setContent {
            MaterialTheme {
                ReaderSurface(
                    page = page,
                    documentFormat = DocumentFormat.TEXT,
                    pdfPageBitmap = null,
                    pdfFitMode = PdfFitMode.FitPage,
                    displayMode = DisplayMode.EinkHighContrast,
                    translation = translation,
                    translationDisplayMode = TranslationDisplayMode.TranslationOnly,
                    pageTurnMode = PageTurnMode.ButtonsOnly,
                    pageTurningEnabled = false,
                    fontSizeSp = 18,
                    lineSpacing = 1.35f,
                    pageMarginDp = 18,
                    onPreviousPage = {},
                    onNextPage = {},
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        composeRule.onAllNodesWithText("Saved Translation").assertCountEquals(0)
        composeRule.onNodeWithText(TranslatedBody).assertIsDisplayed()
        composeRule.onAllNodesWithText(OriginalBody).assertCountEquals(0)
    }

    private companion object {
        const val OriginalBody = "A-Pu opened the door."
        const val TranslatedBody = "아푸가 문을 열었다."
        val page = ReaderPage(
            index = 0,
            segments = listOf(TextSegment("original", 0, 0, OriginalBody)),
        )
        val translation = PageTranslation(
            page = page,
            sourceLanguage = "en",
            targetLanguage = "ko",
            segments = listOf(TranslatedSegment("original", TranslatedBody)),
            completedFromCache = true,
        )
    }
}
