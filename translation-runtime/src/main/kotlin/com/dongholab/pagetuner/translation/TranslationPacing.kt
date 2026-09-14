package com.dongholab.pagetuner.translation

import kotlin.math.roundToLong

data class TranslationPacing(
    val readingWordsPerMinute: Int,
    val mode: TranslationPaceMode,
) {
    fun delayAfterBatchMillis(wordCount: Int): Long {
        val safeWpm = readingWordsPerMinute.coerceIn(80, 900)
        val readingMillis = (wordCount.toDouble() / safeWpm.toDouble() * 60_000.0).roundToLong()

        return when (mode) {
            TranslationPaceMode.READING -> readingMillis.coerceIn(750L, 14_000L)
            TranslationPaceMode.FAST -> (readingMillis * 0.28).roundToLong().coerceIn(250L, 4_000L)
            TranslationPaceMode.OFFLINE_PREFETCH -> (readingMillis * 0.08).roundToLong().coerceIn(80L, 1_200L)
        }
    }
}
