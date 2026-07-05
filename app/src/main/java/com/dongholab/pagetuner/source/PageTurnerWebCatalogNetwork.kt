package com.dongholab.pagetuner.source

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PageTurnerWebCatalogNetwork {
    suspend fun fetchString(url: String): String = withContext(Dispatchers.IO) {
        fetchBytes(url).toString(Charsets.UTF_8)
    }

    suspend fun fetchBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("Only HTTP(S) catalog URLs are supported.")
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true

        try {
            val statusCode = connection.responseCode
            if (statusCode !in 200..299) {
                throw IOException("Remote source returned HTTP $statusCode.")
            }
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }
}
