package com.dongholab.pagetuner.server.novel

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/** An interceptor supplies deterministic transport responses; these are not live-site tests. */
class PublicHttpsCatalogTransportTest {
    @Test fun `every redirected target is checked before the next request`() {
        val requests = mutableListOf<String>()
        val client = PublicHttpsNovelHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            requests += chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "https://127.0.0.1/private").body(ByteArray(0).toResponseBody()).build()
        }.build())
        assertThrows(UnsafeNovelUrl::class.java) { runBlocking { client.fetchContent("https://catalog-redirect.example/books") } }
        assertEquals(listOf("https://catalog-redirect.example/books"), requests)
    }

    @Test fun `preserves binary bytes and final URL across public cross origin redirects`() = runBlocking {
        val bytes = byteArrayOf(0, -1, 3, 40)
        val client = PublicHttpsNovelHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
            assertNull(chain.request().header("Authorization"))
            val response = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("Test")
            if (chain.request().url.host == "catalog-file.example") response.code(302).header("Location", "https://catalog-cdn.example/book.pdf").body(ByteArray(0).toResponseBody()).build()
            else response.code(200).body(bytes.toResponseBody()).build()
        }.build())
        val result = client.fetchContent("https://catalog-file.example/book", 10)
        assertEquals("https://catalog-cdn.example/book.pdf", result.url)
        assertArrayEquals(bytes, result.bytes)
    }

    @Test fun `bounds both declared content length and unknown length streams`() {
        listOf(true, false).forEach { declared ->
            val client = PublicHttpsNovelHttpClient(OkHttpClient.Builder().addInterceptor { chain ->
                val body = object : ResponseBody() {
                    override fun contentType() = null
                    override fun contentLength() = if (declared) 20L else -1L
                    override fun source() = Buffer().write(ByteArray(20))
                }
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
            }.build())
            assertThrows(IOException::class.java) { runBlocking { client.fetchContent("https://catalog-limit-${if (declared) "declared" else "stream"}.example/file", 10) } }
        }
    }
}
