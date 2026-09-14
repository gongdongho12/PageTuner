package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.ChapterContent
import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.translation.ChapterTranslationEngine
import com.dongholab.pagetuner.translation.DeepSeekDefaults
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.translation.TranslationRuntimeIdentity
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.server.account.LanguageCatalog
import java.net.URI
import org.springframework.stereotype.Component

interface WorkflowTranslator {
    suspend fun translate(chapter: ChapterContent, config: JobConfiguration, apiKey: String,
        completed: Map<String, String>, onParagraph: suspend (String, String) -> Unit): List<TranslatedParagraph>
}

@Component
class RuntimeWorkflowTranslator : WorkflowTranslator {
    override suspend fun translate(chapter: ChapterContent, config: JobConfiguration, apiKey: String,
        completed: Map<String, String>, onParagraph: suspend (String, String) -> Unit): List<TranslatedParagraph> =
        ChapterTranslationEngine().translate(chapter, config.settings(apiKey), config.glossary(chapter.identity.book.canonicalId),
            completed, onParagraph, { _, _ -> })
}

fun JobConfiguration.settings(key: String) = TranslationSettings(
    providerKind = TranslationProviderKind.valueOf(providerKind), apiKey = key, llmEndpoint = endpoint, llmModel = model,
    sourceLanguage = sourceLanguage, targetLanguage = targetLanguage, paceMode = TranslationPaceMode.FAST,
)
fun JobConfiguration.glossary(bookId: String): BookGlossary? = glossary.takeIf { it.isNotEmpty() }?.let { entries ->
    BookGlossary(bookId, entries.map { BookGlossaryEntry(StableContentHash.sha256(it.source.lowercase()).take(24), it.source, it.target) })
}

@Component
class WorkflowProviders {
    private fun env(name: String) = System.getenv(name).orEmpty().trim()
    private val defaultDeepSeekEndpoint = env("DEEPSEEK_API_URL").ifBlank { "https://api.deepseek.com/chat/completions" }
    private val defaultOpenAiEndpoint = env("OPENAI_API_URL").ifBlank { "https://api.openai.com/v1/chat/completions" }
    private val allowedEndpoints = (env("PAGETUNER_LLM_ENDPOINTS").split(',') + defaultDeepSeekEndpoint + defaultOpenAiEndpoint)
        .map { it.trim().trimEnd('/') }.filter(String::isNotBlank).toSet()

    fun list() = TranslationProviderList(listOf(
        TranslationProviderInfo("GOOGLE_WEB_TRANSLATE_HTML", "Google 웹 번역", true, false, "", ""),
        TranslationProviderInfo("GOOGLE_CLOUD", "Google Cloud Translation", key("GOOGLE_CLOUD").isNotBlank(), true, "", ""),
        TranslationProviderInfo("DEEPSEEK", "DeepSeek", key("DEEPSEEK").isNotBlank(), true, defaultDeepSeekEndpoint,
            env("DEEPSEEK_MODEL").ifBlank { DeepSeekDefaults.Model }),
        TranslationProviderInfo("OPENAI_COMPATIBLE_LLM", "OpenAI 호환 API", key("OPENAI_COMPATIBLE_LLM").isNotBlank(), true,
            defaultOpenAiEndpoint, env("OPENAI_MODEL").ifBlank { "gpt-4.1-mini" }),
    ))

    fun resolve(request: CreateTranslationJobRequest, chapter: StoredChapter): Pair<JobConfiguration, String> {
        val provider = list().providers.find { it.id == request.providerKind }
            ?: throw WorkflowFailure("INVALID_PROVIDER", 400, "지원하지 않는 번역 제공자입니다.")
        val secret = request.apiKey?.trim().orEmpty().ifBlank { key(provider.id) }
        require(secret.length <= 4096 && !secret.contains('\n') && !secret.contains('\r')) { "Invalid API credential." }
        if (provider.requiresKey && secret.isBlank()) throw WorkflowFailure("PROVIDER_NOT_CONFIGURED", 400, "번역 API 키를 입력하거나 서버에 설정해 주세요.")
        val requestedSource = request.sourceLanguage?.trim().orEmpty().ifBlank { chapter.sourceLanguage }
        val source = if (requestedSource.equals("auto", ignoreCase = true)) "auto" else LanguageCatalog.normalize(requestedSource, 24)
        require(!request.targetLanguage.trim().equals("auto", ignoreCase = true)) { "A concrete target language is required." }
        val target = LanguageCatalog.normalize(request.targetLanguage, 24)
        require(!source.equals(target, ignoreCase = true)) { "Source and target languages must differ." }
        val isLlm = provider.id in setOf("DEEPSEEK", "OPENAI_COMPATIBLE_LLM")
        val endpoint = if (isLlm) request.endpoint?.trim()?.trimEnd('/').orEmpty().ifBlank { provider.defaultEndpoint.trimEnd('/') } else ""
        val model = if (isLlm) request.model?.trim().orEmpty().ifBlank { provider.defaultModel } else ""
        if (isLlm) {
            val uri = runCatching { URI(endpoint) }.getOrNull()
            require(uri != null && uri.scheme in setOf("https", "http") && uri.host != null && uri.userInfo == null && uri.fragment == null && uri.query == null) { "Invalid translation endpoint." }
            require(uri.scheme == "https" || uri.host in setOf("localhost", "127.0.0.1", "[::1]")) { "Translation endpoint must use HTTPS (except explicit loopback development endpoints)." }
            if (endpoint !in allowedEndpoints) throw WorkflowFailure("ENDPOINT_NOT_ALLOWED", 400, "이 번역 서버 주소는 허용되지 않았습니다. 서버의 PAGETUNER_LLM_ENDPOINTS 설정에 등록해 주세요.")
            require(model.length in 1..200 && model.none(Char::isISOControl)) { "Invalid translation model." }
        }
        require(request.glossary.size <= 200) { "A glossary can contain at most 200 entries." }
        val glossary = request.glossary.map { WorkflowGlossaryEntry(it.source.trim(), it.target.trim()) }
        require(glossary.all { it.source.length in 1..200 && it.target.length in 1..200 }) { "Invalid glossary entry." }
        require(glossary.map { it.source.lowercase() }.distinct().size == glossary.size) { "Duplicate glossary source terms." }
        val preliminary = JobConfiguration(provider.id, source, target, endpoint, model, glossary.sortedBy { it.source }, "", "", "")
        val identity = TranslationRuntimeIdentity.describe(preliminary.settings(secret), preliminary.glossary(chapter.content().identity.book.canonicalId))
        return preliminary.copy(translationProviderId = identity.providerId, model = identity.modelId,
            promptRevision = identity.promptRevision, glossaryRevision = identity.glossaryRevision) to secret
    }
    private fun key(id: String): String = when (id) {
        "GOOGLE_CLOUD" -> env("PAGETUNER_GOOGLE_API_KEY")
        "DEEPSEEK" -> env("DEEPSEEK_API_KEY")
        "OPENAI_COMPATIBLE_LLM" -> env("OPENAI_API_KEY")
        else -> ""
    }
}
