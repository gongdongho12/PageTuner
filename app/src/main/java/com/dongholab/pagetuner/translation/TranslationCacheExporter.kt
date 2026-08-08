package com.dongholab.pagetuner.translation

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object TranslationCacheExporter {
    suspend fun exportCacheToUri(
        context: Context,
        cacheFile: File,
        destinationUri: Uri,
    ): Boolean {
        return withContext(Dispatchers.IO) {
            runCatching {
                if (!cacheFile.exists()) return@withContext false
                context.contentResolver.openOutputStream(destinationUri)?.use { output ->
                    cacheFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                } ?: return@withContext false
                true
            }.getOrDefault(false)
        }
    }
}
