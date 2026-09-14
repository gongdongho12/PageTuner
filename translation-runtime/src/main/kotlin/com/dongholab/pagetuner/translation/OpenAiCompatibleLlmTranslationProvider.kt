package com.dongholab.pagetuner.translation

import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.translation.glossary.CharacterAliasSuggestion
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OpenAiCompatibleLlmTranslationProvider(
    private val apiKey: String,
    private val endpoint: String,
    private val model: String,
    private val providerName: String = DefaultProviderName,
    private val providerIdPrefix: String = "openai-compatible-llm",
    private val requestOptions: LlmChatRequestOptions = LlmChatRequestOptions(),
    private val transport: LlmHttpTransport = LlmHttpTransport.default(providerName),
    initialCharacterAliases: List<CharacterAliasSuggestion> = emptyList(),
    private val onCharacterAliases: ((List<CharacterAliasSuggestion>) -> Unit)? = null,
) : TranslationProvider {
    private val characterAliases = linkedMapOf<String, CharacterAliasSuggestion>().apply {
        initialCharacterAliases.forEach { suggestion ->
            val source = suggestion.sourceTerm.trim()
            val alias = suggestion.alias.trim()
            if (source.isNotBlank() && alias.isNotBlank()) {
                put(source.lowercase(), CharacterAliasSuggestion(source, alias))
            }
        }
    }
    private val characterAliasEnabled = onCharacterAliases != null || characterAliases.isNotEmpty()

    override val id: String = buildString {
        append(providerIdPrefix)
        append(':')
        append(DocumentIds.sha256(endpoint).take(12))
        append(':')
        append(model)
        if (characterAliasEnabled) append(":character-alias-v1")
    }

    override suspend fun translate(request: TranslationRequest): List<TranslatedSegment> {
        if (apiKey.isBlank()) {
            throw providerConfigurationException(providerName, "LLM API key is required.")
        }
        if (endpoint.isBlank()) {
            throw providerConfigurationException(providerName, "LLM endpoint is required.")
        }
        if (model.isBlank()) {
            throw providerConfigurationException(providerName, "LLM model is required.")
        }
        if (request.segments.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            runCatching {
                val response = transport.post(
                    endpoint = endpoint,
                    headers = mapOf(
                        "Authorization" to "Bearer ${apiKey.trim()}",
                        "Content-Type" to "application/json; charset=utf-8",
                        "Accept" to "application/json",
                    ),
                    body = buildRequestBody(request),
                )
                parseResponse(request, response)
            }.getOrElse { error ->
                throw error.asProviderNetworkFailure(providerName)
            }
        }
    }

    private fun buildRequestBody(request: TranslationRequest): String {
        val input = JSONArray().apply {
            request.segments.forEach { put(it.text) }
        }
        val knownAliases = synchronized(characterAliases) { characterAliases.values.toList() }
        return JSONObject().apply {
            put("model", model)
            put("temperature", 0)
            put("stream", false)
            if (requestOptions.jsonResponse) {
                put("response_format", JSONObject().put("type", "json_object"))
            }
            requestOptions.thinkingEnabled?.let { enabled ->
                put("thinking", JSONObject().put("type", if (enabled) "enabled" else "disabled"))
            }
            put("messages", JSONArray().apply {
                put(
                    JSONObject()
                        .put("role", "system")
                        .put(
                            "content",
                            "You are a translation engine. Return only JSON. " +
                                "Preserve paragraph count and order. Do not add explanations." +
                                if (characterAliasEnabled) {
                                    " Identify named people and transliterate their names naturally and consistently. " +
                                        "Preserve PTGLOSSARY tokens exactly. Use every supplied character alias verbatim."
                                } else {
                                    ""
                                },
                        ),
                )
                put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            buildString {
                                append("Translate each item from ")
                                append(request.sourceLanguage)
                                append(" to ")
                                append(request.targetLanguage)
                                append(". Return exactly this shape: ")
                                if (characterAliasEnabled) {
                                    append("{\"translations\":[\"...\"],")
                                    append("\"characterAliases\":[{\"source\":\"A-Pu\",\"alias\":\"아푸\"}]}\n")
                                    append("characterAliases must contain only people named in the input; ")
                                    append("use their aliases in translations. Existing aliases: ")
                                    append(JSONArray().apply {
                                        knownAliases.forEach { suggestion ->
                                            put(JSONObject()
                                                .put("source", suggestion.sourceTerm)
                                                .put("alias", suggestion.alias))
                                        }
                                    })
                                    append('\n')
                                } else {
                                    append("{\"translations\":[\"...\"]}\n")
                                }
                                append(input.toString())
                            },
                        ),
                )
            })
        }.toString()
    }

    private fun parseResponse(
        request: TranslationRequest,
        response: String,
    ): List<TranslatedSegment> {
        val content = runCatching {
            JSONObject(response)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = providerName,
                detail = "LLM response did not contain a chat completion message.",
                cause = error,
            )
        }

        val responseJson = runCatching { extractJsonObject(content) }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = providerName,
                detail = "LLM response did not contain translation JSON.",
                cause = error,
            )
        }
        val translations = runCatching {
            responseJson.getJSONArray("translations")
        }.getOrElse { error ->
            throw providerResponseFormatException(
                providerName = providerName,
                detail = "LLM response did not contain translation JSON.",
                cause = error,
            )
        }
        if (translations.length() != request.segments.size) {
            throw providerResponseFormatException(
                providerName = providerName,
                detail = "LLM translation response size did not match request size.",
            )
        }

        val result = request.segments.mapIndexed { index, segment ->
            TranslatedSegment(
                segmentId = segment.id,
                translatedText = translations.getString(index),
            )
        }
        validateTranslationResponse(request.segments, result, providerName)
        if (characterAliasEnabled) {
            acceptCharacterAliases(request, responseJson.optJSONArray("characterAliases"))
        }
        return result
    }

    private fun acceptCharacterAliases(request: TranslationRequest, values: JSONArray?) {
        if (values == null) return
        val sourceText = request.segments.joinToString("\n") { it.text }
        val discovered = buildList {
            for (index in 0 until values.length().coerceAtMost(MaxAliasesPerResponse)) {
                val item = values.optJSONObject(index) ?: continue
                val source = item.optString("source").trim()
                val alias = item.optString("alias").trim()
                if (source.isBlank() || alias.isBlank()) continue
                if (source.length > MaxAliasCharacters || alias.length > MaxAliasCharacters) continue
                if (!sourceText.contains(source, ignoreCase = true)) continue
                val normalized = source.lowercase()
                val added = synchronized(characterAliases) {
                    if (characterAliases.containsKey(normalized)) false
                    else {
                        characterAliases[normalized] = CharacterAliasSuggestion(source, alias)
                        true
                    }
                }
                if (added) add(CharacterAliasSuggestion(source, alias))
            }
        }
        if (discovered.isNotEmpty()) onCharacterAliases?.invoke(discovered)
    }

    private fun extractJsonObject(content: String): JSONObject {
        val trimmed = content.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        return try {
            JSONObject(trimmed)
        } catch (error: Exception) {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start < 0 || end <= start) throw IOException("LLM response did not contain JSON.", error)
            JSONObject(trimmed.substring(start, end + 1))
        }
    }

    companion object {
        /** Bump when the fixed translation instructions or response contract change. */
        const val PromptRevision = "paragraph-json-v1"
        private const val DefaultProviderName = "LLM API"
        private const val MaxAliasesPerResponse = 24
        private const val MaxAliasCharacters = 80
    }
}

data class LlmChatRequestOptions(
    val jsonResponse: Boolean = false,
    val thinkingEnabled: Boolean? = null,
)

fun interface LlmHttpTransport {
    suspend fun post(
        endpoint: String,
        headers: Map<String, String>,
        body: String,
    ): String

    companion object {
        fun default(providerName: String = "LLM API"): LlmHttpTransport =
            LlmHttpTransport { endpoint, headers, body ->
                ProviderHttpTransport(providerName).post(endpoint, headers, body)
            }
    }
}
