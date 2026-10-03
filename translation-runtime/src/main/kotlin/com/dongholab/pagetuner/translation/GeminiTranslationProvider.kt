package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion

/** Google's documented OpenAI compatibility API, using the existing strict paragraph/alias parser. */
class GeminiTranslationProvider(
    apiKey: String,
    endpoint: String = GeminiDefaults.ApiUrl,
    model: String = GeminiDefaults.Model,
    transport: LlmHttpTransport = LlmHttpTransport.default("Gemini"),
    initialCharacterAliases: List<CharacterAliasSuggestion> = emptyList(),
    onCharacterAliases: ((List<CharacterAliasSuggestion>) -> Unit)? = null,
) : TranslationProvider {
    private val delegate = OpenAiCompatibleLlmTranslationProvider(
        apiKey = apiKey, endpoint = endpoint, model = model,
        providerName = "Gemini", providerIdPrefix = "gemini",
        requestOptions = LlmChatRequestOptions(maxTokens = 32_768, jsonSchemaResponse = true),
        transport = transport, initialCharacterAliases = initialCharacterAliases, onCharacterAliases = onCharacterAliases,
    )
    override val id = delegate.id
    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> = delegate.translate(request)
}

object GeminiDefaults {
    const val ApiUrl = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    const val Model = "gemini-3.8-flash"
}
