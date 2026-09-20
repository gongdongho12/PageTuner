package com.dongholab.pagetuner.settings

import androidx.compose.ui.text.font.FontFamily
import com.dongholab.pagetuner.ui.reader.toComposeFontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ReaderTypographySettingsTest {

    @Test
    fun defaultSettings_haveExpectedTypographyDefaults() {
        val settings = ReaderSettings()
        assertEquals(18, settings.readerFontSizeSp)
        assertEquals(1.35f, settings.readerLineSpacing, 0.001f)
        assertEquals(18, settings.readerPageMarginDp)
        assertEquals(ReaderFontFamily.DEFAULT, settings.readerFontFamily)
    }

    @Test
    fun readerFontFamily_mapsToCorrectComposeFontFamily() {
        assertEquals(FontFamily.Default, ReaderFontFamily.DEFAULT.toComposeFontFamily())
        assertEquals(FontFamily.Serif, ReaderFontFamily.SERIF.toComposeFontFamily())
        assertEquals(FontFamily.SansSerif, ReaderFontFamily.SANS_SERIF.toComposeFontFamily())
        assertEquals(FontFamily.Monospace, ReaderFontFamily.MONOSPACE.toComposeFontFamily())
    }

    @Test
    fun readerFontFamily_allEntriesHaveValidLabels() {
        for (family in ReaderFontFamily.entries) {
            assertNotEquals(0, family.labelRes)
        }
    }

    @Test
    fun readerSettings_canCustomizeTypographyIndependently() {
        val customized = ReaderSettings(
            readerFontSizeSp = 24,
            readerLineSpacing = 1.6f,
            readerPageMarginDp = 28,
            readerFontFamily = ReaderFontFamily.SERIF,
        )
        assertEquals(24, customized.readerFontSizeSp)
        assertEquals(1.6f, customized.readerLineSpacing, 0.001f)
        assertEquals(28, customized.readerPageMarginDp)
        assertEquals(ReaderFontFamily.SERIF, customized.readerFontFamily)
    }
}
