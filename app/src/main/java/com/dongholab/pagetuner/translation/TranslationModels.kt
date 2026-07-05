package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.ReaderPage
import com.dongholab.pagetuner.document.TextSegment
import java.net.URI

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
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> apiKey.isNotBlank()
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
                apiKey.isNotBlank() && normalizedLlmEndpoint.isNotBlank() && normalizedLlmModel.isNotBlank()
        }
}

enum class TranslationProviderKind {
    GOOGLE_CLOUD,
    GOOGLE_WEB_TRANSLATE_HTML,
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

enum class ProviderHealthState {
    NotChecked,
    Ready,
    MissingConfiguration,
    InvalidConfiguration,
}

data class ProviderHealthCheck(
    val state: ProviderHealthState = ProviderHealthState.NotChecked,
    val providerKind: TranslationProviderKind? = null,
)

fun TranslationSettings.checkProviderHealth(): ProviderHealthCheck {
    return when (providerKind) {
        TranslationProviderKind.GOOGLE_CLOUD -> {
            if (apiKey.isBlank()) {
                ProviderHealthCheck(
                    state = ProviderHealthState.MissingConfiguration,
                    providerKind = providerKind,
                )
            } else {
                ProviderHealthCheck(state = ProviderHealthState.Ready, providerKind = providerKind)
            }
        }
        TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> {
            if (apiKey.isBlank()) {
                ProviderHealthCheck(
                    state = ProviderHealthState.MissingConfiguration,
                    providerKind = providerKind,
                )
            } else {
                ProviderHealthCheck(state = ProviderHealthState.Ready, providerKind = providerKind)
            }
        }
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> {
            when {
                apiKey.isBlank() || normalizedLlmEndpoint.isBlank() || normalizedLlmModel.isBlank() ->
                    ProviderHealthCheck(
                        state = ProviderHealthState.MissingConfiguration,
                        providerKind = providerKind,
                    )
                !normalizedLlmEndpoint.hasHttpUrlShape() ->
                    ProviderHealthCheck(
                        state = ProviderHealthState.InvalidConfiguration,
                        providerKind = providerKind,
                    )
                else -> ProviderHealthCheck(state = ProviderHealthState.Ready, providerKind = providerKind)
            }
        }
    }
}

enum class TranslationQueueItemStatus {
    Pending,
    Active,
    Saved,
    Failed,
    Cancelled,
}

data class TranslationQueueItem(
    val pageIndex: Int,
    val pageNumber: Int,
    val status: TranslationQueueItemStatus = TranslationQueueItemStatus.Pending,
    val attempts: Int = 0,
    val error: String? = null,
)

data class TranslationQueueState(
    val items: List<TranslationQueueItem> = emptyList(),
    val running: Boolean = false,
    val paused: Boolean = false,
    val cancelled: Boolean = false,
    val retrying: Boolean = false,
) {
    val totalPages: Int
        get() = items.size

    val completedPages: Int
        get() = items.count { it.status == TranslationQueueItemStatus.Saved }

    val failedPages: Int
        get() = items.count { it.status == TranslationQueueItemStatus.Failed }

    val activePageNumber: Int?
        get() = items.firstOrNull { it.status == TranslationQueueItemStatus.Active }?.pageNumber

    val fraction: Float
        get() = if (totalPages == 0) 0f else completedPages.toFloat() / totalPages.toFloat()

    val canPause: Boolean
        get() = running && !paused

    val canResume: Boolean
        get() = running && paused

    val canCancel: Boolean
        get() = running

    val canRetry: Boolean
        get() = !running && failedPages > 0
}

private fun String.hasHttpUrlShape(): Boolean {
    val parsed = runCatching { URI(this) }.getOrNull() ?: return false
    return parsed.scheme in setOf("http", "https") && !parsed.host.isNullOrBlank()
}
