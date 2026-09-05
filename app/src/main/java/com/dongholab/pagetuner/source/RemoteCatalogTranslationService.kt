package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.core.translation.CatalogTranslationEntry
import com.dongholab.pagetuner.core.translation.CatalogTranslationService
import com.dongholab.pagetuner.core.translation.TranslationLanguages
import com.dongholab.pagetuner.translation.ContentTranslationService
import com.dongholab.pagetuner.translation.TranslationProgress
import com.dongholab.pagetuner.translation.TranslationSettings

typealias CatalogItemTranslation = com.dongholab.pagetuner.core.translation.CatalogItemTranslation

fun RemoteBookItem.translationKey(): String {
    val workKey = seriesId?.trim()?.takeIf(String::isNotBlank) ?: "standalone"
    return "${identity.sourceType}:${identity.accountId}:$workKey:${identity.remoteId}"
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
        val sharedService = CatalogTranslationService { request, languages, progress ->
            contentTranslationService.translate(
                request,
                settings.copy(sourceLanguage = languages.source, targetLanguage = languages.target),
                progress,
            )
        }
        return sharedService.translate(
            items.map { CatalogTranslationEntry(it.translationKey(), it.title, it.description) },
            TranslationLanguages(settings.normalizedSourceLanguage, settings.normalizedTargetLanguage),
            onProgress,
        )
    }
}
