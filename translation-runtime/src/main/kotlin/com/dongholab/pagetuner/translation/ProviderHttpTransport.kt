package com.dongholab.pagetuner.translation

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded, cancellable HTTP transport shared by every platform and provider. */
class ProviderHttpTransport(
    private val providerName: String,
    private val connectTimeoutMillis: Int = 15_000,
    private val readTimeoutMillis: Int = 60_000,
    private val requestTimeoutMillis: Long = 75_000,
    private val maxResponseBytes: Int = 4 * 1024 * 1024,
    private val allowInsecureHttp: Boolean = false,
    private val isRateLimitRedirect: (source: URI, target: URI) -> Boolean = { _, _ -> false },
    private val connectionFactory: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    init {
        require(connectTimeoutMillis > 0 && readTimeoutMillis > 0 && requestTimeoutMillis > 0)
        require(maxResponseBytes > 0)
    }

    suspend fun post(endpoint: String, headers: Map<String, String>, body: String): String {
        val uri = runCatching { URI(endpoint) }.getOrNull()
            ?: throw providerConfigurationException(providerName, "Invalid provider endpoint.")
        if (!uri.isAllowedProviderEndpoint(allowInsecureHttp)) {
            throw providerConfigurationException(providerName, "Provider endpoint requires HTTPS (HTTP is allowed for loopback development).")
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        return try {
            withTimeout(requestTimeoutMillis) {
                withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { continuation ->
                    val connection = try {
                        connectionFactory(uri.toURL())
                    } catch (_: IOException) {
                        continuation.resumeWithException(providerNetworkException(providerName, "Connection failed."))
                        return@suspendCancellableCoroutine
                    }
                    continuation.invokeOnCancellation { runCatching { connection.disconnect() } }
                    try {
                        if (!continuation.isActive) return@suspendCancellableCoroutine
                        connection.requestMethod = "POST"
                        connection.instanceFollowRedirects = false
                        connection.connectTimeout = connectTimeoutMillis
                        connection.readTimeout = readTimeoutMillis
                        connection.doOutput = true
                        connection.setFixedLengthStreamingMode(bytes.size)
                        headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
                        connection.outputStream.use { it.write(bytes) }
                        val status = connection.responseCode
                        // Never forward credentials or source text to a redirected endpoint.
                        if (status in 300..399) {
                            val target = runCatching { URI(connection.getHeaderField("Location").orEmpty()) }.getOrNull()
                            if (target != null && isRateLimitRedirect(uri, target)) {
                                throw TranslationProviderException(TranslationProviderFailure(
                                    providerName, TranslationProviderErrorKind.RateLimited, "Provider requests are temporarily limited.",
                                ))
                            }
                            throw providerHttpException(providerName, status, "Redirect refused.")
                        }
                        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                        val response = stream?.use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                if (!continuation.isActive) return@suspendCancellableCoroutine
                                val count = input.read(buffer)
                                if (count < 0) break
                                if (output.size() + count > maxResponseBytes) {
                                    throw providerResponseFormatException(providerName, "Provider response exceeded the size limit.")
                                }
                                output.write(buffer, 0, count)
                            }
                            output.toString(Charsets.UTF_8.name())
                        }.orEmpty()
                        // Provider error bodies can echo credentials or the submitted chapter.
                        if (status !in 200..299) throw providerHttpException(providerName, status, "Request failed.")
                        if (continuation.isActive) continuation.resume(response)
                    } catch (error: Exception) {
                        val safeError = if (error is IOException && error !is TranslationProviderException) {
                            providerNetworkException(providerName, "Connection failed.")
                        } else error
                        if (continuation.isActive) continuation.resumeWithException(safeError)
                    } finally {
                        runCatching { connection.disconnect() }
                    }
                }
            }
            }
        } catch (_: TimeoutCancellationException) {
            // A transport deadline is a bounded retryable failure; an outer job cancellation is not.
            currentCoroutineContext().ensureActive()
            throw providerNetworkException(providerName, "Provider request timed out.")
        }
    }
}

internal fun URI.isAllowedProviderEndpoint(allowInsecureHttp: Boolean = false): Boolean {
    val endpointHost = host?.lowercase().orEmpty().removeSurrounding("[", "]")
    val loopback = endpointHost in setOf("localhost", "127.0.0.1", "::1")
    return endpointHost.isNotBlank() && rawUserInfo == null && rawFragment == null &&
        (scheme == "https" || (scheme == "http" && (loopback || allowInsecureHttp)))
}
