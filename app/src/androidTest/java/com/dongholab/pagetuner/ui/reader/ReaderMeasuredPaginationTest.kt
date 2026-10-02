package com.dongholab.pagetuner.ui.reader

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.reader.PdfFitMode
import com.dongholab.pagetuner.reader.ReaderDisplayNavigation
import com.dongholab.pagetuner.reader.ReaderDisplayPosition
import com.dongholab.pagetuner.translation.PageTranslation
import com.dongholab.pagetuner.translation.TranslatedSegment
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Executes real Compose line layout; compilation alone is not device/rendering evidence. */
class ReaderMeasuredPaginationTest {
    @get:Rule val rule = createComposeRule()
    private var position by mutableStateOf(ReaderDisplayPosition(0))
    private var margin by mutableStateOf(8)
    private var height by mutableStateOf(320)
    private var mode by mutableStateOf(TranslationDisplayMode.OriginalOnly)
    private var suppliedTranslation by mutableStateOf<PageTranslation?>(null)
    private var navigation = ReaderDisplayNavigation()

    @Test fun narrowReaderPreservesRequestedFontAndEveryCharacterAcrossCanonicalPages() {
        val document = document("처음 😀 한글 English ".repeat(9), "  둘째\n문단 😀 ".repeat(9), "마지막 글자.")
        render(document)
        val displayed = StringBuilder()
        repeat(200) {
            val layout = textLayout("reader-original-text")
            assertFalse(layout.hasVisualOverflow)
            assertEquals(28.sp, layout.layoutInput.style.fontSize)
            displayed.append(layout.layoutInput.text.text)
            val next = rule.runOnIdle { navigation.next }
            if (next == null) {
                assertEquals(document.pages.joinToString("\n\n") { it.plainText }, displayed.toString())
                return
            }
            rule.runOnIdle { position = next }
        }
        fail("Reader did not reach the last character")
    }

    @Test fun marginAndHeightChangesReflowFromTheSameSourceAnchor() {
        val document = document("읽던 위치 😀\n다음 문장. ".repeat(60))
        render(document)
        rule.runOnIdle { position = requireNotNull(navigation.next) }
        val anchor = rule.runOnIdle { position }
        val before = textLayout("reader-original-text").layoutInput.text.text
        rule.runOnIdle { margin = 32; height = 260 }
        val after = textLayout("reader-original-text")
        assertFalse(after.hasVisualOverflow)
        assertTrue(after.layoutInput.text.length < before.length)
        assertTrue(before.startsWith(after.layoutInput.text.text))
        rule.runOnIdle { assertEquals(anchor.pageIndex, position.pageIndex); assertEquals(anchor.characterOffset, position.characterOffset) }
    }

    @Test fun comparisonExposesEveryOriginalAndExpandedTranslationCharacter() {
        val document = document("Source sentence 😀 ".repeat(6))
        val translation = PageTranslation(document.pages[0], "en", "ko", listOf(TranslatedSegment("s0", "길어진 번역 😀 문장입니다. ".repeat(25))), false)
        mode = TranslationDisplayMode.SideBySide
        suppliedTranslation = translation
        render(document)
        val originals = StringBuilder(); val translations = StringBuilder()
        repeat(200) {
            val original = textLayout("reader-original-text"); val translated = textLayout("reader-translated-text")
            assertFalse(original.hasVisualOverflow); assertFalse(translated.hasVisualOverflow)
            originals.append(original.layoutInput.text.text); translations.append(translated.layoutInput.text.text)
            val next = rule.runOnIdle { navigation.next }
            if (next == null) {
                assertEquals(document.pages[0].plainText, originals.toString())
                assertEquals(translation.text, translations.toString())
                return
            }
            rule.runOnIdle { position = next }
        }
        fail("Comparison hid a continuation")
    }

    @Test fun lateTranslationKeepsSourceAnchorAndStartsSameUnalignedSegment() {
        val document = document("원문 긴 문단 😀 ".repeat(50))
        render(document)
        rule.runOnIdle { position = requireNotNull(navigation.next) }
        val anchor = rule.runOnIdle { position }
        val translated = "Same paragraph translation. 😀 ".repeat(40)
        rule.runOnIdle {
            mode = TranslationDisplayMode.TranslationOnly
            suppliedTranslation = PageTranslation(document.pages[0], "ko", "en", listOf(TranslatedSegment("s0", translated)), false)
        }
        val layout = textLayout("reader-translated-text")
        assertFalse(layout.hasVisualOverflow)
        assertTrue(translated.startsWith(layout.layoutInput.text.text))
        rule.runOnIdle { assertEquals(anchor.characterOffset, position.characterOffset); assertEquals(anchor.pageIndex, position.pageIndex) }
    }

    @Test fun scannedPdfKeepsPhysicalNavigationBeforeBitmapArrives() {
        val document = document("", "", "").copy(format = DocumentFormat.PDF)
        render(document)
        rule.runOnIdle { assertEquals(1, navigation.next?.pageIndex); assertNull(navigation.previous) }
    }

    @Test fun pdfComparisonOverlayLeavesOriginalVisible() {
        val document = document("Original").copy(format = DocumentFormat.PDF)
        mode = TranslationDisplayMode.SideBySide
        suppliedTranslation = PageTranslation(document.pages[0], "en", "ko", listOf(TranslatedSegment("s0", "번역")), false)
        render(document, Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888))
        val image = rule.onNodeWithTag("reader-pdf-original", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val panel = rule.onNodeWithTag("reader-translation-panel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(panel.top > image.top)
        assertTrue(panel.height < image.height)
    }

    private fun render(document: ReaderDocument, bitmap: Bitmap? = null) {
        rule.setContent { MaterialTheme { Box(Modifier.size(260.dp, height.dp)) {
            ReaderSurface(document.pages[position.pageIndex], document.format, bitmap, PdfFitMode.FitPage, DisplayMode.EinkHighContrast,
                suppliedTranslation, translationDisplayMode = mode, pageTurnMode = PageTurnMode.ButtonsOnly,
                pageTurningEnabled = true, fontSizeSp = 28, lineSpacing = 1.5f, pageMarginDp = margin,
                onPreviousPage = {}, onNextPage = {}, modifier = Modifier.fillMaxSize(), document = document,
                displayPosition = position, onDisplayNavigation = { navigation = it }, onDisplayPositionResolved = { position = it })
        } } }
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithTag(tag, useUnmergedTree = true).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
        return results.single()
    }
    private fun document(vararg texts: String) = ReaderDocument("document", "Book", DocumentFormat.TEXT,
        texts.mapIndexed { i, text -> ReaderPage(i, listOf(TextSegment("s$i", i, 0, text))) })
}
