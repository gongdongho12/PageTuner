package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerTranslationJobsViewModelTest {
    @Test fun originalEntryCanCreateAJobAndKeyIsClearedAfterSubmission() = runTest {
        var sent: JSONObject? = null
        withModel({ request -> when {
            request.url.contains("/chapters/") -> JobsFixture.response(JobsFixture.original())
            request.url.endsWith("/translation-providers") -> JobsFixture.response(JobsFixture.providers())
            request.method == "POST" -> {
                sent = JSONObject(request.body!!)
                JobsFixture.response(JobsFixture.job("QUEUED", "DEEPSEEK", listOf(ServerJobGlossaryEntry("Original", "원문"))))
            }
            else -> JobsFixture.response(JobsFixture.page(JobsFixture.job("QUEUED", "DEEPSEEK")))
        } }) { vm ->
            vm.prepareTranslation(JobsFixture.entry); vm.awaitIdle()
            assertEquals(JobsFixture.chapter, vm.state.value.jobSource!!.sourceContent)
            vm.selectJobProvider("DEEPSEEK")
            vm.updateJobDraft(vm.state.value.jobDraft.copy(apiKey = "request-memory-key", glossary = "Original=원문"))
            assertFalse(vm.state.value.toString().contains("request-memory-key"))
            vm.submitTranslationJob(); vm.awaitIdle()
            assertNull(vm.state.value.error)
            assertEquals("request-memory-key", sent!!.getString("apiKey"))
            assertEquals("DEEPSEEK", sent!!.getString("providerKind"))
            assertEquals("", vm.state.value.jobDraft.apiKey)
            assertEquals("QUEUED", vm.state.value.latestJob!!.status)
            vm.disconnect()
            assertNull(vm.state.value.latestJob)
            assertEquals("", vm.state.value.connection.password)
        }
    }

    @Test fun retryRestoresExactSettingsAndOnlyAllowsChangingTheInMemoryKey() = runTest {
        val terms = listOf(ServerJobGlossaryEntry("a=b", "x\ny"))
        val failed = ServerTranslationJobJson.job(JobsFixture.job("FAILED", "DEEPSEEK", terms))
        var sent: JSONObject? = null
        withModel({ request -> when {
            request.url.contains("/chapters/") -> JobsFixture.response(JobsFixture.original())
            request.url.endsWith("/translation-providers") -> JobsFixture.response(JobsFixture.providers())
            request.method == "POST" -> {
                sent = JSONObject(request.body!!)
                JobsFixture.response(JobsFixture.job("QUEUED", "DEEPSEEK", terms))
            }
            else -> JobsFixture.response(JobsFixture.page(JobsFixture.job("QUEUED", "DEEPSEEK", terms)))
        } }) { vm ->
            vm.prepareRetry(failed); vm.awaitIdle()
            val restored = vm.state.value.jobDraft
            vm.updateJobDraft(restored.copy(apiKey = "new-key", targetLanguage = "ja", glossary = "changed=other", model = "different"))
            vm.selectJobProvider("GOOGLE_WEB_TRANSLATE_HTML")
            assertEquals(restored.copy(apiKey = "new-key"), vm.state.value.jobDraft)
            vm.submitTranslationJob(); vm.awaitIdle()
            assertNull(vm.state.value.error)
            assertEquals(failed.jobId, sent!!.getString("retryOf"))
            assertEquals("ko", sent!!.getString("targetLanguage"))
            assertEquals("deepseek-chat", sent!!.getString("model"))
            assertEquals("a=b", sent!!.getJSONArray("glossary").getJSONObject(0).getString("source"))
            assertEquals("x\ny", sent!!.getJSONArray("glossary").getJSONObject(0).getString("target"))
        }
    }

    @Test fun manualCancelCannotBeOverwrittenByAnEarlierPollingResponse() = runTest {
        val pollEntered = CompletableDeferred<Unit>()
        val pollResponse = CompletableDeferred<TranslationStoreHttpResponse>()
        var lists = 0
        withModel({ request -> when {
            request.url.endsWith("/cancel") -> JobsFixture.response(JobsFixture.job("CANCELLED"))
            request.url.contains("/translation-jobs?") -> {
                lists += 1
                if (lists == 1) JobsFixture.response(JobsFixture.page(JobsFixture.job("RUNNING")))
                else { pollEntered.complete(Unit); pollResponse.await() }
            }
            else -> error("Unexpected job request")
        } }) { vm ->
            vm.loadJobs(); vm.awaitIdle()
            advanceTimeBy(3_000); runCurrent(); pollEntered.await()
            vm.cancelServerJob(vm.state.value.jobs!!.items.single())
            pollResponse.complete(JobsFixture.response(JobsFixture.page(JobsFixture.job("RUNNING"))))
            vm.awaitIdle()
            assertEquals("CANCELLED", vm.state.value.latestJob!!.status)
            assertEquals("CANCELLED", vm.state.value.jobs!!.items.single().status)
            advanceTimeBy(6_000); runCurrent()
            assertEquals(2, lists)
            assertEquals(R.string.server_job_cancelled, vm.state.value.status.resource)
        }
    }

    private suspend fun ServerLibraryViewModel.awaitIdle() { state.first { !it.busy } }
    private suspend fun TestScope.withModel(
        handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse,
        work: suspend (ServerLibraryViewModel) -> Unit,
    ) {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val vm = ServerLibraryViewModel { endpoint, auth -> HttpTranslationStore(endpoint, auth, TranslationStoreHttpTransport { request ->
            when {
                request.url.endsWith("/csrf") -> JobsFixture.csrf()
                request.url.endsWith("/register") -> TranslationStoreHttpResponse(201, body = JobsFixture.profile().toString())
                else -> handler(request)
            }
        }) }
        try {
            vm.updateEndpoint("https://reader.example"); vm.updateUsername("reader"); vm.updatePassword("long-password")
            vm.updateProfileDraft(ServerAccountDraft("Reader"))
            vm.register(); vm.awaitIdle()
            assertTrue(vm.state.value.connected)
            work(vm)
        } finally {
            vm.disconnect()
            vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
            Dispatchers.resetMain()
        }
    }
}
