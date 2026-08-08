package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationCostEstimatorTest {
    @Test
    fun estimateCost_calculatesCorrectValues() {
        val pages = listOf("Hello World", "PageTurner E-Ink Reader")
        val estimate = TranslationCostEstimator.estimateCost(pages, costPerMillionCharsUsd = 20.0)

        assertEquals(2, estimate.estimatedPages)
        assertEquals(34, estimate.totalCharacters)
        assertEquals(8, estimate.estimatedTokens)
    }
}
