package com.dongholab.pagetuner.source

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PageTurnerWebCatalogNetwork {
    private const val TAG = "PageTurnerCoverNetwork"

    suspend fun fetchString(url: String): String = withContext(Dispatchers.IO) {
        fetchBytes(url).toString(Charsets.UTF_8)
    }

    suspend fun fetchBytes(
        url: String,
        maxBytes: Int? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        var currentUrl = unwrapNextJsImageUrl(url)
        repeat(5) { redirectCount ->
            runCatching {
                val connection = URL(currentUrl).openConnection() as? HttpURLConnection
                    ?: throw IOException("Only HTTP(S) catalog URLs are supported.")

                connection.connectTimeout = 12_000
                connection.readTimeout = 20_000
                connection.instanceFollowRedirects = true

                val domain = runCatching { URL(currentUrl).host }.getOrDefault("wtr-lab.com")
                val origin = "https://$domain"

                // Chrome Browser Headers for Cover Image CDN & Next.js Image Proxy Fetching
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                connection.setRequestProperty("Referer", origin)
                connection.setRequestProperty("Origin", origin)
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
                connection.setRequestProperty("sec-ch-ua", "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\"")
                connection.setRequestProperty("sec-fetch-dest", "image")
                connection.setRequestProperty("sec-fetch-mode", "no-cors")
                connection.setRequestProperty("sec-fetch-site", "same-origin")

                val statusCode = connection.responseCode
                Log.d(TAG, "Fetching image [$redirectCount] URL: $currentUrl -> HTTP $statusCode")

                if (statusCode in 300..399) {
                    val location = connection.getHeaderField("Location")
                    if (!location.isNullOrBlank()) {
                        currentUrl = location
                        connection.disconnect()
                        return@repeat
                    }
                }

                if (statusCode !in 200..299) {
                    connection.disconnect()
                    throw IOException("Remote image returned HTTP $statusCode for $currentUrl")
                }

                val inputStream = if ("gzip".equals(connection.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(connection.inputStream)
                } else {
                    connection.inputStream
                }

                val bytes = inputStream.use { stream ->
                    if (maxBytes == null) stream.readBytes() else stream.readBytes(maxBytes)
                }
                connection.disconnect()
                Log.d(TAG, "Successfully fetched image (${bytes.size} bytes) from $url")
                return@withContext bytes
            }.onFailure { error ->
                Log.w(TAG, "Failed fetching image attempt $redirectCount from $currentUrl: ${error.message}")
            }
        }
        throw IOException("Failed to fetch image bytes from $url after redirects.")
    }

    private fun unwrapNextJsImageUrl(rawUrl: String): String {
        var processed = rawUrl.trim()
        if (processed.startsWith("//")) {
            processed = "https:$processed"
        } else if (processed.startsWith("/")) {
            processed = "https://wtr-lab.com$processed"
        }

        if (processed.contains("/_next/image") && processed.contains("url=")) {
            runCatching {
                val param = processed.substringAfter("url=").substringBefore("&")
                val decoded = URLDecoder.decode(param, "UTF-8")
                if (decoded.startsWith("http")) {
                    return decoded
                } else if (decoded.startsWith("/")) {
                    val domain = runCatching { URL(processed).host }.getOrDefault("wtr-lab.com")
                    return "https://$domain$decoded"
                }
            }
        }
        return processed
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
