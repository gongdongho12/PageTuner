package com.dongholab.pagetuner.source

import android.content.Context
import com.dongholab.pagetuner.source.offline.OfflineNovelStorageStore
import com.dongholab.pagetuner.translation.ContentTranslationRequest
import com.dongholab.pagetuner.translation.ContentTranslationService
import com.dongholab.pagetuner.translation.ContentTranslationServiceFactory
import com.dongholab.pagetuner.translation.TranslatableField
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.glossary.BookGlossaryStore
import com.dongholab.pagetuner.library.remoteLibraryIdentityOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

enum class BatchDownloadStage {
    DownloadingOriginal,
    Translating,
    Saved,
    Completed,
}

data class BatchDownloadProgress(
    val currentItemIndex: Int,
    val totalItems: Int,
    val currentTitle: String,
    val stage: BatchDownloadStage = BatchDownloadStage.DownloadingOriginal,
    val translatedPart: Int = 0,
    val totalTranslationParts: Int = 0,
    val savedItems: Int = 0,
    val failedItems: Int = 0,
    val targetLanguage: String? = null,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
) {
    val fraction: Float
        get() = if (totalItems == 0) 1f else {
            val chapterBase = (currentItemIndex - 1).coerceAtLeast(0).toFloat()
            val withinChapter = when (stage) {
                BatchDownloadStage.DownloadingOriginal -> 0.15f
                BatchDownloadStage.Translating -> {
                    if (totalTranslationParts == 0) 0.5f
                    else 0.15f + (translatedPart.toFloat() / totalTranslationParts) * 0.75f
                }
                BatchDownloadStage.Saved, BatchDownloadStage.Completed -> 1f
            }
            ((chapterBase + withinChapter) / totalItems).coerceIn(0f, 1f)
        }
}

data class BatchDownloadResult(
    val totalItems: Int,
    val savedItems: Int,
    val failedItems: Int,
    val targetLanguage: String?,
)

fun interface WebNovelChapterDownloadClient {
    suspend fun download(item: RemoteBookItem): ByteArray
}

object AdapterBackedWebNovelChapterDownloadClient : WebNovelChapterDownloadClient {
    override suspend fun download(item: RemoteBookItem): ByteArray {
        return WebNovelRemoteBookSource(
            accountId = item.identity.accountId,
            endpointUrl = item.downloadUrl,
        ).download(item)
    }
}

object WebNovelBatchDownloader {

    suspend fun downloadChaptersInBackground(
        context: Context,
        chapters: List<RemoteBookItem>,
        settings: TranslationSettings,
        includeTranslation: Boolean = true,
        store: OfflineNovelStorageStore = OfflineNovelStorageStore.globalOfflineStore,
        translationService: ContentTranslationService? = null,
        chapterDownloadClient: WebNovelChapterDownloadClient = AdapterBackedWebNovelChapterDownloadClient,
        onProgress: (BatchDownloadProgress) -> Unit,
        onSaveChapter: suspend (RemoteBookItem, ByteArray) -> Unit = { _, _ -> },
    ): BatchDownloadResult = withContext(Dispatchers.IO) {
        require(!includeTranslation || settings.isProviderConfigured) {
            "Configure a translation provider before downloading translated chapters."
        }

        val targetLanguage = settings.normalizedTargetLanguage.takeIf { includeTranslation }
        val glossary = chapters.firstOrNull()
            ?.remoteLibraryIdentityOrNull()
            ?.localBookId
            ?.let { BookGlossaryStore(context.applicationContext).load(it) }
        val translator = translationService
            ?: ContentTranslationServiceFactory.create(context.applicationContext, settings, glossary)
        var savedItems = 0
        var failedItems = 0

        chapters.forEachIndexed { index, item ->
            coroutineContext.ensureActive()
            val itemNumber = index + 1
            onProgress(
                BatchDownloadProgress(
                    currentItemIndex = itemNumber,
                    totalItems = chapters.size,
                    currentTitle = item.title,
                    savedItems = savedItems,
                    failedItems = failedItems,
                    targetLanguage = targetLanguage,
                ),
            )

            runCatching {
                val existing = store.getOfflineChapter(item)
                val originalText = existing?.originalText ?: run {
                    chapterDownloadClient.download(item).toString(Charsets.UTF_8)
                }
                val bytes = originalText.toByteArray(Charsets.UTF_8)

                // Persist the original first. Translation/network failures cannot erase it.
                if (existing == null) store.saveOriginalChapter(item, itemNumber, originalText)
                onSaveChapter(item, bytes)

                val alreadyTranslated = existing?.translations
                    ?.containsKey(settings.normalizedTargetLanguage.lowercase()) == true
                if (includeTranslation && !alreadyTranslated) {
                    val bodyFieldId = "${item.translationKey()}:body"
                    val translation = translator.translate(
                        request = ContentTranslationRequest(
                            namespace = "web-novel-chapter-v1",
                            title = item.title,
                            fields = listOf(
                                TranslatableField(
                                    id = bodyFieldId,
                                    text = originalText,
                                ),
                            ),
                        ),
                        settings = settings.copy(paceMode = TranslationPaceMode.OFFLINE_PREFETCH),
                        onProgress = { progress ->
                            coroutineContext.ensureActive()
                            onProgress(
                                BatchDownloadProgress(
                                    currentItemIndex = itemNumber,
                                    totalItems = chapters.size,
                                    currentTitle = item.title,
                                    stage = BatchDownloadStage.Translating,
                                    translatedPart = progress.completedSegments,
                                    totalTranslationParts = progress.totalSegments,
                                    savedItems = savedItems,
                                    failedItems = failedItems,
                                    targetLanguage = targetLanguage,
                                ),
                            )
                        },
                    )
                    val translatedBody = translation.values[bodyFieldId]
                        ?.takeIf(String::isNotBlank)
                        ?: error("Translation provider returned no chapter body.")
                    store.saveTranslation(
                        item = item,
                        chapterNumber = itemNumber,
                        targetLanguage = settings.normalizedTargetLanguage,
                        translatedText = translatedBody,
                        providerId = translation.providerId,
                    )
                }
            }.onSuccess {
                savedItems += 1
                onProgress(
                    BatchDownloadProgress(
                        currentItemIndex = itemNumber,
                        totalItems = chapters.size,
                        currentTitle = item.title,
                        stage = BatchDownloadStage.Saved,
                        savedItems = savedItems,
                        failedItems = failedItems,
                        targetLanguage = targetLanguage,
                    ),
                )
            }.onFailure { error ->
                if (error is CancellationException) throw error
                failedItems += 1
                onProgress(
                    BatchDownloadProgress(
                        currentItemIndex = itemNumber,
                        totalItems = chapters.size,
                        currentTitle = item.title,
                        stage = BatchDownloadStage.Saved,
                        savedItems = savedItems,
                        failedItems = failedItems,
                        targetLanguage = targetLanguage,
                        errorMessage = error.message ?: error::class.java.simpleName,
                    ),
                )
            }

            // Sequential access protects rendered WebView loading and avoids source throttling.
            if (index < chapters.lastIndex) delay(1_200L)
        }

        onProgress(
            BatchDownloadProgress(
                currentItemIndex = chapters.size,
                totalItems = chapters.size,
                currentTitle = "Offline package is ready",
                stage = BatchDownloadStage.Completed,
                savedItems = savedItems,
                failedItems = failedItems,
                targetLanguage = targetLanguage,
                isCompleted = true,
                errorMessage = if (failedItems > 0) "$failedItems chapter(s) could not be saved." else null,
            ),
        )
        BatchDownloadResult(chapters.size, savedItems, failedItems, targetLanguage)
    }
}
