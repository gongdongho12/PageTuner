package com.dongholab.pagetuner.translation

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
    override fun toString(): String = "TranslationSettings(providerKind=$providerKind, credentials=REDACTED)"

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
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> true
            TranslationProviderKind.DEEPSEEK ->
                apiKey.isNotBlank() && normalizedLlmEndpoint.isNotBlank() && normalizedLlmModel.isNotBlank()
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM ->
                apiKey.isNotBlank() && normalizedLlmEndpoint.isNotBlank() && normalizedLlmModel.isNotBlank()
        }
}

enum class TranslationProviderKind {
    GOOGLE_CLOUD,
    GOOGLE_WEB_TRANSLATE_HTML,
    DEEPSEEK,
    OPENAI_COMPATIBLE_LLM,
}

enum class TranslationSubscriptionPlan {
    GOOGLE_TRANSLATE,
    DEEPSEEK_AI,
    CUSTOM_API,
}

val TranslationProviderKind.subscriptionPlan: TranslationSubscriptionPlan
    get() = when (this) {
        TranslationProviderKind.GOOGLE_CLOUD,
        TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> TranslationSubscriptionPlan.GOOGLE_TRANSLATE
        TranslationProviderKind.DEEPSEEK -> TranslationSubscriptionPlan.DEEPSEEK_AI
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> TranslationSubscriptionPlan.CUSTOM_API
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

typealias TranslationProgress = com.dongholab.pagetuner.core.translation.ContentTranslationProgress

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
            ProviderHealthCheck(state = ProviderHealthState.Ready, providerKind = providerKind)
        }
        TranslationProviderKind.DEEPSEEK -> {
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

private fun String.hasHttpUrlShape(): Boolean {
    val parsed = runCatching { URI(this) }.getOrNull() ?: return false
    return parsed.isAllowedProviderEndpoint()
}
