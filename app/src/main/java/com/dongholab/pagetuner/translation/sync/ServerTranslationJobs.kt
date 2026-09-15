package com.dongholab.pagetuner.translation.sync

import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

data class ServerTranslationProvider(val id: String, val displayName: String, val configured: Boolean,
    val requiresKey: Boolean, val defaultEndpoint: String, val defaultModel: String)
data class ServerJobGlossaryEntry(val source: String, val target: String, val kind: String = "Character",
    val displayTerm: String = "", val caseSensitive: Boolean = false, val enabled: Boolean = true)
data class ServerJobSettings(val sourceLanguage: String, val targetLanguage: String, val endpoint: String,
    val model: String, val glossary: List<ServerJobGlossaryEntry>)
data class ServerTranslationJob(val jobId: String, val status: String, val chapterRecordId: String,
    val bookTitle: String, val chapterTitle: String, val providerKind: String, val targetLanguage: String,
    val completedParagraphs: Int, val totalParagraphs: Int, val translationRecordId: String?,
    val errorCode: String?, val createdAt: String, val updatedAt: String, val settings: ServerJobSettings,
    val canRetry: Boolean) {
    val title: String get() = listOf(bookTitle, chapterTitle).filter(String::isNotBlank).distinct().joinToString(" · ")
    val active: Boolean get() = status in setOf("QUEUED", "RUNNING")
}
data class ServerJobsPage(val items: List<ServerTranslationJob>, val page: Int, val size: Int,
    val totalItems: Long, val totalPages: Int, val hasNext: Boolean)

/** API keys only live in this in-memory form; retry settings returned by the server never include them. */
data class ServerJobDraft(
    val chapterRecordId: String = "",
    val providerKind: String = "GOOGLE_WEB_TRANSLATE_HTML",
    val sourceLanguage: String = "en",
    val targetLanguage: String = "ko",
    val endpoint: String = "",
    val model: String = "",
    val apiKey: String = "",
    val glossary: String = "",
    val glossaryEntries: List<ServerJobGlossaryEntry>? = null,
    val retryOf: String? = null,
    val idempotencyKey: String = UUID.randomUUID().toString(),
) {
    override fun toString(): String = "ServerJobDraft(providerKind=$providerKind, credentials=REDACTED)"
}

internal object ServerTranslationJobJson {
    val providerIds = setOf("GOOGLE_WEB_TRANSLATE_HTML", "GOOGLE_CLOUD", "DEEPSEEK", "OPENAI_COMPATIBLE_LLM")
    private val states = setOf("QUEUED", "RUNNING", "COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED")
    fun providers(json: JSONObject): List<ServerTranslationProvider> {
        val values = json.getJSONArray("providers")
        require(values.length() in 1..20)
        val result = List(values.length()) { index -> values.getJSONObject(index).let { item ->
            ServerTranslationProvider(item.string("id"), item.string("displayName"), item.boolean("configured"), item.boolean("requiresKey"),
                item.string("defaultEndpoint", true), item.string("defaultModel", true)).also { require(it.id in providerIds) }
        } }
        require(result.map { it.id }.distinct().size == result.size)
        return result
    }
    fun job(json: JSONObject): ServerTranslationJob {
        val settings = json.getJSONObject("settings")
        val glossary = settings.getJSONArray("glossary")
        require(glossary.length() <= 200)
        val result = ServerTranslationJob(json.uuid("jobId"), json.string("status"), json.uuid("chapterRecordId"),
            json.string("bookTitle", true), json.string("chapterTitle", true), json.string("providerKind"), json.string("targetLanguage"),
            json.integer("completedParagraphs"), json.integer("totalParagraphs"), json.optional("translationRecordId")?.also(::validateUuid),
            json.optional("errorCode"), json.string("createdAt"), json.string("updatedAt"),
            ServerJobSettings(settings.string("sourceLanguage"), settings.string("targetLanguage"), settings.string("endpoint", true),
                settings.string("model", true), List(glossary.length()) { index -> glossary.getJSONObject(index).let {
                    glossaryEntry(it)
                } }), json.boolean("canRetry"))
        require(result.status in states && result.providerKind in providerIds)
        require(result.totalParagraphs in 1..10_000 && result.completedParagraphs in 0..result.totalParagraphs)
        require(result.canRetry == (result.status in setOf("FAILED", "CANCELLED", "INTERRUPTED")))
        require((result.status == "COMPLETED") == (result.translationRecordId != null))
        require(result.status != "COMPLETED" || result.completedParagraphs == result.totalParagraphs)
        require(result.targetLanguage == result.settings.targetLanguage)
        return result
    }
    fun page(json: JSONObject, page: Int, size: Int): ServerJobsPage {
        require(json.integer("page") == page && json.integer("size") == size)
        val count = json.get("totalItems").let { require(it is Int || it is Long); (it as Number).toLong().also { value -> require(value >= 0) } }
        val pages = json.integer("totalPages")
        val next = json.boolean("hasNext")
        require(pages.toLong() == count / size + (if (count % size > 0) 1 else 0) && next == (page.toLong() + 1 < pages))
        val values = json.getJSONArray("items")
        require(values.length().toLong() == (count - page.toLong() * size).coerceIn(0, size.toLong()))
        val items = List(values.length()) { job(values.getJSONObject(it)) }
        require(items.map { it.jobId }.distinct().size == items.size)
        return ServerJobsPage(items, page, size, count, pages, next)
    }
    fun encode(draft: ServerJobDraft): JSONObject {
        validateUuid(draft.chapterRecordId); validateUuid(draft.idempotencyKey); draft.retryOf?.let(::validateUuid)
        require(draft.providerKind in providerIds)
        val language = Regex("[A-Za-z][A-Za-z0-9-]{0,23}")
        require(language.matches(draft.sourceLanguage) && language.matches(draft.targetLanguage) && !draft.targetLanguage.equals("auto", true))
        require(!draft.sourceLanguage.equals(draft.targetLanguage, true))
        require(draft.apiKey.length <= 4096 && draft.apiKey.none { it == '\r' || it == '\n' })
        val terms = draft.glossaryEntries ?: draft.glossary.lineSequence().map(String::trim).filter(String::isNotEmpty).map { line ->
            require('=' in line) { "Glossary entries must use source=translation." }
            ServerJobGlossaryEntry(line.substringBefore('=').trim(), line.substringAfter('=').trim())
        }.toList()
        require(terms.size <= 200 && terms.all { it.source.length in 1..200 && it.target.length in 1..200 &&
            it.displayTerm.length <= 200 && it.kind in setOf("Character", "Place", "Term") })
        require(terms.map { it.source.lowercase() }.distinct().size == terms.size)
        return JSONObject().put("chapterRecordId", draft.chapterRecordId).put("idempotencyKey", draft.idempotencyKey)
            .put("providerKind", draft.providerKind).put("sourceLanguage", draft.sourceLanguage).put("targetLanguage", draft.targetLanguage)
            .put("endpoint", draft.endpoint.trim()).put("model", draft.model.trim())
            .put("glossary", JSONArray().apply { terms.forEach { term -> put(JSONObject().put("source", term.source.trim()).put("target", term.target.trim()).apply {
                if (term.kind != "Character") put("kind", term.kind)
                if (term.displayTerm.isNotBlank()) put("displayTerm", term.displayTerm.trim())
                if (term.caseSensitive) put("caseSensitive", true)
                if (!term.enabled) put("enabled", false)
            }) } })
            .apply {
                draft.apiKey.takeIf(String::isNotBlank)?.let { put("apiKey", it) }
                draft.retryOf?.let { put("retryOf", it) }
            }
    }
    fun glossaryEntry(json: JSONObject): ServerJobGlossaryEntry = ServerJobGlossaryEntry(json.string("source").trim(), json.string("target").trim(),
        if (json.has("kind")) json.string("kind") else "Character", if (json.has("displayTerm")) json.string("displayTerm", true).trim() else "",
        if (json.has("caseSensitive")) json.boolean("caseSensitive") else false, if (json.has("enabled")) json.boolean("enabled") else true).also {
        require(it.source.length in 1..200 && it.target.length in 1..200 && it.displayTerm.length <= 200 && it.kind in setOf("Character", "Place", "Term"))
    }
    private fun validateUuid(value: String) { require(UUID.fromString(value).toString() == value) }
    private fun JSONObject.uuid(key: String) = string(key).also(::validateUuid)
    private fun JSONObject.string(key: String, blank: Boolean = false) = (get(key) as? String)?.also { require(blank || it.isNotBlank()) } ?: error("Invalid job string")
    private fun JSONObject.optional(key: String): String? = if (!has(key) || isNull(key)) null else string(key)
    private fun JSONObject.boolean(key: String) = get(key) as? Boolean ?: error("Invalid job flag")
    private fun JSONObject.integer(key: String): Int = get(key).let {
        require(it is Int || it is Long)
        (it as Number).toLong().also { value -> require(value in 0..Int.MAX_VALUE) }.toInt()
    }
}
