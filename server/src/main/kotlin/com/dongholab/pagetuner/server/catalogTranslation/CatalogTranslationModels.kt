package com.dongholab.pagetuner.server.catalogTranslation

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.translation.CatalogTranslationEntry
import java.time.Instant
import java.util.UUID

/** Credentials belong only to an executing coroutine; never put this request in the task registry. */
class CatalogTranslationRequest(
    val requestId: UUID,
    val items: List<CatalogTranslationEntry>,
    val sourceLanguage: String = "auto",
    val targetLanguage: String = "ko",
    val providerKind: String = "GOOGLE_WEB_TRANSLATE_HTML",
    val endpoint: String? = null,
    val model: String? = null,
    val apiKey: String? = null,
) {
    override fun toString() = "CatalogTranslationRequest(requestId=$requestId, credentials=REDACTED)"
    fun snapshot() = CatalogTranslationRequest(requestId, items.map { it.copy() }, sourceLanguage.trim(), targetLanguage.trim(),
        providerKind, endpoint?.trim(), model?.trim(), apiKey)
    fun validate() {
        require(items.size in 1..24) { "Select between 1 and 24 catalog items." }
        require(items.map { it.key }.distinct().size == items.size) { "Catalog item keys must be unique." }
        require(items.all { it.key.length in 1..512 && it.key.isNotBlank() && it.title.length in 1..400 && it.title.isNotBlank() &&
            (it.description?.length ?: 0) <= 2_000 }) { "Catalog keys, titles or descriptions exceed their limits." }
        require(items.sumOf { it.title.length + (it.description?.length ?: 0) } <= 24_000) { "Catalog text exceeds 24000 characters." }
        val language = Regex("[A-Za-z][A-Za-z0-9-]{0,23}")
        require(language.matches(sourceLanguage) && language.matches(targetLanguage) && !targetLanguage.equals("auto", true) &&
            !sourceLanguage.equals(targetLanguage, true)) { "Invalid catalog translation languages." }
        require(providerKind in setOf("GOOGLE_WEB_TRANSLATE_HTML", "GOOGLE_CLOUD", "DEEPSEEK", "OPENAI_COMPATIBLE_LLM")) { "Invalid translation provider." }
        require((apiKey?.length ?: 0) <= 4096 && apiKey.orEmpty().none { it == '\r' || it == '\n' }) { "Invalid provider credential." }
        require((endpoint?.length ?: 0) <= 2_000 && (model?.length ?: 0) <= 200 &&
            endpoint.orEmpty().none { it.isISOControl() } && model.orEmpty().none { it.isISOControl() }) { "Invalid translation settings." }
    }
    val sourceHash: String get() = StableContentHash.sha256(items.joinToString("\n") {
        listOf(it.key, it.title, it.description.orEmpty()).joinToString("") { value -> "${value.length}:$value" }
    })
    val signature: String get() = StableContentHash.sha256(listOf(sourceHash, sourceLanguage, targetLanguage, providerKind,
        endpoint.orEmpty(), model.orEmpty()).joinToString("\n"))
}

data class CatalogTranslationItem(val key: String, val title: String, val description: String?, val targetLanguage: String)
data class CatalogTranslationView(
    val requestId: UUID, val status: String, val sourceHash: String, val providerKind: String, val targetLanguage: String,
    val completedSegments: Int, val totalSegments: Int, val items: List<CatalogTranslationItem>, val errorCode: String?, val updatedAt: Instant,
)
