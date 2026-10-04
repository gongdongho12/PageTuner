package com.dongholab.pagetuner.translation

data class CostEstimate(
    val totalCharacters: Int,
    val estimatedPages: Int,
    val estimatedTokens: Int,
    val estimatedCostUsd: Double,
)

object TranslationCostEstimator {
    // Estimated average pricing: Google Cloud $20 per 1M chars, OpenAI GPT-4o-mini approx $0.15 per 1M input tokens
    fun estimateCost(
        pagesText: List<String>,
        costPerMillionCharsUsd: Double = 20.0,
    ): CostEstimate {
        val totalChars = pagesText.sumOf { it.length }
        val estimatedTokens = (totalChars / 4.0).toInt()
        val estimatedCost = (totalChars / 1_000_000.0) * costPerMillionCharsUsd

        return CostEstimate(
            totalCharacters = totalChars,
            estimatedPages = pagesText.size,
            estimatedTokens = estimatedTokens,
            estimatedCostUsd = estimatedCost,
        )
    }
}
