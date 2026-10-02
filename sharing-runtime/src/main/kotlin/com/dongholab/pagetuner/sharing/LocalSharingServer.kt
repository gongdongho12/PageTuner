package com.dongholab.pagetuner.sharing

import com.dongholab.pagetuner.core.sharing.LocalSharingContract
import com.dongholab.pagetuner.core.sharing.SharedDocument
import com.dongholab.pagetuner.core.sharing.SharedLibraryPage
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

data class SharingSessionInfo(val address: String, val port: Int, val pairingCode: String, val expiresAt: Long)

/** A deliberately small, read-only LAN host. No account cookies, remote fetches or disk paths enter its API. */
class LocalSharingServer(
    private val bindAddress: String,
    private val port: Int = 8787,
    private val library: SharingLibrary,
    private val webAssets: SharingWebAssets,
    private val sessionDurationMillis: Long = 2 * 60 * 60 * 1000L,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val random = SecureRandom()
    private var host: HttpHost? = null
    private var active: SharingSessionInfo? = null
    private var expiryTimer: java.util.Timer? = null
    // Insertion order is last successful use; touching a token moves it to the end.
    private val tokens = linkedSetOf<String>()
    private val attempts = mutableListOf<Long>()
    private val peerAttempts = linkedMapOf<String, MutableList<Long>>()

    init {
        require(isPrivateIpv4(bindAddress)) { "Select a private IPv4 interface, not a wildcard or hostname" }
        require(port in 0..65535)
        require(sessionDurationMillis in 1..MAX_DURATION_MILLIS)
    }

    val sessionInfo: SharingSessionInfo?
        get() = synchronized(lock) { currentSession() }
    val isRunning: Boolean
        get() = sessionInfo != null

    fun startSharing(): SharingSessionInfo = synchronized(lock) {
        currentSession()?.let { return it }
        stopLocked()
        val server = HttpHost()
        try {
            server.start(READ_TIMEOUT_MILLIS, true)
            val now = clockMillis()
            val info = SharingSessionInfo(
                bindAddress, server.listeningPort,
                random.nextInt(100_000_000).toString().padStart(8, '0'), now + sessionDurationMillis,
            )
            host = server
            active = info
            expiryTimer = java.util.Timer("PageTuner sharing expiry", true).apply {
                schedule(object : java.util.TimerTask() {
                    override fun run() = synchronized(lock) {
                        if (host === server) stopLocked()
                    }
                }, sessionDurationMillis)
            }
            info
        } catch (failure: Exception) {
            server.stop()
            throw failure
        }
    }

    fun stopSharing() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        active = null
        tokens.clear()
        attempts.clear()
        peerAttempts.clear()
        expiryTimer?.cancel()
        expiryTimer = null
        host?.stop()
        host = null
    }

    private fun currentSession(): SharingSessionInfo? {
        val info = active ?: return null
        if (clockMillis() >= info.expiresAt) {
            active = null
            tokens.clear()
            return null
        }
        return info
    }

    private inner class HttpHost : NanoHTTPD(bindAddress, port) {
        init { setAsyncRunner(BoundedRunner()) }

        override fun useGzipWhenAccepted(response: Response) = false

        override fun serve(request: IHTTPSession): Response {
            val response = try { route(request) } catch (failure: HttpFailure) {
                error(failure.status, failure.code)
            } catch (failure: SharingUnavailableException) {
                when (failure.code) {
                    "document_too_large", "asset_too_large" -> error(413, failure.code)
                    "unsupported_format" -> error(422, failure.code)
                    "document_changed" -> error(409, failure.code)
                    else -> error(409, "document_unavailable")
                }
            } catch (_: Exception) {
                // Never return exception messages: adapters may include local paths or account details.
                error(500, "sharing_failed")
            }
            response.closeConnection(true)
            response.addHeader("Cache-Control", "no-store")
            response.addHeader("Pragma", "no-cache")
            if (response.status.requestStatus == 429) response.addHeader("Retry-After", "60")
            response.addHeader("X-Content-Type-Options", "nosniff")
            response.addHeader("X-Frame-Options", "DENY")
            response.addHeader("Referrer-Policy", "no-referrer")
            response.addHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
            response.addHeader("Content-Security-Policy", "default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' blob: data:; font-src 'self'; connect-src 'self'; worker-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'")
            return response
        }

        private fun route(request: IHTTPSession): Response {
            val info = synchronized(lock) { currentSession() } ?: fail(410, "sharing_expired")
            val authority = if (info.port == 80) bindAddress else "$bindAddress:${info.port}"
            if (request.headers["host"] != authority) fail(403, "invalid_host")
            request.headers["origin"]?.let { if (it != "http://$authority") fail(403, "invalid_origin") }
            if (request.headers["sec-fetch-site"] == "cross-site") fail(403, "invalid_origin")
            if (request.headers.containsKey("transfer-encoding")) fail(400, "unsupported_body")
            if (request.uri.length > 1024 || request.queryParameterString.orEmpty().length > 512) fail(414, "invalid_path")
            if (!safePath(request.uri)) fail(400, "invalid_path")

            val path = request.uri
            val api = LocalSharingContract.API_PATH
            if (path == "$api/pair") {
                method(request, Method.POST)
                query(request, emptySet())
                return pair(request, info)
            }
            if (request.headers["content-length"]?.let { it != "0" } == true) fail(400, "unsupported_body")
            if (path == "$api/status") {
                method(request, Method.GET)
                query(request, emptySet())
                return json(200, JSONObject().put("version", LocalSharingContract.VERSION).put("readOnly", true).put("expiresAt", info.expiresAt))
            }
            if (path.startsWith("$api/") || path == api) {
                val token = request.headers[LocalSharingContract.SESSION_HEADER.lowercase()]
                synchronized(lock) {
                    if (currentSession() != info || token == null || token !in tokens) fail(401, "pairing_required")
                    tokens.remove(token)
                    tokens.add(token)
                }
                if (path == "$api/session") {
                    method(request, Method.DELETE)
                    query(request, emptySet())
                    synchronized(lock) { tokens.remove(token) }
                    return newFixedLengthResponse(status(204), "application/json", "")
                }
                method(request, Method.GET)
                if (path == "$api/books") {
                    query(request, setOf("offset", "limit"))
                    val offset = integer(request, "offset", 0, 0..Int.MAX_VALUE)
                    val limit = integer(request, "limit", LocalSharingContract.MAX_PAGE_SIZE, 1..LocalSharingContract.MAX_PAGE_SIZE)
                    val result = library.list(offset, limit)
                    if (result.items.size > limit) fail(500, "sharing_failed")
                    return json(200, result.toJson())
                }
                val parts = path.removePrefix("$api/").split('/')
                if (parts.size == 2 && parts[0] == "books" && opaqueId(parts[1])) {
                    query(request, emptySet())
                    val document = library.document(parts[1]) ?: fail(404, "document_not_found")
                    if (document.paragraphs.sumOf { it.text.length.toLong() } > LocalSharingContract.MAX_DOCUMENT_CHARACTERS) fail(413, "document_too_large")
                    return json(200, document.toJson())
                }
                if (parts.size == 4 && parts[0] == "books" && parts[2] == "assets" && opaqueId(parts[1]) && opaqueId(parts[3])) {
                    query(request, setOf("revision"))
                    val revision = request.parameters["revision"]?.singleOrNull() ?: fail(400, "revision_required")
                    if (!opaqueId(revision)) fail(400, "invalid_revision")
                    val asset = library.asset(parts[1], revision, parts[3]) ?: fail(404, "asset_not_found")
                    if (asset.byteLength !in 0..LocalSharingContract.MAX_ASSET_BYTES) fail(413, "asset_too_large")
                    if (asset.mimeType !in ASSET_MIME_TYPES) fail(422, "unsupported_asset")
                    return newFixedLengthResponse(status(200), asset.mimeType, asset.open(), asset.byteLength)
                }
                fail(404, "route_not_found")
            }
            method(request, Method.GET)
            query(request, emptySet())
            val assetPath = if (path == "/") "index.html" else path.removePrefix("/")
            // Generated assets only; never map a URL to a filesystem path in this runtime.
            if (assetPath != "index.html" && !assetPath.startsWith("assets/") && assetPath != "icon.svg") fail(404, "route_not_found")
            val asset = webAssets.open(assetPath) ?: fail(404, "asset_not_found")
            if (asset.byteLength !in 0..LocalSharingContract.MAX_ASSET_BYTES) fail(413, "asset_too_large")
            return newFixedLengthResponse(status(200), asset.mimeType, asset.open(), asset.byteLength)
        }

        private fun pair(request: IHTTPSession, info: SharingSessionInfo): Response {
            synchronized(lock) {
                val now = clockMillis()
                attempts.removeAll { it <= now - RATE_WINDOW_MILLIS }
                peerAttempts.values.forEach { values -> values.removeAll { it <= now - RATE_WINDOW_MILLIS } }
                peerAttempts.entries.removeAll { it.value.isEmpty() }
                val peer = request.remoteIpAddress
                val peerList = peerAttempts[peer]
                if (attempts.size >= GLOBAL_ATTEMPTS || (peerList?.size ?: 0) >= PEER_ATTEMPTS || (peerList == null && peerAttempts.size >= MAX_PEERS)) fail(429, "pairing_rate_limited")
                attempts.add(now)
                peerAttempts.getOrPut(peer) { mutableListOf() }.add(now)
            }
            val contentType = request.headers["content-type"]?.substringBefore(';')?.trim()
            if (contentType != "application/json") fail(415, "json_required")
            val length = request.headers["content-length"]?.toIntOrNull() ?: fail(411, "content_length_required")
            if (length !in 1..MAX_PAIR_BODY_BYTES) fail(413, "body_too_large")
            val bytes = ByteArray(length)
            var offset = 0
            while (offset < length) {
                val read = request.inputStream.read(bytes, offset, length - offset)
                if (read <= 0) fail(400, "invalid_body")
                offset += read
            }
            val payload = try {
                val input = JSONTokener(bytes.toString(Charsets.UTF_8))
                val value = JSONObject(input)
                if (input.nextClean() != '\u0000') fail(400, "invalid_body")
                value
            } catch (_: Exception) { fail(400, "invalid_body") }
            if (payload.length() != 1 || !payload.has("code") || payload.opt("code") !is String) fail(400, "invalid_body")
            val code = payload.getString("code")
            if (!code.matches(Regex("[0-9]{8}")) || !MessageDigest.isEqual(code.toByteArray(Charsets.UTF_8), info.pairingCode.toByteArray(Charsets.UTF_8))) fail(401, "invalid_pairing_code")
            val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            synchronized(lock) {
                if (currentSession() != info) fail(410, "sharing_expired")
                // Reloading a browser loses its memory-only token. A correct code must still let
                // that browser reconnect without keeping abandoned sessions for the whole lifetime.
                if (tokens.size >= MAX_SESSIONS) tokens.remove(tokens.first())
                tokens.add(token)
            }
            return json(200, JSONObject().put("token", token).put("expiresAt", info.expiresAt))
        }
    }

    private class BoundedRunner : NanoHTTPD.AsyncRunner {
        private val clients = Collections.newSetFromMap(ConcurrentHashMap<NanoHTTPD.ClientHandler, Boolean>())
        private val deadlines = ConcurrentHashMap<NanoHTTPD.ClientHandler, ScheduledFuture<*>>()
        private val timer = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "PageTuner sharing deadline").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        private val pool = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(), { task -> Thread(task, "PageTuner sharing request").apply { isDaemon = true } })

        override fun exec(client: NanoHTTPD.ClientHandler) {
            clients.add(client)
            try {
                deadlines[client] = timer.schedule({ client.close() }, CONNECTION_DEADLINE_SECONDS, TimeUnit.SECONDS)
                pool.execute(client)
            } catch (_: RejectedExecutionException) {
                client.close()
                closed(client)
            }
        }

        override fun closed(client: NanoHTTPD.ClientHandler) {
            clients.remove(client)
            deadlines.remove(client)?.cancel(false)
        }

        override fun closeAll() {
            clients.toList().forEach { it.close() }
            clients.clear()
            deadlines.clear()
            timer.shutdownNow()
            pool.shutdownNow()
        }
    }

    private companion object {
        const val MAX_DURATION_MILLIS = 24 * 60 * 60 * 1000L
        const val READ_TIMEOUT_MILLIS = 5_000
        const val CONNECTION_DEADLINE_SECONDS = 30L
        const val MAX_PAIR_BODY_BYTES = 1024
        const val RATE_WINDOW_MILLIS = 60_000L
        const val PEER_ATTEMPTS = 10
        const val GLOBAL_ATTEMPTS = 30
        const val MAX_PEERS = 128
        const val MAX_SESSIONS = 8
        val ASSET_MIME_TYPES = setOf("application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "image/avif", "image/bmp")

        fun isPrivateIpv4(value: String): Boolean {
            val parts = value.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || (it.toIntOrNull() ?: 256) > 255 }) return false
            val n = parts.map(String::toInt)
            return n[0] == 10 || n[0] == 127 || (n[0] == 192 && n[1] == 168) || (n[0] == 172 && n[1] in 16..31)
        }
        fun safePath(path: String): Boolean = path.startsWith('/') && !path.contains('\\') && !path.contains('%') && path.split('/').drop(1).all { segment -> segment.isEmpty() || (segment != "." && segment != ".." && segment.matches(Regex("[A-Za-z0-9_.-]+"))) }
        fun opaqueId(value: String) = value.length in 1..128 && value.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]*")) && value != "." && value != ".."
        fun method(request: NanoHTTPD.IHTTPSession, expected: NanoHTTPD.Method) { if (request.method != expected) fail(405, "method_not_allowed") }
        fun query(request: NanoHTTPD.IHTTPSession, allowed: Set<String>) { if (request.parameters.any { it.key !in allowed || it.value.size != 1 }) fail(400, "invalid_query") }
        fun integer(request: NanoHTTPD.IHTTPSession, name: String, default: Int, range: IntRange): Int {
            val raw = request.parameters[name]?.singleOrNull() ?: return default
            val value = raw.toIntOrNull() ?: fail(400, "invalid_query")
            if (value !in range) fail(400, "invalid_query")
            return value
        }
        fun fail(status: Int, code: String): Nothing = throw HttpFailure(status, code)
        fun status(code: Int): NanoHTTPD.Response.IStatus = object : NanoHTTPD.Response.IStatus {
            override fun getRequestStatus() = code
            override fun getDescription() = "$code ${when (code) { 200 -> "OK"; 204 -> "No Content"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"; 413 -> "Payload Too Large"; 414 -> "URI Too Long"; 415 -> "Unsupported Media Type"; 422 -> "Unprocessable Content"; 429 -> "Too Many Requests"; else -> "Internal Server Error" }}"
        }
        fun json(code: Int, body: JSONObject): NanoHTTPD.Response = NanoHTTPD.newFixedLengthResponse(status(code), "application/json; charset=utf-8", body.toString())
        fun error(status: Int, code: String) = json(status, JSONObject().put("code", code).put("message", code.replace('_', ' ')))
    }
}

private class HttpFailure(val status: Int, val code: String) : IOException(code)

private fun SharedLibraryPage.toJson() = JSONObject().put("items", JSONArray(items.map { item -> JSONObject().put("id", item.id).put("title", item.title).put("format", item.format).put("edition", item.edition) })).put("total", total).put("offset", offset).put("limit", limit)

private fun SharedDocument.toJson() = JSONObject()
    .put("id", id).put("title", title).put("format", format).put("edition", edition).put("language", language).put("revision", revision)
    .put("paragraphs", JSONArray(paragraphs.map { JSONObject().put("paragraphId", it.paragraphId).put("text", it.text) }))
    .put("outline", JSONArray(outline.map { JSONObject().put("title", it.title).put("paragraphId", it.paragraphId) }))
    .put("assets", JSONArray(assets.map { JSONObject().put("id", it.id).put("mimeType", it.mimeType).put("byteLength", it.byteLength).put("role", it.role).put("paragraphId", it.paragraphId ?: JSONObject.NULL).put("alt", it.alt) }))
    .put("anchor", anchor?.let { JSONObject().put("paragraphId", it.paragraphId).put("characterOffset", it.characterOffset) } ?: JSONObject.NULL)
