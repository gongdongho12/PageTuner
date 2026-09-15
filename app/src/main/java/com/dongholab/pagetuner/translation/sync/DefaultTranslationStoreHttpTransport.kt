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
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** HttpURLConnection does not support PATCH on every supported JVM. Keep its existing GET/POST path. */
class DefaultTranslationStoreHttpTransport(
    private val standard: TranslationStoreHttpTransport = UrlConnectionTranslationStoreTransport(),
    private val patch: TranslationStoreHttpTransport = PatchTranslationStoreHttpTransport(),
) : TranslationStoreHttpTransport {
    override suspend fun execute(request: TranslationStoreHttpRequest): TranslationStoreHttpResponse =
        if (request.method == "PATCH") patch.execute(request) else standard.execute(request)
}

class PatchTranslationStoreHttpTransport : TranslationStoreHttpTransport {
    override suspend fun execute(request: TranslationStoreHttpRequest): TranslationStoreHttpResponse = withContext(Dispatchers.IO) {
        require(request.method == "PATCH")
        val body = requireNotNull(request.body).toRequestBody("application/json; charset=utf-8".toMediaType())
        val builder = Request.Builder().url(request.url).patch(body)
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        val call = client.newCall(builder.build())
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            try {
                if (!continuation.isActive) return@suspendCancellableCoroutine
                val result = call.execute().use { response ->
                    val text = response.body?.byteStream()?.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (output.size() + count > 4 * 1024 * 1024) throw IOException("Account response exceeds the size limit.")
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }.orEmpty()
                    TranslationStoreHttpResponse(response.code, response.headers.toMultimap(), text)
                }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }
    private companion object {
        val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).callTimeout(45, TimeUnit.SECONDS).build()
    }
}
