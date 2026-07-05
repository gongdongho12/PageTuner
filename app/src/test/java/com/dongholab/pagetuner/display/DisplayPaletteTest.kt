package com.dongholab.pagetuner.display

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DisplayPaletteTest {
    @Test
    fun selectsColorAndMonochromeServicePalettes() {
        assertEquals(ColorServicePalette, DisplayMode.Color.servicePalette())
        assertEquals(MonochromeServicePalette, DisplayMode.Monochrome.servicePalette())
        assertEquals(MonochromeServicePalette, DisplayMode.EinkHighContrast.servicePalette())
        assertNotEquals(
            DisplayMode.Color.servicePalette().paperArgb,
            DisplayMode.Monochrome.servicePalette().paperArgb,
        )
    }
}
