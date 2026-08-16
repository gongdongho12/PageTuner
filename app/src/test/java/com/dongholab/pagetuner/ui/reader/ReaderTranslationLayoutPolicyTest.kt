package com.dongholab.pagetuner.ui.reader

import com.dongholab.pagetuner.settings.ReaderSettings
import com.dongholab.pagetuner.translation.TranslationDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderTranslationLayoutPolicyTest {
    @Test
    fun translationOnlyIsTheReaderDefaultAndUsesTheWholeSurface() {
        assertEquals(TranslationDisplayMode.TranslationOnly, ReaderSettings().translationDisplayMode)

        val layout = readerTranslationLayout(true, TranslationDisplayMode.TranslationOnly)

        assertFalse(layout.showOriginal)
        assertTrue(layout.showTranslation)
        assertEquals(1f, layout.translationFraction)
        assertFalse(layout.showTranslationLabel)
    }

    @Test
    fun comparisonStillPrioritizesTranslationAndUsesOnlyACompactLabel() {
        val layout = readerTranslationLayout(true, TranslationDisplayMode.SideBySide)

        assertTrue(layout.showOriginal)
        assertTrue(layout.showTranslation)
        assertEquals(0.35f, layout.originalFraction)
        assertEquals(0.65f, layout.translationFraction)
        assertTrue(layout.showTranslationLabel)
    }

    @Test
    fun missingTranslationAlwaysKeepsOriginalReadable() {
        val layout = readerTranslationLayout(false, TranslationDisplayMode.TranslationOnly)

        assertTrue(layout.showOriginal)
        assertFalse(layout.showTranslation)
        assertEquals(1f, layout.originalFraction)
    }
}
