package com.dongholab.pagetuner.source

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PageTurnerWebCatalogNetwork {
    suspend fun fetchString(url: String): String = withContext(Dispatchers.IO) {
        fetchBytes(url).toString(Charsets.UTF_8)
    }

    suspend fun fetchBytes(
        url: String,
        maxBytes: Int? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
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
            connection.inputStream.use { inputStream ->
                if (maxBytes == null) {
                    inputStream.readBytes()
                } else {
                    inputStream.readBytes(maxBytes)
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readBytes(maxBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0
        while (true) {
            val count = read(buffer)
            if (count == -1) break
            totalBytes += count
            if (totalBytes > maxBytes) {
                throw IOException("Remote file is larger than $maxBytes bytes.")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
