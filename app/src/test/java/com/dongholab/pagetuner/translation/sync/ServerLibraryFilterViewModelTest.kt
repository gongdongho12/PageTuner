package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.core.model.library.LibraryFilter
import java.io.IOException
import java.net.URI
import java.util.UUID
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
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerLibraryFilterViewModelTest {
    @Test fun applyAndResetStartAtFirstPageWhilePagingAndRefreshRetainAppliedFilters() = modelTest {
        val requests = mutableListOf<String>()
        val vm = model { request -> requests += request.url; pageResponse(request) }
        vm.connectFixture()
        vm.loadPage(2); vm.idle()
        assertEquals(2, vm.state.value.page!!.page)
        val draft = ServerLibraryFilterDraft(q = "\uFEFF Story \u00A0", folderMode = ServerLibraryFolderFilter.Unfiled,
            tag = " 인물,별명 ", favorite = false)
        vm.updateLibraryFilterDraft(draft)
        assertTrue(vm.applyLibraryFilter()); vm.idle()
        assertEquals(0, vm.state.value.page!!.page)
        assertEquals(LibraryFilter("Story", "", "인물,별명", false), vm.state.value.filter)
        vm.loadPage(1); vm.idle()
        assertTrue(requests.last().contains("page=1&size=12&q=Story&folder=&tag="))
        vm.loadPage(1); vm.idle()
        assertTrue(requests.last().endsWith("favorite=false"))
        vm.resetLibraryFilter(); vm.idle()
        assertEquals(LibraryFilter(), vm.state.value.filter)
        assertEquals(ServerLibraryFilterDraft(), vm.state.value.filterDraft)
        assertTrue(requests.last().endsWith("/translations?page=0&size=12"))
    }

    @Test fun kindAndAccountChangesClearFiltersAndResultsWithoutReusingTheOldPage() = modelTest {
        val requests = mutableListOf<String>()
        val vm = model { request -> requests += request.url; pageResponse(request) }
        vm.connectFixture()
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(favorite = true)); vm.applyLibraryFilter(); vm.idle()
        vm.loadPage(2); vm.idle()
        vm.loadPage(2, ServerLibraryKind.Originals); vm.idle()
        assertTrue(requests.last().endsWith("/chapters?page=0&size=12"))
        assertEquals(ServerLibraryKind.Originals, vm.state.value.kind)
        assertTrue(vm.state.value.page!!.items.all { it.kind == ServerLibraryKind.Originals })
        assertEquals(LibraryFilter(), vm.state.value.filter)
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(q = "secret title")); vm.applyLibraryFilter(); vm.idle()
        vm.updateUsername("other-reader")
        assertFalse(vm.state.value.connected)
        assertNull(vm.state.value.page)
        assertEquals(LibraryFilter(), vm.state.value.filter)
        assertEquals(ServerLibraryFilterDraft(), vm.state.value.filterDraft)
    }

    @Test fun invalidDraftPreservesAppliedResultsAndDoesNotRequest() = modelTest {
        var calls = 0
        val vm = model { calls++; pageResponse(it) }
        vm.connectFixture()
        val page = vm.state.value.page
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(folderMode = ServerLibraryFolderFilter.Exact, folder = "  "))
        assertFalse(vm.applyLibraryFilter())
        assertSame(page, vm.state.value.page)
        assertEquals(1, calls)
        assertEquals(R.string.server_filter_invalid, vm.state.value.error!!.resource)
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(tag = "a".repeat(61)))
        assertFalse(vm.applyLibraryFilter())
        assertEquals(1, calls)
    }

    @Test fun failedNewFilterClearsOldRowsAndRetainsTheQueryForRetry() = modelTest {
        var failFilter = true
        val vm = model { request ->
            if ("q=" in request.url && failFilter) TranslationStoreHttpResponse(503) else pageResponse(request)
        }
        vm.connectFixture()
        assertNotNull(vm.state.value.page)
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(q = "Changed")); vm.applyLibraryFilter(); vm.idle()
        assertNull(vm.state.value.page)
        assertEquals("Changed", vm.state.value.filter.q)
        assertNotNull(vm.state.value.error)
        failFilter = false
        vm.loadPage(); vm.idle()
        assertNotNull(vm.state.value.page)
        assertNull(vm.state.value.error)
        assertEquals("Changed", vm.state.value.filter.q)
    }

    @Test fun cancelledFilterCannotReplaceResetResultsWithLateSuccessOrError() = modelTest {
        listOf(false, true).forEach { fails ->
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val exited = CompletableDeferred<Unit>()
            val vm = model { request ->
                if ("q=slow" in request.url) {
                    entered.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    exited.complete(Unit)
                    if (fails) throw IOException("Late response")
                    pageResponse(request, total = 1)
                } else pageResponse(request)
            }
            vm.connectFixture()
            vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(q = "slow")); vm.applyLibraryFilter(); entered.await()
            vm.resetLibraryFilter(); vm.idle()
            release.complete(Unit); exited.await(); runCurrent()
            assertEquals(LibraryFilter(), vm.state.value.filter)
            assertEquals(25L, vm.state.value.page!!.totalItems)
            assertNull(vm.state.value.error)
            assertFalse(vm.state.value.busy)
        }
    }

    @Test fun switchingKindDuringARequestRejectsTheLateTranslationPage() = modelTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val exited = CompletableDeferred<Unit>()
        val vm = model { request ->
            if ("q=slow" in request.url) {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                exited.complete(Unit)
                pageResponse(request, total = 1)
            } else pageResponse(request)
        }
        vm.connectFixture()
        vm.updateLibraryFilterDraft(ServerLibraryFilterDraft(q = "slow")); vm.applyLibraryFilter(); entered.await()
        vm.loadPage(2, ServerLibraryKind.Originals); vm.idle()
        release.complete(Unit); exited.await(); runCurrent()
        assertEquals(ServerLibraryKind.Originals, vm.state.value.kind)
        assertEquals(LibraryFilter(), vm.state.value.filter)
        assertEquals(0, vm.state.value.page!!.page)
        assertEquals(25L, vm.state.value.page!!.totalItems)
        assertTrue(vm.state.value.page!!.items.all { it.kind == ServerLibraryKind.Originals })
        assertNull(vm.state.value.error)
    }

    private val models = mutableListOf<ServerLibraryViewModel>()
    private fun modelTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally {
            models.forEach { it.disconnect(); it.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            Dispatchers.resetMain()
        }
    }

    private fun model(handler: suspend (TranslationStoreHttpRequest) -> TranslationStoreHttpResponse) =
        ServerLibraryViewModel { endpoint, auth -> HttpTranslationStore(endpoint, auth, TranslationStoreHttpTransport { request ->
            if (request.url.endsWith("/accounts/me")) TranslationStoreHttpResponse(200, body = profile) else handler(request)
        }) }.also(models::add)

    private suspend fun ServerLibraryViewModel.connectFixture() {
        updateEndpoint("https://reader.example"); updateUsername("reader"); updatePassword("test-password")
        connect(); idle()
        assertTrue(state.value.connected)
    }
    private suspend fun ServerLibraryViewModel.idle() { state.first { !it.busy } }

    private fun pageResponse(request: TranslationStoreHttpRequest, total: Int = 25): TranslationStoreHttpResponse {
        val page = URI(request.url).rawQuery.substringAfter("page=").substringBefore('&').toInt()
        val values = JSONArray()
        for (index in page * 12 until minOf((page + 1) * 12, total)) values.put(JSONObject()
            .put("recordId", UUID(0, index.toLong() + 1).toString()).put("bookTitle", "Book $index")
            .put("paragraphCount", 1).put("sourceRevision", "revision").put("targetLanguage", "ko").put("sourceLanguage", "en"))
        return TranslationStoreHttpResponse(200, body = JSONObject().put("items", values).put("page", page).put("size", 12)
            .put("totalItems", total).put("totalPages", (total + 11) / 12).put("hasNext", page + 1 < (total + 11) / 12).toString())
    }
    private val profile = """{"accountId":"11111111-1111-4111-8111-111111111111","username":"reader","displayName":"Reader","locale":"en","targetLanguage":"ko","effectiveLocale":"en"}"""
}
