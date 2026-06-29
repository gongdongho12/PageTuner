package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment

data class TranslationSettings(
    val providerKind: TranslationProviderKind = TranslationProviderKind.GOOGLE_CLOUD,
    val apiKey: String,
    val llmEndpoint: String = "",
    val llmModel: String = "",
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ko",
    val readingWordsPerMinute: Int = 210,
    val paceMode: TranslationPaceMode = TranslationPaceMode.READING,
    val batchSize: Int = 6,
) {
    val normalizedSourceLanguage: String
        get() = sourceLanguage.trim().ifBlank { "auto" }

    val normalizedTargetLanguage: String
        get() = targetLanguage.trim().ifBlank { "ko" }

    val normalizedLlmEndpoint: String
        get() = llmEndpoint.trim().trimEnd('/')

    val normalizedLlmModel: String
        get() = llmModel.trim()

    val isProviderConfigured: Boolean
        get() = when (providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD -> apiKey.isNotBlank()
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
                apiKey.isNotBlank() && normalizedLlmEndpoint.isNotBlank() && normalizedLlmModel.isNotBlank()
        }
}

enum class TranslationProviderKind {
    GOOGLE_CLOUD,
    OPENAI_COMPATIBLE_LLM,
}

enum class TranslationPaceMode {
    READING,
    FAST,
    OFFLINE_PREFETCH,
}

data class TranslationRequest(
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TextSegment>,
)

data class TranslatedSegment(
    val segmentId: String,
    val translatedText: String,
)

data class PageTranslation(
    val page: ReaderPage,
    val sourceLanguage: String,
    val targetLanguage: String,
    val segments: List<TranslatedSegment>,
    val completedFromCache: Boolean,
) {
    val text: String = segments.joinToString(separator = "\n\n") { it.translatedText }
}

data class TranslationProgress(
    val completedSegments: Int,
    val totalSegments: Int,
    val status: String,
    val currentText: String,
) {
    val fraction: Float
        get() = if (totalSegments == 0) 1f else completedSegments.toFloat() / totalSegments.toFloat()
}

data class PrefetchProgress(
    val completedPages: Int,
    val totalPages: Int,
    val activePageNumber: Int,
    val stage: PrefetchStage,
) {
    val fraction: Float
        get() = if (totalPages == 0) 1f else completedPages.toFloat() / totalPages.toFloat()
}

enum class PrefetchStage {
    PREPARING,
    SAVED,
}
