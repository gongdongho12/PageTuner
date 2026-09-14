package com.dongholab.pagetuner.translation

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ProviderHttpTransportTest {
    @Test fun sendsUtf8BodyWithoutFollowingRedirectsAndClosesConnection() = runBlocking {
        val connection = FakeConnection()
        val result = ProviderHttpTransport("test", connectionFactory = { connection })
            .post("https://provider.example/translate", mapOf("Authorization" to "Bearer test"), "한국어")
        assertEquals("translated", result)
        assertEquals("한국어", connection.body.toString("UTF-8"))
        assertEquals("Bearer test", connection.getRequestProperty("Authorization"))
        assertFalse(connection.instanceFollowRedirects)
        assertEquals(15_000, connection.connectTimeout)
        assertTrue(connection.closed)
    }

    @Test fun rejectsRedirectWithoutReadingOrLeakingLocation() = runBlocking {
        val connection = FakeConnection(status = 302)
        val error = runCatching { ProviderHttpTransport("test", connectionFactory = { connection })
            .post("https://provider.example/translate", mapOf("Authorization" to "secret"), "source") }.exceptionOrNull()
        assertTrue(error is TranslationProviderException)
        assertFalse(connection.read)
        assertTrue(connection.closed)
        assertFalse(error.toString().contains("secret"))
    }

    @Test fun rejectsCleartextRemoteEndpointsBeforeOpeningAConnection() = runBlocking {
        var opened = false
        val transport = ProviderHttpTransport("test", connectionFactory = { opened = true; FakeConnection() })
        for (endpoint in listOf("http://provider.example/path", "https://user:password@provider.example/path", "file:///secret", "https://provider.example/#fragment")) {
            val error = runCatching { transport.post(endpoint, emptyMap(), "") }.exceptionOrNull()
            assertEquals(TranslationProviderErrorKind.Configuration, (error as TranslationProviderException).failure.kind)
        }
        assertFalse(opened)
        assertEquals("translated", transport.post("http://127.0.0.1:8080/path", emptyMap(), ""))
    }

    @Test fun configurationHealthUsesTheSameEndpointRulesAsDefaultTransport() {
        val settings = TranslationSettings(TranslationProviderKind.OPENAI_COMPATIBLE_LLM, "key", llmEndpoint = "http://remote.example/chat", llmModel = "model")
        assertEquals(ProviderHealthState.InvalidConfiguration, settings.checkProviderHealth().state)
        assertEquals(ProviderHealthState.Ready, settings.copy(llmEndpoint = "http://localhost:11434/v1/chat/completions").checkProviderHealth().state)
    }

    @Test fun boundsResponseAndDoesNotExposeProviderErrorBody() = runBlocking {
        val large = FakeConnection(response = "0123456789")
        val error = runCatching { ProviderHttpTransport("test", maxResponseBytes = 5, connectionFactory = { large })
            .post("https://provider.example", emptyMap(), "") }.exceptionOrNull()
        assertEquals(TranslationProviderErrorKind.ResponseFormat, (error as TranslationProviderException).failure.kind)
        assertTrue(large.closed)
        val denied = FakeConnection(status = 401, response = "Your key is secret-key, source private chapter")
        val authError = runCatching { ProviderHttpTransport("test", connectionFactory = { denied })
            .post("https://provider.example", emptyMap(), "") }.exceptionOrNull()
        assertEquals(TranslationProviderErrorKind.Authentication, (authError as TranslationProviderException).failure.kind)
        assertFalse(authError.toString().contains("secret-key"))
        assertFalse(authError.toString().contains("private chapter"))
    }

    @Test fun cancellationDisconnectsAnActiveConnection() = runBlocking {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val connection = object : FakeConnection() {
            override fun getInputStream(): InputStream {
                entered.countDown()
                check(released.await(5, TimeUnit.SECONDS)) { "Cancellation did not disconnect the connection." }
                return super.getInputStream()
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val job = async(Dispatchers.Default) { ProviderHttpTransport("test", connectionFactory = { connection })
            .post("https://provider.example", emptyMap(), "") }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(connection.closed)
    }

    @Test fun transportDeadlineIsANetworkFailureAndDisconnects() = runBlocking {
        val released = CountDownLatch(1)
        val connection = object : FakeConnection() {
            override fun getInputStream(): InputStream {
                check(released.await(5, TimeUnit.SECONDS)) { "Deadline did not disconnect." }
                return super.getInputStream()
            }
            override fun disconnect() { super.disconnect(); released.countDown() }
        }
        val error = runCatching { ProviderHttpTransport("test", requestTimeoutMillis = 100, connectionFactory = { connection })
            .post("https://provider.example", emptyMap(), "") }.exceptionOrNull()
        assertEquals(TranslationProviderErrorKind.Network, (error as TranslationProviderException).failure.kind)
        assertTrue(connection.closed)
    }

    private open class FakeConnection(private val status: Int = 200, private val response: String = "translated") : HttpURLConnection(URL("https://provider.example")) {
        val body = ByteArrayOutputStream()
        @Volatile var closed = false
        var read = false
        override fun connect() = Unit
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode() = status
        override fun getInputStream(): InputStream { read = true; return ByteArrayInputStream(response.toByteArray(Charsets.UTF_8)) }
        override fun getErrorStream(): InputStream = ByteArrayInputStream(response.toByteArray(Charsets.UTF_8))
    }
}
