package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion

object TranslationProviderFactory {
    fun create(
        settings: TranslationSettings,
        initialCharacterAliases: List<CharacterAliasSuggestion> = emptyList(),
        onCharacterAliases: ((List<CharacterAliasSuggestion>) -> Unit)? = null,
    ): TranslationProvider {
        return when (settings.providerKind) {
            TranslationProviderKind.GOOGLE_CLOUD -> GoogleCloudTranslationProvider(settings.apiKey)
            TranslationProviderKind.GOOGLE_WEB_TRANSLATE_HTML ->
                GoogleWebTranslateHtmlProvider(settings.apiKey)
            TranslationProviderKind.DEEPSEEK -> DeepSeekTranslationProvider(
                apiKey = settings.apiKey,
                endpoint = settings.normalizedLlmEndpoint,
                model = settings.normalizedLlmModel,
                initialCharacterAliases = initialCharacterAliases,
                onCharacterAliases = onCharacterAliases,
            )
            TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> OpenAiCompatibleLlmTranslationProvider(
                apiKey = settings.apiKey,
                endpoint = settings.normalizedLlmEndpoint,
                model = settings.normalizedLlmModel,
                initialCharacterAliases = initialCharacterAliases,
                onCharacterAliases = onCharacterAliases,
            )
        }
    }
}
