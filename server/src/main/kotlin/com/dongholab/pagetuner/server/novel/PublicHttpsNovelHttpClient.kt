package com.dongholab.pagetuner.server.novel

import com.dongholab.pagetuner.source.WebNovelRequestGate
import com.dongholab.pagetuner.source.service.NovelHttpTransport
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class UnsafeNovelUrl(message: String) : IllegalArgumentException(message)
class NovelProviderFailure(val status: Int) : IOException("The novel provider returned HTTP $status.")
data class PublicHttpsContent(val url: String, val bytes: ByteArray, val contentType: String?)

/** Uses the validated DNS answers for the actual socket, not a separate preflight lookup. */
class PublicHttpsNovelHttpClient internal constructor(private val client: OkHttpClient) : NovelHttpTransport {
    constructor() : this(OkHttpClient.Builder()
        .dns(PublicNovelDns()).proxy(Proxy.NO_PROXY)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS).build())
    private val permits = Semaphore(8)

    override suspend fun fetchText(url: String): String = text(request(url, null, null, MAX_RESPONSE_BYTES))

    /** Reuses the same DNS, redirect, cancellation, rate and concurrency policy for catalog files. */
    suspend fun fetchContent(url: String, maxBytes: Int = MAX_RESPONSE_BYTES): PublicHttpsContent {
        require(maxBytes in 1..MAX_FILE_BYTES) { "Invalid response byte limit." }
        return request(url, null, null, maxBytes)
    }

    private fun text(content: PublicHttpsContent): String = content.bytes.toString(Charsets.UTF_8)
        .takeIf(String::isNotBlank) ?: throw IOException("The novel provider returned an empty response.")

    override suspend fun postJson(url: String, body: String, referer: String): String {
        require(body.toByteArray(Charsets.UTF_8).size <= 64 * 1024) { "Provider request is too large." }
        val target = PublicNovelTargets.url(url)
        val referring = PublicNovelTargets.url(referer)
        require(target.host == referring.host) { "Provider POST and referer must have the same host." }
        return text(request(url, body, referer, MAX_RESPONSE_BYTES))
    }

    private suspend fun request(url: String, body: String?, referer: String?, maxBytes: Int): PublicHttpsContent = withTimeout(60_000) {
        permits.withPermit {
            var target = PublicNovelTargets.url(url)
            var postBody = body
            var redirects = 0
            while (true) {
                WebNovelRequestGate.awaitPermit(target.toString())
                val builder = Request.Builder().url(target)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9")
                postBody?.let {
                    builder.post(it.toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .header("Origin", "https://${target.host}")
                        .header("Referer", requireNotNull(referer))
                }
                val response = execute(builder.build(), maxBytes)
                if (response.status in setOf(301, 302, 303, 307, 308)) {
                    if (++redirects > 5) throw IOException("The novel provider redirected too many times.")
                    val location = response.location ?: throw IOException("Provider redirect has no location.")
                    val next = PublicNovelTargets.url(URI(target.toString()).resolve(location).toString())
                    // Reader POSTs never forward request bodies or referring URLs to a different origin.
                    if (postBody != null && next.host != target.host) {
                        throw UnsafeNovelUrl("Cross-origin provider POST redirects are not supported.")
                    }
                    if (response.status == 303) postBody = null
                    target = next
                    continue
                }
                if (response.status == 429 || response.status == 503) {
                    val retryAfter = response.retryAfter?.toLongOrNull()?.coerceIn(0, 30)?.times(1_000)
                    WebNovelRequestGate.recordThrottled(target.toString(), retryAfter)
                }
                if (response.status !in 200..299) throw NovelProviderFailure(response.status)
                WebNovelRequestGate.recordSuccess(target.toString())
                val bytes = response.body?.takeIf { it.isNotEmpty() }
                    ?: throw IOException("The novel provider returned an empty response.")
                return@withPermit PublicHttpsContent(target.toString(), bytes, response.contentType)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IOException("No provider response.")
        }
    }

    private suspend fun execute(request: Request, maxBytes: Int): ProviderResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use {
                        val bytes = if (response.isSuccessful) {
                            val responseBody = response.body ?: throw IOException("Missing provider response body.")
                            if (responseBody.contentLength() > maxBytes) {
                                throw IOException("The provider response exceeded $maxBytes bytes.")
                            }
                            // OkHttp transparently decompresses gzip; bound the decoded bytes too.
                            responseBody.byteStream().use { input ->
                                val output = ByteArrayOutputStream()
                                val buffer = ByteArray(8_192)
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    if (output.size() + count > maxBytes) {
                                        throw IOException("The provider response exceeded $maxBytes bytes.")
                                    }
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                        } else null
                        ProviderResponse(response.code, response.header("Location"), response.header("Retry-After"), bytes, response.header("Content-Type"))
                    }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private data class ProviderResponse(val status: Int, val location: String?, val retryAfter: String?, val body: ByteArray?, val contentType: String?)

    companion object {
        private const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
        const val MAX_FILE_BYTES = 32 * 1024 * 1024
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    }
}

internal object PublicNovelTargets {
    fun url(value: String): HttpUrl {
        if (value.length > 4_096 || value.any { it.isISOControl() } || '\\' in value) {
            throw UnsafeNovelUrl("A valid public HTTPS URL is required.")
        }
        val uri = runCatching { URI(value) }.getOrNull()
            ?: throw UnsafeNovelUrl("A valid public HTTPS URL is required.")
        val parsed = value.toHttpUrlOrNull() ?: throw UnsafeNovelUrl("A valid public HTTPS URL is required.")
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null ||
            parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            throw UnsafeNovelUrl("Only public HTTPS URLs on port 443 without credentials or fragments are supported.")
        }
        val host = parsed.host.lowercase().trimEnd('.')
        if (!host.contains('.') || host.contains(':') || host.all { it.isDigit() || it == '.' } ||
            host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            throw UnsafeNovelUrl("A public DNS hostname is required.")
        }
        return parsed
    }

    fun addresses(values: List<InetAddress>): List<InetAddress> {
        if (values.isEmpty() || values.any { !isPublic(it) }) {
            throw UnknownHostException("The novel host did not resolve exclusively to public addresses.")
        }
        return values
    }

    private fun isPublic(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address.map { it.toInt() and 255 }
        return if (bytes.size == 4) {
            val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
            a !in setOf(0, 10, 127) && a < 224 &&
                !(a == 100 && b in 64..127) && !(a == 169 && b == 254) &&
                !(a == 172 && b in 16..31) && !(a == 192 && b == 168) &&
                !(a == 192 && b == 0 && c in setOf(0, 2)) && !(a == 192 && b == 88 && c == 99) &&
                !(a == 198 && b in 18..19) && !(a == 198 && b == 51 && c == 100) &&
                !(a == 203 && b == 0 && c == 113)
        } else {
            bytes.size == 16 && (bytes[0] and 0xe0) == 0x20 &&
                !(bytes[0] == 0x20 && bytes[1] == 0x02) &&
                !(bytes[0] == 0x20 && bytes[1] == 0x01 &&
                    ((bytes[2] == 0 && bytes[3] in 0..0x2f) || (bytes[2] == 0x0d && bytes[3] == 0xb8)))
        }
    }
}

/** Resolution has its own deadline and bounded workers, including when the OS resolver stalls. */
internal class PublicNovelDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val future = try {
            resolver.submit<List<InetAddress>> { InetAddress.getAllByName(hostname).toList() }
        } catch (error: Exception) {
            throw UnknownHostException("Provider DNS resolver is busy.").apply { initCause(error) }
        }
        return try {
            PublicNovelTargets.addresses(future.get(5, TimeUnit.SECONDS))
        } catch (error: Exception) {
            future.cancel(true)
            throw UnknownHostException("Provider DNS resolution failed or was not public.").apply { initCause(error) }
        }
    }

    companion object {
        private val resolver = ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, ArrayBlockingQueue(16),
            { runnable -> Thread(runnable, "novel-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy())
    }
}
