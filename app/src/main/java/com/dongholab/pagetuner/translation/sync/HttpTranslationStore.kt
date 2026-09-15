package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.BookIdentity
import com.dongholab.pagetuner.core.content.ChapterIdentity
import com.dongholab.pagetuner.core.translation.StoredTranslation
import com.dongholab.pagetuner.core.translation.TranslatedParagraph
import com.dongholab.pagetuner.core.translation.TranslationArtifact
import com.dongholab.pagetuner.core.translation.TranslationSaveResult
import com.dongholab.pagetuner.core.translation.TranslationStore
import java.io.IOException
import java.net.HttpCookie
import java.net.URI
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Constructor-only credentials. No credentials or session cookies are written to disk. */
class TranslationStoreBasicAuth(username: String, password: String) {
    internal val header: String

    init {
        require(username.isNotBlank() && ':' !in username && '\r' !in username && '\n' !in username)
        require('\r' !in password && '\n' !in password)
        header = "Basic " + encodeBase64("$username:$password".toByteArray(Charsets.UTF_8))
    }

    private fun encodeBase64(bytes: ByteArray): String = buildString {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        for (offset in bytes.indices step 3) {
            val a = bytes[offset].toInt() and 255
            val b = bytes.getOrNull(offset + 1)?.toInt()?.and(255)
            val c = bytes.getOrNull(offset + 2)?.toInt()?.and(255)
            append(alphabet[a ushr 2])
            append(alphabet[((a and 3) shl 4) or ((b ?: 0) ushr 4)])
            append(if (b == null) '=' else alphabet[((b and 15) shl 2) or ((c ?: 0) ushr 6)])
            append(if (c == null) '=' else alphabet[c and 63])
        }
    }
}

enum class TranslationStoreFailure {
    AUTHENTICATION, FORBIDDEN, NOT_FOUND, CONFLICT, INVALID_REQUEST, SERVER, REDIRECT, NETWORK, TIMEOUT, INVALID_RESPONSE,
}

class TranslationStoreException(
    val failure: TranslationStoreFailure,
    val status: Int? = null,
) : IOException("Translation store request failed: $failure${status?.let { " (HTTP $it)" }.orEmpty()}.")

/** A manual, authenticated adapter for the server's /api/v1/translations contract. */
class HttpTranslationStore(
    baseUrl: String,
    private val auth: TranslationStoreBasicAuth? = null,
    private val transport: TranslationStoreHttpTransport = DefaultTranslationStoreHttpTransport(),
    allowInsecureDevelopmentHttp: Boolean = false,
) : TranslationStore {
    private val base: URI = URI(baseUrl).also { uri ->
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank()) { "A HTTP(S) server origin is required." }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Credentials, query and fragment are not allowed in the server URL." }
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "Use the server origin without an API path." }
        require(uri.port == -1 || uri.port in 1..65535) { "Invalid server port." }
        val loopback = uri.host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "[::1]")
        require(uri.scheme == "https" || loopback || allowInsecureDevelopmentHttp) { "Non-loopback HTTP requires explicit development opt-in." }
    }

    override suspend fun save(artifact: TranslationArtifact): TranslationSaveResult {
        return save(artifact, null, null)
    }

    suspend fun save(artifact: TranslationArtifact, bookTitle: String?, chapterTitle: String?): TranslationSaveResult {
        // Snapshot before suspending; rebuild hashes off the caller's (possibly Main) dispatcher.
        val paragraphs = artifact.paragraphs.toList()
        return withContext(Dispatchers.IO) { saveSnapshot(artifact.copy(paragraphs = paragraphs), bookTitle, chapterTitle) }
    }

    private suspend fun saveSnapshot(artifact: TranslationArtifact, bookTitle: String?, chapterTitle: String?): TranslationSaveResult {
        currentCoroutineContext().ensureActive()
        require(artifact.paragraphs.isNotEmpty() && artifact.paragraphs.all { it.text.isNotBlank() }) { "A complete, nonblank translation is required." }
        val csrfResponse = execute("/api/v1/csrf", "GET")
        val csrf = decode { JSONObject(csrfResponse.body) }
        val header = decode { csrf.requiredString("headerName") }
        val token = decode { csrf.requiredString("token") }
        if (!header.matches(Regex("X-[A-Za-z0-9-]+", RegexOption.IGNORE_CASE)) || token.any { it == '\r' || it == '\n' }) {
            throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
        }
        val cookies = sessionCookies(csrfResponse, "/api/v1/translations")
        if (cookies.isEmpty()) throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
        val payload = TranslationStoreJson.encode(artifact).apply {
            bookTitle?.takeIf(String::isNotBlank)?.let { put("bookTitle", it.take(500)) }
            chapterTitle?.takeIf(String::isNotBlank)?.let { put("chapterTitle", it.take(500)) }
        }
        val response = execute(
            "/api/v1/translations", "POST", payload.toString(),
            mapOf(header to token, "Cookie" to cookies),
        )
        return decode {
            val json = JSONObject(response.body)
            val stored = TranslationStoreJson.decode(json)
            require(stored.artifact == artifact) { "Saved artifact differs from the submitted artifact." }
            TranslationSaveResult(stored, json.get("created") as Boolean)
        }
    }

    override suspend fun get(recordId: String): StoredTranslation = withContext(Dispatchers.IO) {
        require(recordId.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "Invalid translation record ID." }
        val response = execute("/api/v1/translations/$recordId", "GET")
        decode {
            TranslationStoreJson.decode(JSONObject(response.body)).also {
                require(it.recordId == recordId) { "Translation record ID does not match the request." }
            }
        }
    }

    suspend fun list(kind: ServerLibraryKind, page: Int = 0, size: Int = 12): ServerLibraryPage = withContext(Dispatchers.IO) {
        require(page >= 0 && size in 1..50 && page.toLong() * size <= Int.MAX_VALUE)
        val path = if (kind == ServerLibraryKind.Translations) "translations" else "chapters"
        val response = execute("/api/v1/$path?page=$page&size=$size", "GET")
        decode { ServerLibraryJson.page(JSONObject(response.body), kind, page, size) }
    }

    suspend fun read(entry: ServerLibraryEntry): ServerLibraryDocument = withContext(Dispatchers.IO) {
        require(java.util.UUID.fromString(entry.recordId).toString() == entry.recordId)
        if (entry.kind == ServerLibraryKind.Translations) {
            val stored = get(entry.recordId)
            require(stored.artifact.sourceRevision == entry.sourceRevision && stored.artifact.paragraphs.size == entry.paragraphCount) {
                "Translation does not match its library entry."
            }
            ServerLibraryDocument(entry, stored.artifact.paragraphs.map { it.text }, stored)
        } else {
            val response = execute("/api/v1/chapters/${entry.recordId}", "GET")
            decode { ServerLibraryJson.chapter(JSONObject(response.body), entry) }
        }
    }

    suspend fun accountLanguages(): ServerAccountLanguages = withContext(Dispatchers.IO) {
        val response = execute("/api/v1/accounts/languages", "GET", authenticated = false)
        decode { ServerAccountJson.languages(JSONObject(response.body)) }
    }

    suspend fun original(recordId: String): ServerLibraryDocument = withContext(Dispatchers.IO) {
        require(java.util.UUID.fromString(recordId).toString() == recordId)
        val response = execute("/api/v1/chapters/$recordId", "GET")
        decode {
            val json = JSONObject(response.body)
            val title = listOf(json.requiredString("bookTitle"), json.requiredString("chapterTitle")).distinct().joinToString(" · ")
            val entry = ServerLibraryEntry(recordId, title, json.requiredString("sourceLanguage"), json.getJSONArray("paragraphs").length(),
                json.requiredString("sourceRevision"), ServerLibraryKind.Originals)
            ServerLibraryJson.chapter(json, entry)
        }
    }

    suspend fun translationProviders(): List<ServerTranslationProvider> = withContext(Dispatchers.IO) {
        val response = execute("/api/v1/translation-providers", "GET")
        decode { ServerTranslationJobJson.providers(JSONObject(response.body)) }
    }

    suspend fun translationJobs(page: Int = 0, size: Int = 12): ServerJobsPage = withContext(Dispatchers.IO) {
        require(page >= 0 && size in 1..50 && page.toLong() * size <= Int.MAX_VALUE)
        val response = execute("/api/v1/translation-jobs?page=$page&size=$size", "GET")
        decode { ServerTranslationJobJson.page(JSONObject(response.body), page, size) }
    }

    suspend fun translationJob(jobId: String): ServerTranslationJob = withContext(Dispatchers.IO) {
        require(java.util.UUID.fromString(jobId).toString() == jobId)
        val response = execute("/api/v1/translation-jobs/$jobId", "GET")
        decode { ServerTranslationJobJson.job(JSONObject(response.body)).also { require(it.jobId == jobId) } }
    }

    suspend fun createTranslationJob(draft: ServerJobDraft): ServerTranslationJob {
        val snapshot = draft.copy(glossaryEntries = draft.glossaryEntries?.toList())
        return withContext(Dispatchers.IO) {
            val body = ServerTranslationJobJson.encode(snapshot)
            val terms = body.getJSONArray("glossary").let { values -> List(values.length()) { index ->
                ServerTranslationJobJson.glossaryEntry(values.getJSONObject(index))
            } }.sortedBy { it.source }
            val response = writeWithCsrf("/api/v1/translation-jobs", "POST", body, true)
            decode { ServerTranslationJobJson.job(JSONObject(response.body)).also {
                require(it.chapterRecordId == snapshot.chapterRecordId && it.providerKind == snapshot.providerKind && it.targetLanguage == snapshot.targetLanguage)
                require(it.settings.sourceLanguage == snapshot.sourceLanguage && it.settings.glossary.sortedBy { term -> term.source } == terms)
                if (snapshot.providerKind in setOf("DEEPSEEK", "OPENAI_COMPATIBLE_LLM")) {
                    require(snapshot.endpoint.isBlank() || it.settings.endpoint == snapshot.endpoint.trim().trimEnd('/'))
                    require(snapshot.model.isBlank() || it.settings.model == snapshot.model.trim())
                }
            } }
        }
    }

    suspend fun cancelTranslationJob(jobId: String): ServerTranslationJob = withContext(Dispatchers.IO) {
        require(java.util.UUID.fromString(jobId).toString() == jobId)
        val response = writeWithCsrf("/api/v1/translation-jobs/$jobId/cancel", "POST", JSONObject(), true)
        decode { ServerTranslationJobJson.job(JSONObject(response.body)).also { require(it.jobId == jobId) } }
    }

    suspend fun registerAccount(username: String, password: String, draft: ServerAccountDraft): ServerAccountProfile = withContext(Dispatchers.IO) {
        ServerAccountJson.validateUsername(username)
        ServerAccountJson.validatePassword(password)
        val body = ServerAccountJson.encode(draft).put("username", username).put("password", password)
        val response = writeWithCsrf("/api/v1/accounts/register", "POST", body, authenticated = false)
        decode {
            require(response.status == 201)
            ServerAccountJson.profile(JSONObject(response.body)).also { require(it.username == username) }
        }
    }

    suspend fun accountProfile(): ServerAccountProfile = withContext(Dispatchers.IO) {
        val response = execute("/api/v1/accounts/me", "GET")
        decode { ServerAccountJson.profile(JSONObject(response.body)) }
    }

    suspend fun updateAccountProfile(draft: ServerAccountDraft): ServerAccountProfile = withContext(Dispatchers.IO) {
        val response = writeWithCsrf("/api/v1/accounts/me", "PATCH", ServerAccountJson.encode(draft), authenticated = true)
        decode {
            ServerAccountJson.profile(JSONObject(response.body)).also {
                require(it.displayName == draft.displayName.trim() &&
                    it.locale.equals(ServerAccountJson.canonicalLanguageTag(draft.locale, 35), true) &&
                    it.targetLanguage.equals(ServerAccountJson.canonicalLanguageTag(draft.targetLanguage, 24), true))
            }
        }
    }

    suspend fun changeAccountPassword(currentPassword: String, newPassword: String) = withContext(Dispatchers.IO) {
        validateCurrentPassword(currentPassword)
        ServerAccountJson.validatePassword(newPassword)
        if (currentPassword == newPassword) throw ServerAccountInputException(ServerAccountInputField.PasswordUnchanged)
        val body = JSONObject().put("currentPassword", currentPassword).put("newPassword", newPassword)
        val response = writeWithCsrf("/api/v1/accounts/me/password", "POST", body, authenticated = true)
        // The old Basic credential is invalid immediately after this response. Do not fetch a profile here.
        decode { require(response.status == 204 && response.body.isEmpty()) }
    }

    suspend fun readingProgress(kind: String, recordId: String): ServerReadingProgress = withContext(Dispatchers.IO) {
        ServerReadingProgressJson.validateTarget(kind, recordId)
        val response = execute("/api/v1/reading-progress/$kind/$recordId", "GET")
        decode { ServerReadingProgressJson.decode(JSONObject(response.body), kind, recordId) }
    }

    suspend fun saveReadingProgress(kind: String, recordId: String, mutation: ServerReadingMutation): ServerReadingProgress = withContext(Dispatchers.IO) {
        ServerReadingProgressJson.validateTarget(kind, recordId)
        val response = writeWithCsrf("/api/v1/reading-progress/$kind/$recordId", "PUT", ServerReadingProgressJson.encode(mutation), true)
        decode { ServerReadingProgressJson.decode(JSONObject(response.body), kind, recordId).also {
            require(it.version == mutation.expectedVersion + 1 && it.anchor == mutation.anchor)
        } }
    }

    private suspend fun writeWithCsrf(path: String, method: String, body: JSONObject, authenticated: Boolean): TranslationStoreHttpResponse {
        val csrfPath = if (path.startsWith("/api/v1/accounts/")) "/api/v1/accounts/csrf" else "/api/v1/csrf"
        val csrfResponse = execute(csrfPath, "GET", authenticated = authenticated)
        val csrf = decode { JSONObject(csrfResponse.body) }
        val header = decode { csrf.requiredString("headerName") }
        val token = decode { csrf.requiredString("token") }
        if (!header.matches(Regex("X-[A-Za-z0-9-]+", RegexOption.IGNORE_CASE)) || token.any { it == '\r' || it == '\n' }) {
            throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
        }
        val cookies = sessionCookies(csrfResponse, path)
        if (cookies.isEmpty()) throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
        return execute(path, method, body.toString(), mapOf(header to token, "Cookie" to cookies), authenticated)
    }

    private suspend fun execute(
        path: String,
        method: String,
        body: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        authenticated: Boolean = true,
    ): TranslationStoreHttpResponse {
        currentCoroutineContext().ensureActive()
        val response = try {
            transport.execute(
                TranslationStoreHttpRequest(
                    base.resolve(path).toString(), method,
                    mapOf("Accept" to "application/json", "Content-Type" to "application/json; charset=utf-8", "X-Requested-With" to "XMLHttpRequest") +
                        (if (authenticated) mapOf("Authorization" to requireNotNull(auth) { "Authentication is required." }.header) else emptyMap()) + extraHeaders,
                    body,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            throw TranslationStoreException(TranslationStoreFailure.TIMEOUT)
        } catch (error: IOException) {
            currentCoroutineContext().ensureActive()
            throw TranslationStoreException(TranslationStoreFailure.NETWORK)
        }
        currentCoroutineContext().ensureActive()
        if (response.status !in 200..299) {
            if (path.startsWith("/api/v1/reading-progress/") && response.status == 429) {
                val seconds = response.headers.entries.firstOrNull { it.key.equals("Retry-After", true) }?.value?.firstOrNull()
                    ?.toLongOrNull()?.takeIf { it in 1..86_400 } ?: 60L
                throw ServerReadingProgressRateLimited(seconds)
            }
            if (path.startsWith("/api/v1/reading-progress/") && method == "PUT" && response.status == 409) {
                val current = decode {
                    val json = JSONObject(response.body)
                    require(json.get("code") == "READING_PROGRESS_CONFLICT")
                    val parts = path.split('/')
                    ServerReadingProgressJson.decode(json.getJSONObject("current"), parts[4], parts[5])
                }
                throw ServerReadingProgressConflict(current)
            }
            if (path == "/api/v1/accounts/me/password" && method == "POST") {
                passwordChangeFailure(response)?.let { throw ServerPasswordChangeException(it) }
            }
            val failure = when (response.status) {
                401 -> TranslationStoreFailure.AUTHENTICATION
                403 -> TranslationStoreFailure.FORBIDDEN
                404 -> TranslationStoreFailure.NOT_FOUND
                409 -> TranslationStoreFailure.CONFLICT
                in 300..399 -> TranslationStoreFailure.REDIRECT
                in 400..499 -> TranslationStoreFailure.INVALID_REQUEST
                else -> TranslationStoreFailure.SERVER
            }
            throw TranslationStoreException(failure, response.status)
        }
        return response
    }

    private fun sessionCookies(response: TranslationStoreHttpResponse, path: String): String = decode {
        response.headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }.flatMap(HttpCookie::parse)
            .filter { cookie ->
                val domain = cookie.domain?.trimStart('.')
                val cookiePath = cookie.path ?: "/"
                val domainMatches = domain == null || base.host.equals(domain, ignoreCase = true)
                val pathMatches = path == cookiePath || path.startsWith(cookiePath.trimEnd('/') + "/")
                domainMatches && pathMatches && cookie.maxAge != 0L && (!cookie.secure || base.scheme == "https")
            }
            .onEach { require(it.name.matches(Regex("[A-Za-z0-9_-]+")) && it.value.none { c -> c == '\r' || c == '\n' || c == ';' }) }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }

    private inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (error: Exception) {
        throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
    }
}

internal object TranslationStoreJson {
    fun encode(artifact: TranslationArtifact): JSONObject = JSONObject().apply {
        put("contentProviderId", artifact.chapter.book.providerId)
        put("bookId", artifact.chapter.book.bookId)
        put("chapterId", artifact.chapter.chapterId)
        put("sourceRevision", artifact.sourceRevision)
        put("sourceLanguage", artifact.sourceLanguage)
        put("targetLanguage", artifact.targetLanguage)
        put("translationProviderId", artifact.providerId)
        put("modelId", artifact.modelId)
        put("promptRevision", artifact.promptRevision)
        put("glossaryRevision", artifact.glossaryRevision)
        put("paragraphs", JSONArray().apply {
            artifact.paragraphs.forEach { put(JSONObject().put("paragraphId", it.paragraphId).put("text", it.text)) }
        })
    }

    fun decode(json: JSONObject): StoredTranslation {
        val paragraphs = json.getJSONArray("paragraphs")
        require(paragraphs.length() > 0)
        val artifact = TranslationArtifact(
            chapter = ChapterIdentity(BookIdentity(json.requiredString("contentProviderId"), json.requiredString("bookId")), json.requiredString("chapterId")),
            sourceRevision = json.requiredString("sourceRevision"),
            sourceLanguage = json.requiredString("sourceLanguage"),
            targetLanguage = json.requiredString("targetLanguage"),
            providerId = json.requiredString("translationProviderId"),
            modelId = json.strictString("modelId"),
            promptRevision = json.strictString("promptRevision"),
            glossaryRevision = json.strictString("glossaryRevision"),
            paragraphs = List(paragraphs.length()) { index ->
                val item = paragraphs.getJSONObject(index)
                TranslatedParagraph(item.requiredString("paragraphId"), item.requiredString("text"))
            },
        )
        require(json.requiredString("artifactId") == artifact.artifactId)
        require(json.requiredString("revision") == artifact.revision)
        require(json.requiredString("payloadHash") == artifact.payloadHash)
        return StoredTranslation(json.requiredString("recordId"), artifact, json.requiredString("createdAt"))
    }
}

private fun JSONObject.strictString(name: String): String = get(name) as? String
    ?: throw IllegalArgumentException("Expected a string.")

private fun JSONObject.requiredString(name: String): String = strictString(name).also { require(it.isNotBlank()) }
