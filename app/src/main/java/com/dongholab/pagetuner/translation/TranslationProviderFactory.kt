package com.dongholab.pagetuner.translation

object TranslationProviderFactory {
    fun create(settings: TranslationSettings): TranslationProvider {
        return when (settings.providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD -> GoogleCloudTranslationProvider(settings.apiKey)
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> OpenAiCompatibleLlmTranslationProvider(
                apiKey = settings.apiKey,
                endpoint = settings.normalizedLlmEndpoint,
                model = settings.normalizedLlmModel,
            )
        }
    }
}
