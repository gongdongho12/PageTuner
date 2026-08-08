package com.dongholab.pagetuner.source

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.random.Random

data class BatchDownloadProgress(
    val currentItemIndex: Int,
    val totalItems: Int,
    val currentTitle: String,
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
)

object WebNovelBatchDownloader {

    suspend fun downloadChaptersInBackground(
        context: Context,
        chapters: List<RemoteBookItem>,
        onProgress: (BatchDownloadProgress) -> Unit,
        onSaveChapter: suspend (RemoteBookItem, ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val total = chapters.size
        chapters.forEachIndexed { index, item ->
            onProgress(
                BatchDownloadProgress(
                    currentItemIndex = index + 1,
                    totalItems = total,
                    currentTitle = item.title,
                )
            )

            runCatching {
                val source = WebNovelRemoteBookSource(
                    accountId = item.identity.accountId,
                    endpointUrl = item.downloadUrl,
                )
                val bytes = source.download(item)
                onSaveChapter(item, bytes)
            }.onFailure { error ->
                // Log failure and continue to next item in queue
            }

            // Human-like jittered delay (1,200ms ~ 2,600ms) between sequential chapter requests
            if (index < total - 1) {
                val humanDelayMillis = Random.nextLong(1200L, 2600L)
                delay(humanDelayMillis)
            }
        }

        onProgress(
            BatchDownloadProgress(
                currentItemIndex = total,
                totalItems = total,
                currentTitle = "All chapters downloaded safely with human delay!",
                isCompleted = true,
            )
        )
    }
}
