package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.translation.glossary.BookGlossary

data class TranslationExecutionIdentity(
    val providerId: String,
    val modelId: String,
    val promptRevision: String,
    val glossaryRevision: String,
)

/** Identity of ChapterTranslationEngine's fixed prompt and glossary pipeline (no dynamic alias discovery). */
object TranslationRuntimeIdentity {
    fun describe(settings: TranslationSettings, glossary: BookGlossary? = null): TranslationExecutionIdentity {
        val provider = TranslationProviderFactory.create(settings)
        val model = when (settings.providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD -> "google-translation-v2"
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> if (settings.apiKey.isBlank()) "google-web-public" else "google-web-html"
            TranslationProviderKind.DEEPSEEK, TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> settings.normalizedLlmModel
        }
        val prompt = when (settings.providerKind) {
            TranslationProviderKind.DEEPSEEK, TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> OpenAiCompatibleLlmTranslationProvider.PromptRevision
            TranslationProviderKind.GOOGLE_CLOUD -> "google-cloud-text-v1"
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML -> "google-web-text-v1"
        }
        return TranslationExecutionIdentity(
            providerId = provider.id,
            modelId = model,
            promptRevision = "$prompt:paragraph-chunks-v1",
            glossaryRevision = glossary?.takeIf { it.activeEntries.isNotEmpty() }
                ?.let { "protected-terms-v1:${it.translationFingerprint}" }.orEmpty(),
        )
    }
}
