package com.dongholab.pagetuner.library

import com.dongholab.pagetuner.document.DocumentIds
import com.dongholab.pagetuner.source.RemoteBookItem

data class RemoteLibraryIdentity(
    val localBookId: String,
    val sourceType: String,
    val accountId: String,
    val seriesId: String,
)

fun RemoteBookItem.remoteLibraryIdentityOrNull(): RemoteLibraryIdentity? {
    val stableSeriesId = seriesId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val sourceType = identity.sourceType.name
    return RemoteLibraryIdentity(
        localBookId = DocumentIds.sha256("$sourceType|${identity.accountId}|$stableSeriesId").take(24),
        sourceType = sourceType,
        accountId = identity.accountId,
        seriesId = stableSeriesId,
    )
}

fun LocalBook.belongsTo(identity: RemoteLibraryIdentity): Boolean {
    return remoteSourceType == identity.sourceType &&
        remoteAccountId == identity.accountId &&
        remoteSeriesId == identity.seriesId
}
