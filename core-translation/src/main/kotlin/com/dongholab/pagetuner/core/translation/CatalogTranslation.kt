package com.dongholab.pagetuner.core.translation

/** Key is supplied by the platform adapter; display titles are never used as identities. */
data class CatalogTranslationEntry(val key: String, val title: String, val description: String?)

data class CatalogItemTranslation(val title: String, val description: String?, val targetLanguage: String)

/** Shared catalog-to-fields-to-translated-catalog flow, independent of Android and Spring. */
class CatalogTranslationService(private val translator: ContentTranslationPort) {
    suspend fun translate(
        items: List<CatalogTranslationEntry>,
        languages: TranslationLanguages,
        onProgress: suspend (ContentTranslationProgress) -> Unit = {},
    ): Map<String, CatalogItemTranslation> {
        val snapshot = items.toList()
        if (snapshot.isEmpty()) return emptyMap()
        require(snapshot.all { it.key.isNotBlank() }) { "Catalog entry keys must not be blank." }
        require(snapshot.map { it.key }.distinct().size == snapshot.size) { "Catalog entry keys must be unique." }
        val fields = snapshot.flatMap { entry ->
            listOfNotNull(
                TranslatableField("${entry.key}:title", entry.title),
                entry.description?.takeIf(String::isNotBlank)?.let {
                    TranslatableField("${entry.key}:description", it)
                },
            )
        }
        val result = translator.translate(
            ContentTranslationRequest("web-catalog-v1", "Web catalog", fields), languages, onProgress,
        )
        return snapshot.associate { entry ->
            entry.key to CatalogItemTranslation(
                title = result.values["${entry.key}:title"].orEmpty().ifBlank { entry.title },
                description = result.values["${entry.key}:description"]?.takeIf(String::isNotBlank),
                targetLanguage = result.targetLanguage,
            )
        }
    }
}
