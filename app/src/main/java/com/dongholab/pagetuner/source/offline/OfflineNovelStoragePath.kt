package com.dongholab.pagetuner.source.offline

import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.source.RemoteBookItem

/** Stable, inspectable provider -> book -> chapter layout for offline novel packages. */
internal object OfflineNovelStoragePath {
    fun relativePath(item: RemoteBookItem, chapterNumber: Int): String {
        return "providers/${providerDirectory(item)}/books/${bookId(item)}/chapters/" +
            "${chapterNumber.coerceAtLeast(0).toString().padStart(8, '0')}-${chapterId(item)}.json"
    }

    fun chapterDirectory(item: RemoteBookItem): String =
        "providers/${providerDirectory(item)}/books/${bookId(item)}/chapters"

    fun chapterFileSuffix(item: RemoteBookItem): String = "-${chapterId(item)}.json"

    fun bookId(item: RemoteBookItem): String {
        val seriesId = item.seriesId?.trim()?.takeIf(String::isNotBlank)
            ?: item.identity.accountId
        return DocumentIds.sha256(
            "${item.identity.sourceType}|${item.identity.accountId}|$seriesId",
        ).take(24)
    }

    private fun providerDirectory(item: RemoteBookItem): String {
        val readable = item.identity.accountId
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .take(40)
            .ifBlank { "provider" }
        val identityHash = DocumentIds.sha256(
            "${item.identity.sourceType}|${item.identity.accountId}",
        ).take(10)
        return "$readable-$identityHash"
    }

    private fun chapterId(item: RemoteBookItem): String =
        DocumentIds.sha256(item.identity.remoteId).take(16)
}
