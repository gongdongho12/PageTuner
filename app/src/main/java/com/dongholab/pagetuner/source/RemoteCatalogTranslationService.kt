package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.translation.ContentTranslationRequest
import com.dongholab.pagetuner.translation.ContentTranslationService
import com.dongholab.pagetuner.translation.TranslatableField
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationSettings

data class CatalogItemTranslation(
    val title: String,
    val description: String?,
    val targetLanguage: String,
)

fun RemoteBookItem.translationKey(): String {
    return "${identity.sourceType}:${identity.accountId}:${identity.remoteId}"
}

interface RemoteCatalogTranslationService {
    suspend fun translate(
        items: List<RemoteBookItem>,
        settings: TranslationSettings,
        onProgress: suspend (TranslationProgress) -> Unit = {},
    ): Map<String, CatalogItemTranslation>
}

class DefaultRemoteCatalogTranslationService(
    private val contentTranslationService: ContentTranslationService,
) : RemoteCatalogTranslationService {
    override suspend fun translate(
        items: List<RemoteBookItem>,
        settings: TranslationSettings,
        onProgress: suspend (TranslationProgress) -> Unit,
    ): Map<String, CatalogItemTranslation> {
        if (items.isEmpty()) return emptyMap()
        val fields = items.flatMap { item ->
            val key = item.translationKey()
            listOfNotNull(
                TranslatableField("$key:title", item.title),
                item.description?.takeIf(String::isNotBlank)?.let {
                    TranslatableField("$key:description", it)
                },
            )
        }
        val result = contentTranslationService.translate(
            request = ContentTranslationRequest(
                namespace = "web-catalog-v1",
                title = "Web catalog",
                fields = fields,
            ),
            settings = settings,
            onProgress = onProgress,
        )
        return items.associate { item ->
            val key = item.translationKey()
            key to CatalogItemTranslation(
                title = result.values["$key:title"].orEmpty().ifBlank { item.title },
                description = result.values["$key:description"]?.takeIf(String::isNotBlank),
                targetLanguage = result.targetLanguage,
            )
        }
    }
}
