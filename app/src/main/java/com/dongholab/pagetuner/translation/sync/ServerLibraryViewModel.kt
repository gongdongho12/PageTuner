package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.document.ReaderDocument
import com.dongholab.pagetuner.translation.TranslationCache
import com.dongholab.pagetuner.translation.TranslationSettings
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Neither this state nor its credentials are saved through SavedStateHandle or preferences. */
class ServerConnectionInput(val endpoint: String = "", val username: String = "", val password: String = "") {
    override fun toString() = "ServerConnectionInput(credentials=REDACTED)"
}
data class ServerLibraryState(
    val connection: ServerConnectionInput = ServerConnectionInput(),
    val connected: Boolean = false,
    val busy: Boolean = false,
    val status: ServerLibraryMessage = ServerLibraryMessage(R.string.server_status_credentials),
    val error: ServerLibraryMessage? = null,
    val kind: ServerLibraryKind = ServerLibraryKind.Translations,
    val page: ServerLibraryPage? = null,
    val selected: ServerLibraryDocument? = null,
    val profile: ServerAccountProfile? = null,
    val profileDraft: ServerAccountDraft = ServerAccountDraft(),
    val languages: ServerAccountLanguages? = null,
    val uiLocale: String? = null,
    val jobSource: ServerLibraryDocument? = null,
    val jobDraft: ServerJobDraft = ServerJobDraft(),
    val providers: List<ServerTranslationProvider> = emptyList(),
    val jobs: ServerJobsPage? = null,
    val latestJob: ServerTranslationJob? = null,
)
data class ServerLibraryMessage(val resource: Int, val arguments: List<Any> = emptyList())
sealed interface ServerLibraryEvent {
    data class Open(val document: ServerLibraryDocument, val saveToDevice: Boolean, val accountKey: String) : ServerLibraryEvent
}

class ServerLibraryViewModel(
    private val clientFactory: (String, TranslationStoreBasicAuth?) -> HttpTranslationStore = { endpoint, auth -> HttpTranslationStore(endpoint, auth) },
) : ViewModel() {
    private val mutableState = MutableStateFlow(ServerLibraryState())
    val state = mutableState.asStateFlow()
    private val mutableEvents = MutableSharedFlow<ServerLibraryEvent>(extraBufferCapacity = 1)
    val events = mutableEvents.asSharedFlow()
    private var store: HttpTranslationStore? = null
    private var operation: Job? = null
    private var jobPolling: Job? = null
    private var generation = 0

    fun updateEndpoint(value: String) = updateInput(value, state.value.connection.username, state.value.connection.password)
    fun updateUsername(value: String) = updateInput(state.value.connection.endpoint, value, state.value.connection.password)
    fun updatePassword(value: String) = updateInput(state.value.connection.endpoint, state.value.connection.username, value)
    private fun updateInput(endpoint: String, username: String, password: String) {
        if (state.value.busy) return
        jobPolling?.cancel()
        store = null
        mutableState.update { it.copy(connection = ServerConnectionInput(endpoint, username, password), connected = false,
            page = null, selected = null, profile = null, jobSource = null, jobDraft = ServerJobDraft(),
            providers = emptyList(), jobs = null, latestJob = null, error = null) }
    }

    fun connect() = start(R.string.server_status_connecting) {
        val input = state.value.connection
        val client = clientFactory(input.endpoint.trim(), TranslationStoreBasicAuth(input.username, input.password))
        val profile = client.accountProfile()
        val page = client.list(state.value.kind)
        currentCoroutineContext().ensureActive()
        store = client
        mutableState.update { it.copy(connected = true, page = page, profile = profile, profileDraft = profile.draft(),
            uiLocale = profile.effectiveLocale, status = ServerLibraryMessage(R.string.server_status_connected, listOf(page.totalItems))) }
    }

    fun disconnect() {
        jobPolling?.cancel()
        cancel()
        store = null
        mutableState.value = ServerLibraryState(connection = ServerConnectionInput(state.value.connection.endpoint, state.value.connection.username),
            uiLocale = state.value.uiLocale, status = ServerLibraryMessage(R.string.server_status_disconnected))
    }

    fun updateProfileDraft(value: ServerAccountDraft) {
        if (!state.value.busy) mutableState.update { it.copy(profileDraft = value, error = null) }
    }

    fun register() = start(R.string.server_status_registering) {
        val input = state.value.connection
        val username = input.username.trim().lowercase(java.util.Locale.ROOT)
        val profile = clientFactory(input.endpoint.trim(), null).registerAccount(username, input.password, state.value.profileDraft)
        currentCoroutineContext().ensureActive()
        store = clientFactory(input.endpoint.trim(), TranslationStoreBasicAuth(profile.username, input.password))
        mutableState.update { it.copy(connection = ServerConnectionInput(input.endpoint, profile.username, input.password),
            connected = true, profile = profile, profileDraft = profile.draft(), page = null, selected = null,
            uiLocale = profile.effectiveLocale, status = ServerLibraryMessage(R.string.server_status_registered)) }
    }

    fun saveProfile() = start(R.string.server_status_profile_saving) {
        val previous = requireNotNull(state.value.profile)
        val result = client().updateAccountProfile(state.value.profileDraft)
        require(result.accountId == previous.accountId && result.username == previous.username)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(profile = result, profileDraft = result.draft(), uiLocale = result.effectiveLocale,
            status = ServerLibraryMessage(R.string.server_status_profile_saved)) }
    }

    fun loadLanguages() = start(R.string.server_status_languages_loading) {
        val input = state.value.connection
        val result = clientFactory(input.endpoint.trim(), null).accountLanguages()
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(languages = result, status = ServerLibraryMessage(R.string.server_status_languages_loaded)) }
    }

    fun prepareTranslation(entry: ServerLibraryEntry) = start(R.string.server_job_preparing) {
        require(entry.kind == ServerLibraryKind.Originals)
        val source = client().read(entry)
        val providers = client().translationProviders()
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(jobSource = source, providers = providers,
            jobDraft = ServerJobDraft(chapterRecordId = entry.recordId, sourceLanguage = entry.language,
                targetLanguage = it.profile?.targetLanguage ?: "ko"),
            status = ServerLibraryMessage(R.string.server_job_ready)) }
    }

    fun updateJobDraft(value: ServerJobDraft) {
        if (state.value.busy) return
        mutableState.update { state ->
            val draft = if (state.jobDraft.retryOf != null) state.jobDraft.copy(apiKey = value.apiKey)
                else value.copy(idempotencyKey = java.util.UUID.randomUUID().toString(), glossaryEntries = null)
            state.copy(jobDraft = draft, error = null)
        }
    }

    fun selectJobProvider(providerId: String) {
        if (state.value.busy || state.value.jobDraft.retryOf != null) return
        val provider = state.value.providers.firstOrNull { it.id == providerId } ?: return
        updateJobDraft(state.value.jobDraft.copy(providerKind = providerId, endpoint = provider.defaultEndpoint,
            model = provider.defaultModel, apiKey = ""))
    }

    fun submitTranslationJob() = start(R.string.server_job_submitting) {
        val draft = state.value.jobDraft
        require(state.value.jobSource?.entry?.recordId == draft.chapterRecordId)
        val result = client().createTranslationJob(draft)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(latestJob = result, jobDraft = it.jobDraft.copy(apiKey = ""),
            status = ServerLibraryMessage(R.string.server_job_submitted)) }
        refreshJobs(0)
        startJobPolling()
    }

    fun loadJobs(page: Int = 0) = start(R.string.server_job_loading) {
        refreshJobs(page)
        mutableState.update { it.copy(status = ServerLibraryMessage(R.string.server_job_loaded)) }
        startJobPolling()
    }

    fun cancelServerJob(job: ServerTranslationJob) = start(R.string.server_job_cancelling) {
        require(job.active)
        val result = client().cancelTranslationJob(job.jobId)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(latestJob = result,
            jobs = it.jobs?.copy(items = it.jobs.items.map { previous -> if (previous.jobId == result.jobId) result else previous }),
            status = ServerLibraryMessage(R.string.server_job_cancelled)) }
    }

    fun prepareRetry(job: ServerTranslationJob) = start(R.string.server_job_preparing_retry) {
        require(job.canRetry)
        val source = client().original(job.chapterRecordId)
        val providers = client().translationProviders()
        val previous = job.settings
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(jobSource = source, providers = providers,
            jobDraft = ServerJobDraft(chapterRecordId = job.chapterRecordId, providerKind = job.providerKind,
                sourceLanguage = previous.sourceLanguage, targetLanguage = previous.targetLanguage, endpoint = previous.endpoint,
                model = previous.model, glossary = previous.glossary.joinToString("\n") { term -> "${term.source}=${term.target}" },
                glossaryEntries = previous.glossary.toList(), retryOf = job.jobId),
            status = ServerLibraryMessage(R.string.server_job_retry_ready)) }
    }

    fun readCompletedJob(job: ServerTranslationJob) = start(R.string.server_status_reading) {
        require(job.status == "COMPLETED")
        val source = requireNotNull(client().original(job.chapterRecordId).sourceContent)
        val stored = client().get(requireNotNull(job.translationRecordId))
        require(stored.artifact.paragraphs.size == job.totalParagraphs && stored.artifact.targetLanguage == job.targetLanguage &&
            stored.artifact.sourceLanguage == job.settings.sourceLanguage && stored.artifact.chapter == source.identity &&
            stored.artifact.sourceRevision == source.sourceRevision && stored.artifact.modelId == job.settings.model &&
            stored.artifact.paragraphs.map { it.paragraphId } == source.paragraphs.map { it.paragraphId })
        val entry = ServerLibraryEntry(stored.recordId, job.title, job.targetLanguage, job.totalParagraphs,
            stored.artifact.sourceRevision, ServerLibraryKind.Translations)
        val document = ServerLibraryDocument(entry, stored.artifact.paragraphs.map { it.text }, stored)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(selected = document, status = ServerLibraryMessage(R.string.server_status_verified)) }
        val input = state.value.connection
        val accountKey = com.dongholab.pagetuner.core.content.StableContentHash.sha256(input.endpoint.trim() + "\n" + input.username).take(24)
        mutableEvents.emit(ServerLibraryEvent.Open(document, false, accountKey))
    }

    private suspend fun refreshJobs(page: Int) {
        val result = client().translationJobs(page)
        val previous = state.value.latestJob
        val latest = previous?.let { value -> result.items.firstOrNull { it.jobId == value.jobId } ?: client().translationJob(value.jobId) }
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(jobs = result, latestJob = latest) }
    }

    private fun startJobPolling() {
        jobPolling?.cancel()
        jobPolling = viewModelScope.launch {
            while (isActive && state.value.connected) {
                val active = state.value.latestJob?.active == true || state.value.jobs?.items?.any { it.active } == true
                if (!active) break
                delay(3_000)
                if (state.value.busy) continue
                try { refreshJobs(state.value.jobs?.page ?: 0) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    mutableState.update { it.copy(error = serverLibraryError(error), status = ServerLibraryMessage(R.string.server_job_poll_failed)) }
                    break
                }
            }
        }
    }

    fun loadPage(page: Int = 0, kind: ServerLibraryKind = state.value.kind) = start(R.string.server_status_loading) {
        val result = client().list(kind, page)
        mutableState.update { it.copy(page = result, kind = kind, status = ServerLibraryMessage(R.string.server_status_count, listOf(result.totalItems))) }
    }

    fun read(entry: ServerLibraryEntry, saveToDevice: Boolean) = start(R.string.server_status_reading) {
        val document = client().read(entry)
        currentCoroutineContext().ensureActive()
        mutableState.update { it.copy(selected = document, status = ServerLibraryMessage(R.string.server_status_verified)) }
        val input = state.value.connection
        val accountKey = com.dongholab.pagetuner.core.content.StableContentHash.sha256(input.endpoint.trim() + "\n" + input.username).take(24)
        mutableEvents.emit(ServerLibraryEvent.Open(document, saveToDevice, accountKey))
    }

    fun publish(document: ReaderDocument, settings: TranslationSettings, glossary: BookGlossary?, cacheProviderId: String, cache: TranslationCache) =
        start(R.string.server_status_publishing) {
            val mapping = withContext(Dispatchers.Default) { ServerDocumentMapping.create(document, settings, glossary, cacheProviderId) }
            val records = cache.getMany(mapping.keys)
            require(records.size == mapping.keys.size && records.values.all { it.text.isNotBlank() }) {
                "아직 번역되지 않은 문단이 있습니다. 문서 전체 번역을 완료한 뒤 저장해 주세요."
            }
            val artifact = withContext(Dispatchers.Default) { mapping.toArtifact(records) }
            val result = client().save(artifact, document.title, document.title)
            mutableState.update { it.copy(status = ServerLibraryMessage(if (result.created) R.string.server_status_published else R.string.server_status_already_saved)) }
        }

    fun restore(document: ReaderDocument, settings: TranslationSettings, glossary: BookGlossary?, cacheProviderId: String, cache: TranslationCache) =
        start(R.string.server_status_restoring) {
            val selected = requireNotNull(state.value.selected?.storedTranslation) { "서버 서재에서 복원할 번역을 먼저 읽어 주세요." }
            val mapping = withContext(Dispatchers.Default) { ServerDocumentMapping.create(document, settings, glossary, cacheProviderId) }
            TranslationCacheSyncService(client(), cache).restore(selected.recordId, mapping)
            mutableState.update { it.copy(status = ServerLibraryMessage(R.string.server_status_restored)) }
        }

    fun cancel() {
        generation += 1
        operation?.cancel()
        operation = null
        mutableState.update { it.copy(busy = false, status = ServerLibraryMessage(R.string.server_status_cancelled), error = null) }
    }

    private fun client() = requireNotNull(store) { "먼저 서버에 연결해 주세요." }
    private fun start(message: Int, work: suspend () -> Unit) {
        if (state.value.busy) return
        // A manual mutation must not be overwritten by an earlier in-flight polling response.
        jobPolling?.cancel()
        val ticket = ++generation
        mutableState.update { it.copy(busy = true, status = ServerLibraryMessage(message), error = null) }
        operation = viewModelScope.launch {
            try { work() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (ticket == generation) mutableState.update { it.copy(error = serverLibraryError(error), status = ServerLibraryMessage(R.string.server_status_failed)) }
            } finally {
                if (ticket == generation) {
                    mutableState.update { it.copy(busy = false) }
                    startJobPolling()
                }
            }
        }
    }
}

internal fun serverLibraryError(error: Exception): ServerLibraryMessage = ServerLibraryMessage(when (error) {
    is ServerAccountInputException -> when (error.field) {
        ServerAccountInputField.Username -> R.string.server_error_username
        ServerAccountInputField.Password -> R.string.server_error_password
        ServerAccountInputField.DisplayName -> R.string.server_error_display_name
        ServerAccountInputField.Locale -> R.string.server_error_locale
        ServerAccountInputField.TargetLanguage -> R.string.server_error_target
    }
    is TranslationStoreException -> when (error.failure) {
        TranslationStoreFailure.AUTHENTICATION -> R.string.server_error_authentication
        TranslationStoreFailure.FORBIDDEN -> R.string.server_error_forbidden
        TranslationStoreFailure.NOT_FOUND -> R.string.server_error_not_found
        TranslationStoreFailure.CONFLICT -> R.string.server_error_conflict
        TranslationStoreFailure.TIMEOUT -> R.string.server_error_timeout
        TranslationStoreFailure.NETWORK -> R.string.server_error_network
        TranslationStoreFailure.INVALID_RESPONSE -> R.string.server_error_response
        else -> R.string.server_error_request
    }
    is TranslationCacheConflictException -> R.string.server_error_cache_conflict
    is IllegalArgumentException -> R.string.server_error_settings
    else -> R.string.server_error_request
})
