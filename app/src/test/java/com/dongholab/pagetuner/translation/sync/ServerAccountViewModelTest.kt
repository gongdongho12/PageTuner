package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.R
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerAccountViewModelTest {
    private val models = mutableListOf<ServerLibraryViewModel>()
    @Test fun registrationAndProfileLanguageKeepTranslationTargetSeparateAndPasswordInMemory() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var saved = profile("ko", "ja")
            val requests = mutableListOf<TranslationStoreHttpRequest>()
            val vm = viewModel { request ->
                requests += request
                when {
                    request.url.endsWith("/csrf") -> csrf()
                    request.url.endsWith("/register") -> TranslationStoreHttpResponse(201, body = saved)
                    request.method == "PATCH" -> {
                        val body = JSONObject(request.body!!)
                        saved = profile(body.getString("locale"), body.getString("targetLanguage"))
                        TranslationStoreHttpResponse(200, body = saved)
                    }
                    else -> error("Unexpected request")
                }
            }
            vm.updateEndpoint("https://reader.example")
            vm.updateUsername("Reader")
            vm.updatePassword("long-password")
            vm.updateProfileDraft(ServerAccountDraft("Reader", "ko", "ja"))
            vm.register()
            vm.state.first { !it.busy }
            assertTrue(vm.state.value.connected)
            assertEquals("reader", vm.state.value.connection.username)
            assertEquals("ko", vm.state.value.uiLocale)
            assertFalse(vm.state.value.toString().contains("long-password"))
            assertTrue(requests.all { "Authorization" !in it.headers })

            vm.updateProfileDraft(vm.state.value.profileDraft.copy(locale = "fr"))
            vm.saveProfile()
            vm.state.first { !it.busy }
            assertEquals("fr", vm.state.value.profile!!.locale)
            assertEquals("en", vm.state.value.uiLocale)
            assertEquals("ja", vm.state.value.profile!!.targetLanguage)
            vm.disconnect()
            assertEquals("", vm.state.value.connection.password)
            assertNull(vm.state.value.profile)
            assertEquals("en", vm.state.value.uiLocale)
        } finally { closeModels(); Dispatchers.resetMain() }
    }

    @Test fun failedProfileSaveKeepsTheLastConfirmedProfileAndLocale() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vm = viewModel { request -> when {
                request.url.endsWith("/csrf") -> csrf()
                request.url.endsWith("/register") -> TranslationStoreHttpResponse(201, body = profile("ko", "ja"))
                else -> TranslationStoreHttpResponse(403)
            } }
            vm.updateEndpoint("https://reader.example"); vm.updateUsername("reader"); vm.updatePassword("long-password")
            vm.updateProfileDraft(ServerAccountDraft("Reader", "ko", "ja"))
            vm.register(); vm.state.first { !it.busy }
            vm.updateProfileDraft(vm.state.value.profileDraft.copy(locale = "en"))
            vm.saveProfile(); vm.state.first { !it.busy }
            assertEquals("ko", vm.state.value.profile!!.locale)
            assertEquals("ko", vm.state.value.uiLocale)
            assertEquals(R.string.server_error_forbidden, vm.state.value.error!!.resource)
            vm.disconnect()
        } finally { closeModels(); Dispatchers.resetMain() }
    }

    @Test fun disconnectDuringLoginCannotRestoreAnOldSession() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val entered = CompletableDeferred<Unit>()
            val response = CompletableDeferred<TranslationStoreHttpResponse>()
            val vm = viewModel { entered.complete(Unit); response.await() }
            vm.updateEndpoint("https://reader.example"); vm.updateUsername("reader"); vm.updatePassword("long-password")
            vm.connect()
            entered.await()
            vm.disconnect()
            response.complete(TranslationStoreHttpResponse(200, body = profile("ko", "ja")))
            assertFalse(vm.state.value.connected)
            assertNull(vm.state.value.profile)
            assertEquals("", vm.state.value.connection.password)
        } finally { closeModels(); Dispatchers.resetMain() }
    }

    private fun viewModel(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        ServerLibraryViewModel { endpoint, auth -> HttpTranslationStore(endpoint, auth, TranslationStoreHttpTransport(handler)) }.also(models::add)
    private suspend fun closeModels() {
        models.forEach { model ->
            model.disconnect()
            model.viewModelScope.coroutineContext[Job]?.cancelAndJoin()
        }
    }
    private fun csrf() = TranslationStoreHttpResponse(200, mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
        """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
    private fun profile(locale: String, target: String) = """{"accountId":"11111111-1111-4111-8111-111111111111","username":"reader","displayName":"Reader","locale":"$locale","targetLanguage":"$target","effectiveLocale":"${if (locale == "ko") "ko" else "en"}"}"""
}
