package com.dongholab.pagetuner.server

import com.fasterxml.jackson.databind.ObjectMapper
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestComponent
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController

/** Real servlet error dispatch is not exercised by MockMvc's sendError response. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ServerSecurity::class, CsrfController::class, ServerSecurityHttpTest.HttpTestConfiguration::class],
    properties = [
        "spring.security.user.name=csrf-http-reader",
        "spring.security.user.password=csrf-http-password",
        "server.address=127.0.0.1",
        "server.servlet.session.cookie.secure=false",
    ],
)
class ServerSecurityHttpTest {
    @LocalServerPort private var port: Int = 0
    @Autowired private lateinit var json: ObjectMapper
    @Autowired private lateinit var probe: MutationProbe

    private data class Csrf(val headerName: String, val token: String)
    private val credentials = basic("csrf-http-password")
    private val clients = mutableListOf<HttpClient>()

    @BeforeEach
    fun resetMutations() { probe.mutations.set(0) }

    @AfterEach
    fun closeClients() { clients.forEach(HttpClient::close) }

    @Test
    fun `anonymous registration rejects missing csrf with direct forbidden and accepts its session token`() {
        val client = client()
        assertForbidden(request(client, "POST", "/api/v1/accounts/register"))
        assertEquals(0, probe.mutations.get())

        val token = csrf(client)
        assertEquals(201, request(client, "POST", "/api/v1/accounts/register", csrf = token).statusCode())
        assertEquals(1, probe.mutations.get())
    }

    @Test
    fun `authenticated profile and password writes reject missing and invalid csrf without changing state`() {
        val client = client()
        val paths = listOf("PATCH" to "/api/v1/accounts/me", "POST" to "/api/v1/accounts/me/password")
        paths.forEach { (method, path) -> assertForbidden(request(client, method, path, credentials)) }
        val token = csrf(client)
        paths.forEach { (method, path) ->
            assertForbidden(request(client, method, path, credentials, token.copy(token = "invalid-token")))
        }
        assertEquals(0, probe.mutations.get())

        paths.forEach { (method, path) ->
            assertEquals(204, request(client, method, path, credentials, token).statusCode())
        }
        assertEquals(2, probe.mutations.get())
    }

    @Test
    fun `csrf tokens require their original session even with valid Basic credentials`() {
        val original = client()
        val token = csrf(original)
        assertForbidden(request(client(), "PATCH", "/api/v1/accounts/me", credentials, token))
        val another = client()
        csrf(another)
        assertForbidden(request(another, "PATCH", "/api/v1/accounts/me", credentials, token))
        assertEquals(0, probe.mutations.get())

        assertEquals(204, request(original, "PATCH", "/api/v1/accounts/me", credentials, token).statusCode())
        assertEquals(1, probe.mutations.get())
    }

    @Test
    fun `csrf session never authenticates requests and invalid Basic credentials still return unauthorized`() {
        val client = client()
        val token = csrf(client, authenticated = true)
        assertEquals(401, request(client, "GET", "/api/v1/accounts/me").statusCode())
        assertEquals(401, request(client, "GET", "/error").statusCode())
        assertEquals(401, request(client, "PATCH", "/api/v1/accounts/me", csrf = token).statusCode())
        assertEquals(401, request(client, "PATCH", "/api/v1/accounts/me", basic("wrong-password"), token).statusCode())
        assertEquals(0, probe.mutations.get())

        assertEquals(204, request(client, "PATCH", "/api/v1/accounts/me", credentials, token).statusCode())
        assertEquals(1, probe.mutations.get())
    }

    private fun client(): HttpClient = HttpClient.newBuilder()
        .cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(5))
        .build()
        .also(clients::add)

    private fun csrf(client: HttpClient, authenticated: Boolean = false): Csrf {
        val response = request(client, "GET", if (authenticated) "/api/v1/csrf" else "/api/v1/accounts/csrf",
            if (authenticated) credentials else null)
        assertEquals(200, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        val value = json.readTree(response.body())
        return Csrf(value["headerName"].asText(), value["token"].asText())
    }

    private fun request(
        client: HttpClient,
        method: String,
        path: String,
        authorization: String? = null,
        csrf: Csrf? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port$path"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
        authorization?.let { builder.header("Authorization", it) }
        csrf?.let { builder.header(it.headerName, it.token) }
        if (method == "GET") builder.GET()
        else builder.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString("{}"))
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun assertForbidden(response: HttpResponse<String>) {
        assertEquals(403, response.statusCode())
        assertEquals("no-store", response.headers().firstValue("Cache-Control").orElse(null))
        assertFalse(response.headers().firstValue("WWW-Authenticate").isPresent)
    }

    private fun basic(password: String): String = "Basic " + Base64.getEncoder()
        .encodeToString("csrf-http-reader:$password".toByteArray(Charsets.UTF_8))

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = [DataSourceAutoConfiguration::class, HibernateJpaAutoConfiguration::class, FlywayAutoConfiguration::class])
    class HttpTestConfiguration {
        @Bean fun mutationProbe() = MutationProbe()
    }

    @TestComponent
    @RestController
    class MutationProbe {
        val mutations = AtomicInteger()
        @PostMapping("/api/v1/accounts/register")
        fun register(): ResponseEntity<Void> { mutations.incrementAndGet(); return ResponseEntity.status(201).build() }
        @GetMapping("/api/v1/accounts/me")
        fun profile(): ResponseEntity<Void> = ResponseEntity.noContent().build()
        @PatchMapping("/api/v1/accounts/me")
        fun update(): ResponseEntity<Void> { mutations.incrementAndGet(); return ResponseEntity.noContent().build() }
        @PostMapping("/api/v1/accounts/me/password")
        fun password(): ResponseEntity<Void> { mutations.incrementAndGet(); return ResponseEntity.noContent().build() }
    }
}
