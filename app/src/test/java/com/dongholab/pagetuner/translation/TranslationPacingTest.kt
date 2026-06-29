package com.dongholab.pagetuner.translation

import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationPacingTest {
    @Test
    fun fasterModesUseShorterDelays() {
        val words = 120
        val reading = TranslationPacing(210, TranslationPaceMode.READING).delayAfterBatchMillis(words)
        val fast = TranslationPacing(210, TranslationPaceMode.FAST).delayAfterBatchMillis(words)
        val prefetch = TranslationPacing(210, TranslationPaceMode.OFFLINE_PREFETCH).delayAfterBatchMillis(words)

        assertTrue(reading > fast)
        assertTrue(fast > prefetch)
    }

    @Test
    fun clampsUnreasonableReadingSpeeds() {
        val verySlow = TranslationPacing(1, TranslationPaceMode.READING).delayAfterBatchMillis(30)
        val veryFast = TranslationPacing(10_000, TranslationPaceMode.READING).delayAfterBatchMillis(30)

        assertTrue(verySlow <= 14_000L)
        assertTrue(veryFast >= 750L)
    }
}
