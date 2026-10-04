package com.dongholab.pagetuner.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ListLayoutModeTest {

    @Test
    fun readerSettings_defaultToDiscreteEinkPaging() {
        assertEquals(ListLayoutMode.Paged, ReaderSettings().listLayoutMode)
    }

    @Test
    fun touchScrolling_isAnExplicitIndependentPreference() {
        val settings = ReaderSettings().copy(listLayoutMode = ListLayoutMode.Scroll)

        assertEquals(ListLayoutMode.Scroll, settings.listLayoutMode)
        assertEquals(com.dongholab.pagetuner.display.DisplayMode.EinkHighContrast, settings.displayMode)
    }
}
