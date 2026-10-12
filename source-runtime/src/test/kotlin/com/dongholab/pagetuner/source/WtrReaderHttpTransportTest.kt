package com.dongholab.pagetuner.source

import com.sun.net.httpserver.HttpServer
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Loopback fixtures exercise the shared transport without contacting a novel provider. */
class WtrReaderHttpTransportTest {
    @Test fun rejectsSignedContentRedirectWithoutRequestingDestinationOrLoggingSignature() {
        val redirected = AtomicInteger()
        withServer { server ->
            server.createContext(PATH) { exchange ->
                exchange.responseHeaders.add("Location", "/redirected/$SIGNATURE")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }
            server.createContext("/redirected/") { exchange ->
                redirected.incrementAndGet()
                exchange.sendResponseHeaders(200, 2)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
            }
            assertRedactedFailure(url(server))
            assertEquals(0, redirected.get())
        }
    }

    @Test fun redactsHttpFailureMessagesAndTheirCauseChains() {
        withServer { server ->
            server.createContext(PATH) { exchange ->
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
            assertRedactedFailure(url(server))
        }
        // URL parser exceptions include their input even before a connection can be opened.
        assertRedactedFailure("invalid-protocol://host$PATH$SIGNATURE")
    }

    @Test fun enforcesFiveMiBLimitAfterGzipDecompression() {
        val compressed = ByteArrayOutputStream().apply {
            GZIPOutputStream(this).use { it.write(ByteArray(5 * 1024 * 1024 + 1) { 65 }) }
        }.toByteArray()
        withServer { server ->
            server.createContext(PATH) { exchange ->
                exchange.responseHeaders.add("Content-Encoding", "gzip")
                exchange.sendResponseHeaders(200, compressed.size.toLong())
                exchange.responseBody.use { it.write(compressed) }
            }
            assertRedactedFailure(url(server))
        }
    }

    private fun assertRedactedFailure(url: String) {
        val logs = mutableListOf<String>()
        val previousSink = SourceDiagnostics.sink
        try {
            SourceDiagnostics.sink = { tag, message -> logs += "$tag $message" }
            val failure = runBlocking { runCatching { WebNovelHttpClient.fetchText(url) }.exceptionOrNull() }
            assertTrue("Expected a bounded transport failure", failure is IOException)
            assertFalse(failure!!.stackTraceToString().contains(SIGNATURE))
            assertNull(failure.cause)
            assertTrue(logs.any { it.contains("[redacted]") })
            assertFalse(logs.joinToString("\n").contains(SIGNATURE))
        } finally {
            SourceDiagnostics.sink = previousSink
        }
    }

    private fun withServer(test: (HttpServer) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        try { test(server) } finally { server.stop(0) }
    }

    private fun url(server: HttpServer) = "http://127.0.0.1:${server.address.port}$PATH$SIGNATURE"

    companion object {
        private const val PATH = "/api/v2/reader/content/v1/ai/43378/28316965/en/"
        private const val SIGNATURE = "synthetic-secret-signature"
    }
}
