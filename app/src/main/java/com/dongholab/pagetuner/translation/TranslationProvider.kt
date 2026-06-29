package com.dongholab.pagetuner.translation

interface TranslationProvider {
    val id: String

    suspend fun translate(request: TranslationRequest): List<TranslatedSegment>
}
