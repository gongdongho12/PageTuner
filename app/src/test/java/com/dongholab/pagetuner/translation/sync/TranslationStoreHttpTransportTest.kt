package com.dongholab.pagetuner.translation.sync

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationStoreHttpTransportTest {
    @Test
    fun connectionDisablesRedirectsAndAppliesFiniteTimeouts() = runTest {
        val connection = object : FakeConnection() {
            // Reproduce HttpURLConnection's Java null status-line key despite the Android annotations.
            @Suppress("UNCHECKED_CAST")
            override fun getHeaderFields(): Map<String, List<String>> = mapOf(
                null to listOf("HTTP/1.1 200 OK"), "Content-Type" to listOf("application/json"),
            ) as Map<String, List<String>>
        }
        val transport = UrlConnectionTranslationStoreTransport(100, 200, connectionFactory = { connection })
        val response = transport.execute(request())
        assertEquals("{}", response.body)
        assertEquals(mapOf("Content-Type" to listOf("application/json")), response.headers)
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(100, connection.connectTimeout)
        assertEquals(200, connection.readTimeout)
        assertTrue(connection.disconnected)
    }

    @Test
    fun cancellationDisconnectsActiveBlockingRead() = runTest {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = object : FakeConnection() {
            override fun getInputStream(): InputStream = object : InputStream() {
                override fun read(): Int {
                    entered.countDown()
                    if (!released.await(5, TimeUnit.SECONDS)) throw SocketTimeoutException()
                    throw IOException("Disconnected")
                }
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val transport = UrlConnectionTranslationStoreTransport(connectionFactory = { connection })
        val job = launch(Dispatchers.Default) { transport.execute(request()) }
        try {
            assertTrue("Transport must start reading", entered.await(5, TimeUnit.SECONDS))
            job.cancelAndJoin()
            assertTrue(job.isCancelled)
            assertTrue(connection.disconnected)
            assertEquals(0L, released.count)
        } finally { connection.disconnect(); job.cancelAndJoin() }
    }

    @Test
    fun oversizedResponseAndTimeoutAlwaysDisconnect() = runTest {
        val oversized = object : FakeConnection() {
            override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(20))
        }
        val timedOut = object : FakeConnection() {
            override fun getResponseCode(): Int = throw SocketTimeoutException("Timed out")
        }
        for (connection in listOf(oversized, timedOut)) {
            try {
                UrlConnectionTranslationStoreTransport(maxResponseBytes = 10, connectionFactory = { connection }).execute(request())
                error("Expected transport failure")
            } catch (_: IOException) { assertTrue(connection.disconnected) }
        }
    }

    private fun request() = TranslationStoreHttpRequest("https://reader.example/api/v1/translations/record", "GET", emptyMap())

    private open class FakeConnection : HttpURLConnection(URL("https://reader.example")) {
        @Volatile var disconnected = false
        override fun connect() = Unit
        override fun disconnect() { disconnected = true }
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = 200
        override fun getInputStream(): InputStream = ByteArrayInputStream("{}".toByteArray())
        override fun getHeaderFields(): Map<String, List<String>> = emptyMap()
    }
}
