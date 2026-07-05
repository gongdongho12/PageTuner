package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat

enum class RemoteSourceType {
    PageTurnerWebCatalog,
    FtpServer,
    GoogleDrive,
}

data class RemoteSourceConnection(
    val sourceType: RemoteSourceType,
    val accountId: String,
    val title: String,
    val itemCount: Int,
)

data class RemoteBookIdentity(
    val sourceType: RemoteSourceType,
    val accountId: String,
    val remoteId: String,
)

data class RemoteTranslationHints(
    val sourceLanguage: String = "auto",
    val targetLanguages: List<String> = emptyList(),
)

data class RemoteBookItem(
    val identity: RemoteBookIdentity,
    val title: String,
    val authors: List<String> = emptyList(),
    val format: DocumentFormat,
    val language: String? = null,
    val downloadUrl: String,
    val contentType: String? = null,
    val sizeBytes: Long? = null,
    val checksum: String? = null,
    val updatedAt: String? = null,
    val coverUrl: String? = null,
    val translationHints: RemoteTranslationHints = RemoteTranslationHints(),
)

interface RemoteBookSource {
    val sourceType: RemoteSourceType
    val accountId: String

    suspend fun connect(): RemoteSourceConnection

    suspend fun list(): List<RemoteBookItem>

    suspend fun search(query: String): List<RemoteBookItem>

    suspend fun download(item: RemoteBookItem): ByteArray

    suspend fun refresh(): List<RemoteBookItem>
}
