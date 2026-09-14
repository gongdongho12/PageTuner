package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion

/** DeepSeek Chat Completions adapter with deterministic JSON and non-thinking translation mode. */
class DeepSeekTranslationProvider(
    apiKey: String,
    endpoint: String = DeepSeekDefaults.ApiUrl,
    model: String = DeepSeekDefaults.Model,
    transport: LlmHttpTransport = LlmHttpTransport.default(ProviderName),
    initialCharacterAliases: List<CharacterAliasSuggestion> = emptyList(),
    onCharacterAliases: ((List<CharacterAliasSuggestion>) -> Unit)? = null,
) : TranslationProvider {
    private val delegate = OpenAiCompatibleLlmTranslationProvider(
        apiKey = apiKey,
        endpoint = endpoint,
        model = model,
        providerName = ProviderName,
        providerIdPrefix = "deepseek",
        requestOptions = LlmChatRequestOptions(
            jsonResponse = true,
            thinkingEnabled = false,
        ),
        transport = transport,
        initialCharacterAliases = initialCharacterAliases,
        onCharacterAliases = onCharacterAliases,
    )

    override val id: String = delegate.id

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> =
        delegate.translate(request)

    private companion object {
        const val ProviderName = "DeepSeek"
    }
}

object DeepSeekDefaults {
    const val ApiUrl = "https://api.deepseek.com/chat/completions"
    const val Model = "deepseek-v4-flash"
}
