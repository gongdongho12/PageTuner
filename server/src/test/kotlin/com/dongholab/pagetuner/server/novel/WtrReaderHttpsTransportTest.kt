package com.dongholab.pagetuner.server.novel

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** Synthetic OkHttp responses exercise transport policy without DNS or external requests. */
class WtrReaderHttpsTransportTest {
    @Test fun `signed content rejects even same origin redirects before a second request`() {
        val requests = mutableListOf<String>()
        val client = client { chain ->
            requests += chain.request().url.toString()
            response(chain, 302).header("Location", CONTENT_URL + "-redirected").build()
        }
        assertRedactedFailure(client)
        assertEquals(listOf(CONTENT_URL), requests)
    }

    @Test fun `signed content network errors discard sensitive messages and causes`() {
        val client = client { throw IOException("Failed $CONTENT_URL", IllegalStateException(CONTENT_URL)) }
        assertRedactedFailure(client)
    }

    @Test fun `signed content retains safe provider status for HTTP error handling`() {
        val client = client { chain -> response(chain, 401).build() }
        val failure = assertThrows(NovelProviderFailure::class.java) {
            runBlocking { client.fetchText(CONTENT_URL) }
        }
        assertEquals(401, failure.status)
        assertFalse(failure.stackTraceToString().contains(SIGNATURE))
    }

    @Test fun `signed content remains bounded for declared and unknown response lengths`() {
        listOf(true, false).forEach { declared ->
            val buffer = Buffer().write(ByteArray(5 * 1024 * 1024 + 1))
            val body = object : ResponseBody() {
                override fun contentType() = null
                override fun contentLength() = if (declared) buffer.size else -1L
                override fun source() = buffer
            }
            val client = client { chain -> response(chain, 200).body(body).build() }
            assertRedactedFailure(client)
        }
    }

    private fun assertRedactedFailure(client: PublicHttpsNovelHttpClient) {
        val failure = assertThrows(IOException::class.java) { runBlocking { client.fetchText(CONTENT_URL) } }
        assertFalse(failure.stackTraceToString().contains(SIGNATURE))
        assertNull(failure.cause)
    }

    private fun client(interceptor: Interceptor) = PublicHttpsNovelHttpClient(
        OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .addInterceptor(interceptor).build(),
    )

    private fun response(chain: Interceptor.Chain, status: Int) = Response.Builder()
        .request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Synthetic fixture")
        .body(ByteArray(0).toResponseBody())

    companion object {
        private const val SIGNATURE = "synthetic-secret-signature"
        private const val CONTENT_URL = "https://wtr-lab.com/api/v2/reader/content/v1/ai/43378/28316965/en/$SIGNATURE"
    }
}
