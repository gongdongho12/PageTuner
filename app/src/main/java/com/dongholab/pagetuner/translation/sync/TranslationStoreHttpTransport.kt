package com.dongholab.pagetuner.translation.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranslationStoreHttpRequest(
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val body: String? = null,
)

data class TranslationStoreHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String = "",
)

fun interface TranslationStoreHttpTransport {
    suspend fun execute(request: TranslationStoreHttpRequest): TranslationStoreHttpResponse
}

/** Each call owns its connection. Cancellation disconnects blocking I/O; redirects are never followed. */
class UrlConnectionTranslationStoreTransport(
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 30_000,
    private val maxResponseBytes: Int = 4 * 1024 * 1024,
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : TranslationStoreHttpTransport {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && maxResponseBytes > 0)
    }

    override suspend fun execute(request: TranslationStoreHttpRequest): TranslationStoreHttpResponse =
        withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                val connection = connectionFactory(URL(request.url))
                continuation.invokeOnCancellation { connection.disconnect() }
                try {
                    if (!continuation.isActive) return@suspendCancellableCoroutine
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = connectTimeoutMillis
                    connection.readTimeout = readTimeoutMillis
                    connection.requestMethod = request.method
                    connection.useCaches = false
                    request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                    request.body?.let { body ->
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        connection.outputStream.use { it.write(bytes) }
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > maxResponseBytes) {
                                throw IOException("Translation store response exceeds the size limit.")
                            }
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }.orEmpty()
                    // HttpURLConnection represents the HTTP status line with a null header name.
                    val rawHeaders: Map<String?, List<String>> = connection.headerFields
                    val headers = rawHeaders.mapNotNull { (name, values) -> name?.let { it to values } }.toMap()
                    val response = TranslationStoreHttpResponse(
                        status,
                        headers,
                        body,
                    )
                    continuation.resume(response)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    connection.disconnect()
                }
            }
        }
}
