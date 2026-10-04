package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import java.net.SocketTimeoutException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerPasswordChangeViewModelTest {
    @Test fun confirmedChangeClearsEveryCredentialWithoutChangingPreferencesOrIssuingMoreRequests() = accountTest {
        val requests = mutableListOf<TranslationStoreHttpRequest>()
        val vm = createModel { request ->
            requests += request
            TranslationStoreHttpResponse(204)
        }
        vm.registerFixture()
        vm.updatePasswordDraft(validDraft())
        assertFalse(vm.state.value.toString().contains("new-password"))
        vm.changePassword(); vm.state.first { !it.busy }
        assertSignedOut(vm)
        assertEquals(R.string.server_password_changed, vm.state.value.status.resource)
        assertEquals(ServerAccountDraft("Reader", "fr", "ja"), vm.state.value.profileDraft)
        assertEquals("en", vm.state.value.uiLocale)
        assertEquals(1, requests.size)
        assertTrue(requests.single().url.endsWith("/accounts/me/password"))
    }

    @Test fun validationDoesNotSendAndDefinitiveServerErrorKeepsAccountWithClearedForm() = accountTest {
        var requests = 0
        val vm = createModel { requests++; TranslationStoreHttpResponse(400, body = """{"code":"CURRENT_PASSWORD_INCORRECT"}""") }
        vm.registerFixture()
        vm.updatePasswordDraft(validDraft().copy(confirmation = "different-password"))
        vm.changePassword(); vm.state.first { !it.busy }
        assertEquals(R.string.server_error_password_confirmation, vm.state.value.error!!.resource)
        assertEquals(0, requests)
        vm.updatePasswordDraft(validDraft())
        vm.changePassword(); vm.state.first { !it.busy }
        assertEquals(1, requests)
        assertTrue(vm.state.value.connected)
        assertEquals("old-password", vm.state.value.connection.password)
        assertEquals(ServerPasswordChangeDraft(), vm.state.value.passwordDraft)
        assertEquals(R.string.server_error_current_password_incorrect, vm.state.value.error!!.resource)
    }

    @Test fun timeoutSignsOutWithUncertainResultAndDoesNotRetry() = accountTest {
        var requests = 0
        val vm = createModel { requests++; throw SocketTimeoutException("old-password new-password") }
        vm.registerFixture(); vm.updatePasswordDraft(validDraft())
        vm.changePassword(); vm.state.first { !it.busy }
        assertSignedOut(vm)
        assertEquals(R.string.server_password_change_uncertain, vm.state.value.status.resource)
        assertNull(vm.state.value.error)
        assertEquals(1, requests)
    }

    @Test fun staleAuthenticationAndConcurrentChangesClearTheSession() = accountTest {
        listOf(
            TranslationStoreHttpResponse(401) to R.string.server_password_sign_in_again,
            TranslationStoreHttpResponse(409, body = """{"code":"PASSWORD_CHANGE_CONFLICT"}""") to R.string.server_error_password_conflict,
        ).forEach { (response, message) ->
            val vm = createModel { response }
            vm.registerFixture(); vm.updatePasswordDraft(validDraft())
            vm.changePassword(); vm.state.first { !it.busy }
            assertSignedOut(vm)
            assertEquals(message, vm.state.value.status.resource)
        }
    }

    @Test fun cancellingAnInFlightChangeClearsCredentialsAndLateSuccessCannotOverwriteNewLogin() = accountTest {
        val entered = CompletableDeferred<Unit>()
        val response = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val vm = createModel {
            entered.complete(Unit)
            withContext(NonCancellable) { response.await() }
            exited.complete(Unit)
            TranslationStoreHttpResponse(204)
        }
        vm.registerFixture(); vm.updatePasswordDraft(validDraft())
        vm.changePassword(); entered.await()
        vm.cancel()
        assertSignedOut(vm)
        assertEquals(R.string.server_password_change_cancelled, vm.state.value.status.resource)
        vm.updatePassword("new-password")
        vm.connect(); vm.state.first { !it.busy }
        assertTrue(vm.state.value.connected)
        response.complete(Unit); exited.await()
        assertTrue(vm.state.value.connected)
        assertEquals("new-password", vm.state.value.connection.password)
        assertEquals(R.string.server_status_connected, vm.state.value.status.resource)
    }

    @Test fun explicitDisconnectRetainsTheUncertainResultNotice() = accountTest {
        val entered = CompletableDeferred<Unit>()
        val vm = createModel { entered.complete(Unit); CompletableDeferred<TranslationStoreHttpResponse>().await() }
        vm.registerFixture(); vm.updatePasswordDraft(validDraft())
        vm.changePassword(); entered.await()
        vm.disconnect()
        assertSignedOut(vm)
        assertEquals(R.string.server_password_change_cancelled, vm.state.value.status.resource)
    }

    private val models = mutableListOf<ServerLibraryViewModel>()

    private fun accountTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally {
            models.forEach { vm -> vm.disconnect(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            Dispatchers.resetMain()
        }
    }

    private fun createModel(passwordHandler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        ServerLibraryViewModel { endpoint, auth -> HttpTranslationStore(endpoint, auth, TranslationStoreHttpTransport { request ->
            when {
                request.url.endsWith("/csrf") -> TranslationStoreHttpResponse(200,
                    mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
                    """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
                request.url.endsWith("/register") -> TranslationStoreHttpResponse(201, body = profile)
                request.url.endsWith("/accounts/me") -> TranslationStoreHttpResponse(200, body = profile)
                request.url.contains("/translations?page=") -> TranslationStoreHttpResponse(200,
                    body = """{"items":[],"page":0,"size":12,"totalItems":0,"totalPages":0,"hasNext":false}""")
                else -> passwordHandler(request)
            }
        }) }.also(models::add)

    private suspend fun ServerLibraryViewModel.registerFixture() {
        updateEndpoint("https://reader.example"); updateUsername("reader"); updatePassword("old-password")
        updateProfileDraft(ServerAccountDraft("Reader", "fr", "ja"))
        register(); state.first { !it.busy }
        assertTrue(state.value.connected)
    }

    private fun assertSignedOut(vm: ServerLibraryViewModel) {
        assertFalse(vm.state.value.connected)
        assertNull(vm.state.value.profile)
        assertEquals("", vm.state.value.connection.password)
        assertEquals(ServerPasswordChangeDraft(), vm.state.value.passwordDraft)
        assertEquals("", vm.state.value.jobDraft.apiKey)
        assertFalse(vm.state.value.passwordChanging)
    }

    private fun validDraft() = ServerPasswordChangeDraft("old-password", "new-password", "new-password")
    private val profile = """{"accountId":"11111111-1111-4111-8111-111111111111","username":"reader","displayName":"Reader","locale":"fr","targetLanguage":"ja","effectiveLocale":"en"}"""
}
