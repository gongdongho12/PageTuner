package com.dongholab.pagetuner.translation.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PdfContentHttpRequest(val url: String, val method: String, val headers: Map<String, String>,
    val body: ByteArray?, val maxResponseBytes: Int, val maxErrorBytes: Int = 64 * 1024)
data class PdfContentHttpResponse(val status: Int, val headers: Map<String, List<String>>, val body: ByteArray)
fun interface PdfContentHttpTransport { suspend fun execute(request: PdfContentHttpRequest): PdfContentHttpResponse }

internal class PdfContentTransportResponseException : IOException("Invalid PDF storage response.")

/** Separate byte transport: existing translation response limits and retry behavior remain unchanged. */
class DefaultPdfContentHttpTransport : PdfContentHttpTransport {
    override suspend fun execute(request: PdfContentHttpRequest): PdfContentHttpResponse = withContext(Dispatchers.IO) {
        require(request.maxResponseBytes > 0 && request.maxErrorBytes > 0)
        val body = request.body?.let { bytes -> object : RequestBody() {
            override fun contentType() = "application/json; charset=utf-8".toMediaType()
            override fun contentLength() = bytes.size.toLong()
            override fun writeTo(sink: BufferedSink) { sink.write(bytes) }
            override fun isOneShot() = true
        } }
        val responseStatus = ResponseStatus()
        val builder = Request.Builder().url(request.url).method(request.method, body)
            .tag(ResponseStatus::class.java, responseStatus)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        // Disables transparent gzip so wire and actual byte bounds cannot silently diverge.
        builder.header("Accept-Encoding", "identity")
        val call = client.newCall(builder.build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val response = call.execute().use { result ->
                    if (result.headers.values("Content-Encoding").let { it.size > 1 || it.any { value -> !value.equals("identity", true) } })
                        throw PdfContentTransportResponseException()
                    val status = responseStatus.original ?: result.code
                    val limit = if (status in 200..299) request.maxResponseBytes else request.maxErrorBytes
                    val bytes = result.body?.let { responseBody ->
                        if (responseBody.contentLength() > limit) throw PdfContentTransportResponseException()
                        responseBody.byteStream().use { input ->
                            val output = ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                if (output.size().toLong() + read > limit) throw PdfContentTransportResponseException()
                                output.write(buffer, 0, read)
                            }
                            output.toByteArray()
                        }
                    } ?: ByteArray(0)
                    PdfContentHttpResponse(status, result.headers.toMultimap(), bytes)
                }
                if (continuation.isActive) continuation.resume(response)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    private class ResponseStatus(var original: Int? = null)

    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp 4.12 follows 503 Retry-After:0 and coalesced HTTP/2 421 independently of
            // retryOnConnectionFailure(false). Hide those codes only inside its follow-up engine;
            // the caller receives the original status, headers and bytes without another request.
            if (response.code == 503 || response.code == 421) {
                requireNotNull(chain.request().tag(ResponseStatus::class.java)).original = response.code
                response.newBuilder().code(599).build()
            } else response
        }
        .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS).build()
}
