package com.dongholab.pagetuner.source

import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.document.DocumentIds
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class GoogleDriveSourceConfig(
    val accessToken: String,
    val title: String = "Google Drive",
    val folderId: String? = null,
    val includeSharedDrives: Boolean = true,
    val accountId: String = defaultGoogleDriveAccountId(folderId, title),
)

data class GoogleDriveFile(
    val id: String,
    val name: String,
    val mimeType: String,
    val modifiedTime: String? = null,
    val sizeBytes: Long? = null,
    val md5Checksum: String? = null,
    val webContentLink: String? = null,
    val thumbnailLink: String? = null,
) {
    val format: DocumentFormat?
        get() = toSupportedDriveDocumentFormatOrNull(name, mimeType)
}

data class GoogleDriveFilePage(
    val files: List<GoogleDriveFile>,
    val nextPageToken: String?,
)

interface GoogleDriveTransport {
    suspend fun listFiles(
        config: GoogleDriveSourceConfig,
        pageToken: String?,
    ): GoogleDriveFilePage

    suspend fun downloadFile(
        config: GoogleDriveSourceConfig,
        fileId: String,
    ): ByteArray
}

class GoogleDriveRemoteBookSource(
    private val config: GoogleDriveSourceConfig,
    private val transport: GoogleDriveTransport = HttpGoogleDriveTransport(),
) : RemoteBookSource {
    override val sourceType: RemoteSourceType = RemoteSourceType.GoogleDrive
    override val accountId: String = config.accountId

    override suspend fun connect(): RemoteSourceConnection {
        val items = list()
        return RemoteSourceConnection(
            sourceType = sourceType,
            accountId = accountId,
            title = config.title,
            itemCount = items.size,
        )
    }

    override suspend fun list(): List<RemoteBookItem> {
        return listDriveFiles().toRemoteBookItems()
    }

    override suspend fun search(query: String): List<RemoteBookItem> {
        val normalizedQuery = query.trim().lowercase()
        if (normalizedQuery.isBlank()) return list()
        return list().filter { item ->
            item.title.lowercase().contains(normalizedQuery) ||
                item.format.name.lowercase().contains(normalizedQuery) ||
                item.contentType.orEmpty().lowercase().contains(normalizedQuery)
        }
    }

    override suspend fun download(item: RemoteBookItem): ByteArray {
        require(item.identity.sourceType == sourceType) {
            "Remote item belongs to ${item.identity.sourceType}, not $sourceType."
        }
        require(item.identity.accountId == accountId) {
            "Remote item belongs to ${item.identity.accountId}, not $accountId."
        }
        return transport.downloadFile(config, item.identity.remoteId)
    }

    override suspend fun refresh(): List<RemoteBookItem> {
        return list()
    }

    private suspend fun listDriveFiles(): List<GoogleDriveFile> {
        val files = mutableListOf<GoogleDriveFile>()
        var pageToken: String? = null
        var pages = 0
        do {
            val page = transport.listFiles(config, pageToken)
            files += page.files
            pageToken = page.nextPageToken
            pages += 1
        } while (pageToken != null && pages < MaxListPages)
        return files
    }

    private fun List<GoogleDriveFile>.toRemoteBookItems(): List<RemoteBookItem> {
        return filter { file -> file.format != null }
            .map { file ->
                RemoteBookItem(
                    identity = RemoteBookIdentity(
                        sourceType = sourceType,
                        accountId = accountId,
                        remoteId = file.id,
                    ),
                    title = file.name.removeDriveSupportedExtension(),
                    format = requireNotNull(file.format),
                    downloadUrl = "google-drive://file/${file.id}",
                    contentType = file.mimeType,
                    sizeBytes = file.sizeBytes,
                    checksum = file.md5Checksum?.let { checksum -> "md5:$checksum" },
                    updatedAt = file.modifiedTime,
                    coverUrl = file.thumbnailLink,
                )
            }
    }

    private companion object {
        const val MaxListPages = 20
    }
}

class HttpGoogleDriveTransport : GoogleDriveTransport {
    override suspend fun listFiles(
        config: GoogleDriveSourceConfig,
        pageToken: String?,
    ): GoogleDriveFilePage = withContext(Dispatchers.IO) {
        val url = buildListUrl(config, pageToken)
        val response = executeGet(url, config.accessToken)
        GoogleDriveFileListParser.parse(response)
    }

    override suspend fun downloadFile(
        config: GoogleDriveSourceConfig,
        fileId: String,
    ): ByteArray = withContext(Dispatchers.IO) {
        val encodedFileId = fileId.urlEncode()
        executeGetBytes(
            url = "https://www.googleapis.com/drive/v3/files/$encodedFileId?alt=media",
            accessToken = config.accessToken,
        )
    }

    private fun buildListUrl(
        config: GoogleDriveSourceConfig,
        pageToken: String?,
    ): String {
        val query = buildString {
            append("trashed = false")
            config.folderId?.takeIf { it.isNotBlank() }?.let { folderId ->
                append(" and '")
                append(folderId.replace("'", "\\'"))
                append("' in parents")
            }
        }
        val params = mutableListOf(
            "pageSize" to "100",
            "q" to query,
            "fields" to "nextPageToken,files(id,name,mimeType,modifiedTime,size,md5Checksum,webContentLink,thumbnailLink)",
            "supportsAllDrives" to config.includeSharedDrives.toString(),
            "includeItemsFromAllDrives" to config.includeSharedDrives.toString(),
        )
        pageToken?.let { params += "pageToken" to it }

        return "https://www.googleapis.com/drive/v3/files?" +
            params.joinToString("&") { (name, value) -> "${name.urlEncode()}=${value.urlEncode()}" }
    }

    private fun executeGet(url: String, accessToken: String): String {
        return execute(url, accessToken) { connection ->
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    }

    private fun executeGetBytes(url: String, accessToken: String): ByteArray {
        return execute(url, accessToken) { connection ->
            connection.inputStream.use { it.readBytes() }
        }
    }

    private fun <T> execute(
        url: String,
        accessToken: String,
        reader: (HttpURLConnection) -> T,
    ): T {
        require(accessToken.isNotBlank()) { "Google Drive access token is required." }
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("Authorization", "Bearer ${accessToken.trim()}")
            setRequestProperty("Accept", "application/json")
        }
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            val response = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            connection.disconnect()
            throw IOException("Google Drive request failed: HTTP $responseCode $response")
        }
        return try {
            reader(connection)
        } finally {
            connection.disconnect()
        }
    }
}

object GoogleDriveFileListParser {
    fun parse(rawJson: String): GoogleDriveFilePage {
        val root = JSONObject(rawJson)
        val files = root.optJSONArray("files")
        return GoogleDriveFilePage(
            files = if (files == null) {
                emptyList()
            } else {
                (0 until files.length()).mapNotNull { index ->
                    files.optJSONObject(index)?.toGoogleDriveFile()
                }
            },
            nextPageToken = root.optString("nextPageToken").takeIf { it.isNotBlank() },
        )
    }

    private fun JSONObject.toGoogleDriveFile(): GoogleDriveFile? {
        val id = optString("id")
        val name = optString("name")
        val mimeType = optString("mimeType")
        if (id.isBlank() || name.isBlank() || mimeType.isBlank()) return null

        return GoogleDriveFile(
            id = id,
            name = name,
            mimeType = mimeType,
            modifiedTime = optString("modifiedTime").takeIf { it.isNotBlank() },
            sizeBytes = optString("size").toLongOrNull(),
            md5Checksum = optString("md5Checksum").takeIf { it.isNotBlank() },
            webContentLink = optString("webContentLink").takeIf { it.isNotBlank() },
            thumbnailLink = optString("thumbnailLink").takeIf { it.isNotBlank() },
        )
    }
}

private fun defaultGoogleDriveAccountId(
    folderId: String?,
    title: String,
): String {
    return DocumentIds.sha256(
        listOf("google-drive", folderId.orEmpty(), title.trim()).joinToString("|"),
    ).take(16)
}

private fun toSupportedDriveDocumentFormatOrNull(
    name: String,
    mimeType: String,
): DocumentFormat? {
    val lowerName = name.lowercase()
    val lowerMime = mimeType.lowercase()
    return when {
        lowerMime == "application/epub+zip" || lowerName.endsWith(".epub") -> DocumentFormat.EPUB
        lowerMime == "application/pdf" || lowerName.endsWith(".pdf") -> DocumentFormat.PDF
        lowerMime == "text/markdown" ||
            lowerMime == "text/x-markdown" ||
            lowerName.endsWith(".md") ||
            lowerName.endsWith(".markdown") -> DocumentFormat.MARKDOWN
        lowerMime == "text/plain" || lowerName.endsWith(".txt") -> DocumentFormat.TEXT
        else -> null
    }
}

private fun String.removeDriveSupportedExtension(): String {
    return replace(Regex("""\.(epub|pdf|md|markdown|txt)$""", RegexOption.IGNORE_CASE), "")
}

private fun String.urlEncode(): String {
    return URLEncoder.encode(this, Charsets.UTF_8.name()).replace("+", "%20")
}
