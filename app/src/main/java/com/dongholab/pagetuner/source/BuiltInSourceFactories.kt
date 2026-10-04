package com.dongholab.pagetuner.source

import java.net.HttpURLConnection
import java.net.URL

class PageTurnerWebCatalogFactory : RemoteBookSourceFactory {
    override val sourceType: RemoteSourceType = RemoteSourceType.PageTurnerWebCatalog
    override val displayName: String = "PageTurner Web Catalog"
    override val description: String = "Static or REST API JSON catalog for e-ink books"

    override fun createSource(account: RemoteSourceAccount): RemoteBookSource {
        return PageTurnerWebCatalogSource(
            catalogUrl = account.endpoint,
            fetchCatalog = { urlStr ->
                val connection = URL(urlStr).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            },
            downloadBook = { item ->
                val connection = URL(item.downloadUrl).openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.inputStream.use { it.readBytes() }
            },
        )
    }
}

class FtpServerSourceFactory : RemoteBookSourceFactory {
    override val sourceType: RemoteSourceType = RemoteSourceType.FtpServer
    override val displayName: String = "FTP / FTPS Server"
    override val description: String = "Remote file server browsing and book downloading"

    override fun createSource(account: RemoteSourceAccount): RemoteBookSource {
        return FtpRemoteBookSource(
            config = FtpRemoteSourceConfig(
                host = account.endpoint,
                username = account.username ?: "anonymous",
                basePath = account.basePath ?: "/",
                accountId = account.id,
            ),
        )
    }
}

class GoogleDriveSourceFactory : RemoteBookSourceFactory {
    override val sourceType: RemoteSourceType = RemoteSourceType.GoogleDrive
    override val displayName: String = "Google Drive"
    override val description: String = "Cloud drive document reading and sync"

    override fun createSource(account: RemoteSourceAccount): RemoteBookSource {
        return GoogleDriveRemoteBookSource(
            config = GoogleDriveSourceConfig(
                accessToken = account.endpoint,
                accountId = account.id,
            ),
        )
    }
}

class WebNovelSourceFactory : RemoteBookSourceFactory {
    override val sourceType: RemoteSourceType = RemoteSourceType.WebNovel
    override val displayName: String = "Web Novel Reader"
    override val description: String = "Adapter-based web novel extraction and offline reading"

    override fun createSource(account: RemoteSourceAccount): RemoteBookSource {
        return WebNovelRemoteBookSource(
            accountId = account.id,
            endpointUrl = account.endpoint,
        )
    }
}
