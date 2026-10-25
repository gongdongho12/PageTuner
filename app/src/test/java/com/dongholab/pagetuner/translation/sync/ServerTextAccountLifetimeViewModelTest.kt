package com.dongholab.pagetuner.translation.sync

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.dongholab.pagetuner.portable.*
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Production VM ownership with injected HTTP transport; no real server or Compose lifecycle. */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerTextAccountLifetimeViewModelTest {
    private val models = mutableListOf<ServerLibraryViewModel>()

    @Test fun verifiedProfileAndExactClientArePublishedTogetherAndGettersNeverCreateAuthority() = accountTest {
        val f = Fixture(); val vm = f.vm
        assertNull(vm.textAccountAuthority()); assertNull(vm.readingConnection()); assertEquals(0, f.factories)
        vm.credentials(); vm.connect(); vm.idle()
        val authority = requireNotNull(vm.textAccountAuthority())
        assertEquals(serverReadingAccountKey("https://reader.example:443/", "reader"), authority.connection.accountKey)
        assertSame(f.created.single(), authority.connection.client)
        assertEquals("READER", vm.state.value.connection.username)
        assertEquals("reader", vm.state.value.profile!!.username)
        repeat(20) {
            assertSame(authority, vm.textAccountAuthority())
            assertSame(authority.connection, vm.readingConnection())
        }
        assertEquals(1, f.factories)
        ticket(authority).check()
    }

    @Test fun registrationPublishesAuthenticatedClientRatherThanAnonymousRegistrationClient() = accountTest {
        val f = Fixture(); f.vm.credentials(); f.vm.register(); f.vm.idle()
        val authority = requireNotNull(f.vm.textAccountAuthority())
        assertEquals(2, f.factories)
        assertNotSame(f.created.first(), authority.connection.client)
        assertSame(f.created.last(), authority.connection.client)
        assertEquals("reader", f.vm.state.value.connection.username)
        ticket(authority).check()
    }

    @Test fun everyInputChangeRetiresOldAuthorityAndReadOnlyGetterCannotReissueIt() = accountTest {
        val f = Fixture()
        listOf<(ServerLibraryViewModel) -> Unit>(
            { it.updateEndpoint("https://other.example") }, { it.updateUsername("other") }, { it.updatePassword("changed-password") },
        ).forEach { edit ->
            f.vm.credentials(); f.vm.connect(); f.vm.idle()
            val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
            val count = f.factories
            edit(f.vm)
            assertNull(f.vm.textAccountAuthority()); assertNull(f.vm.readingConnection())
            assertExpired(captured); assertExpired(old)
            repeat(5) { assertNull(f.vm.textAccountAuthority()) }
            assertEquals(count, f.factories)
        }
    }

    @Test fun replacementUsingSameClientAndKeyStillPermanentlyRetiresPriorGeneration() = accountTest {
        val f = Fixture(sharedClient = true); f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
        f.vm.connect(); f.vm.idle()
        val fresh = requireNotNull(f.vm.textAccountAuthority())
        assertNotSame(old, fresh); assertSame(old.connection.client, fresh.connection.client)
        assertEquals(old.connection.accountKey, fresh.connection.accountKey)
        assertExpired(captured); assertExpired(old); ticket(fresh).check()
    }

    @Test fun accountAndOriginABAWithSameClientNeverRevivesOldAuthority() = accountTest {
        val f = Fixture(sharedClient = true); f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
        f.username = "other"; f.vm.updateEndpoint("https://other.example"); f.vm.updateUsername("other")
        f.vm.connect(); f.vm.idle()
        val middle = requireNotNull(f.vm.textAccountAuthority()); val middleTicket = ticket(middle)
        assertNotEquals(old.connection.accountKey, middle.connection.accountKey)
        f.username = "reader"; f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val latest = requireNotNull(f.vm.textAccountAuthority())
        assertEquals(old.connection.accountKey, latest.connection.accountKey)
        assertSame(old.connection.client, latest.connection.client)
        assertExpired(captured); assertExpired(old); assertExpired(middleTicket); ticket(latest).check()
    }

    @Test fun failedInitialLoginDoesNotIssueAndFailedReconnectPreservesUnchangedVerifiedAuthority() = accountTest {
        val f = Fixture(); f.profileResponse = { TranslationStoreHttpResponse(401) }
        f.vm.credentials(); f.vm.connect(); f.vm.idle(); assertNull(f.vm.textAccountAuthority())
        f.profileResponse = null; f.vm.connect(); f.vm.idle()
        val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
        f.profileResponse = { TranslationStoreHttpResponse(401) }
        f.vm.connect(); f.vm.idle()
        assertSame(old, f.vm.textAccountAuthority()); assertTrue(f.vm.state.value.connected); captured.check()
    }

    @Test fun disconnectAndRealViewModelClearRetireAndClearNeverAllowsNewWork() = accountTest {
        val f = Fixture(); f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
        f.vm.disconnect(); assertExpired(captured); assertNull(f.vm.textAccountAuthority())
        f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val current = requireNotNull(f.vm.textAccountAuthority()); val currentTicket = ticket(current)
        ViewModelStore().also { it.put("account", f.vm); it.clear() }
        assertExpired(currentTicket); assertNull(f.vm.textAccountAuthority()); assertNull(f.vm.pdfConnection())
        val count = f.factories
        f.vm.connect(); f.vm.register(); f.vm.updateEndpoint("https://later.example")
        assertEquals(count, f.factories); assertNull(f.vm.textAccountAuthority())
    }

    @Test fun clearDuringNonCancellableLoginCannotPublishLateAuthority() = accountTest {
        listOf(false, true).forEach { register ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
            val f = Fixture()
            val held: suspend () -> TranslationStoreHttpResponse = {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() }
                exited.complete(Unit)
                TranslationStoreHttpResponse(if (register) 201 else 200, body = f.profile())
            }
            if (register) f.registerResponse = held else f.profileResponse = held
            f.vm.credentials(); if (register) f.vm.register() else f.vm.connect()
            entered.await()
            val oldOperation = f.vm.viewModelScope.coroutineContext[Job]!!.children.single()
            ViewModelStore().also { it.put("account", f.vm); it.clear() }
            release.complete(Unit); exited.await(); oldOperation.join()
            assertNull(f.vm.textAccountAuthority()); assertNull(f.vm.readingConnection()); assertNull(f.vm.pdfConnection())
            assertEquals(1, f.factories)
        }
    }

    @Test fun confirmedPasswordChangeRetiresAuthorityUntilExplicitRelogin() = accountTest {
        val f = Fixture(); f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val old = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(old)
        f.vm.updatePasswordDraft(validDraft()); f.vm.changePassword(); f.vm.idle()
        assertFalse(f.vm.state.value.connected); assertNull(f.vm.textAccountAuthority()); assertExpired(captured)
        val count = f.factories
        repeat(5) { assertNull(f.vm.readingConnection()); assertNull(f.vm.textAccountAuthority()) }
        assertEquals(count, f.factories)
        f.vm.updatePassword("new-password"); f.vm.connect(); f.vm.idle()
        val fresh = requireNotNull(f.vm.textAccountAuthority()); assertNotSame(old, fresh); ticket(fresh).check()
    }

    @Test fun uncertainAuthenticationAndConflictPasswordOutcomesRetireButDefinitiveRejectionKeepsAuthority() = accountTest {
        val failures: List<suspend () -> TranslationStoreHttpResponse> = listOf(
            { throw SocketTimeoutException() }, { TranslationStoreHttpResponse(401) },
            { TranslationStoreHttpResponse(409, body = """{"code":"PASSWORD_CHANGE_CONFLICT"}""") },
            { TranslationStoreHttpResponse(503) },
        )
        failures.forEach { response ->
            val f = Fixture(); f.passwordResponse = response
            f.vm.credentials(); f.vm.connect(); f.vm.idle(); val old = ticket(requireNotNull(f.vm.textAccountAuthority()))
            f.vm.updatePasswordDraft(validDraft()); f.vm.changePassword(); f.vm.idle()
            assertNull(f.vm.textAccountAuthority()); assertExpired(old)
        }
        val f = Fixture(); f.passwordResponse = { TranslationStoreHttpResponse(400, body = """{"code":"CURRENT_PASSWORD_INCORRECT"}""") }
        f.vm.credentials(); f.vm.connect(); f.vm.idle(); val old = requireNotNull(f.vm.textAccountAuthority())
        f.vm.updatePasswordDraft(validDraft()); f.vm.changePassword(); f.vm.idle()
        assertSame(old, f.vm.textAccountAuthority()); ticket(old).check()
    }

    @Test fun cancelledOldPasswordFailuresCannotClearReplacementLogin() = accountTest {
        val failures: List<suspend () -> TranslationStoreHttpResponse> = listOf(
            { throw SocketTimeoutException() }, { TranslationStoreHttpResponse(401) },
            { TranslationStoreHttpResponse(409, body = """{"code":"PASSWORD_CHANGE_CONFLICT"}""") },
        )
        failures.forEach { failure ->
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); val exited = CompletableDeferred<Unit>()
            val f = Fixture()
            f.passwordResponse = {
                entered.complete(Unit)
                try { withContext(NonCancellable) { release.await(); failure() } } finally { exited.complete(Unit) }
            }
            f.vm.credentials(); f.vm.connect(); f.vm.idle(); val old = ticket(requireNotNull(f.vm.textAccountAuthority()))
            f.vm.updatePasswordDraft(validDraft()); f.vm.changePassword(); entered.await()
            val oldOperation = f.vm.viewModelScope.coroutineContext[Job]!!.children.single()
            f.vm.cancel()
            assertExpired(old); assertNull(f.vm.textAccountAuthority())
            f.vm.updatePassword("new-password"); f.vm.connect(); f.vm.idle()
            val fresh = requireNotNull(f.vm.textAccountAuthority())
            release.complete(Unit); exited.await(); oldOperation.join()
            assertSame(fresh, f.vm.textAccountAuthority()); assertTrue(f.vm.state.value.connected); ticket(fresh).check()
        }
    }

    @Test fun actualDisconnectWaitsForCapturedCommitAndDoesNotRewriteItsSuccessfulResult() = accountTest {
        val f = Fixture(); f.vm.credentials(); f.vm.connect(); f.vm.idle()
        val authority = requireNotNull(f.vm.textAccountAuthority()); val captured = ticket(authority)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val observedBlocked = AtomicBoolean(false); val workers = Executors.newFixedThreadPool(2)
        val mainThread = Thread.currentThread()
        try {
            val commit = workers.submit<Int> {
                captured.withCurrent {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    7
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val observer = workers.submit {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (mainThread.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                observedBlocked.set(mainThread.state == Thread.State.BLOCKED)
                // Lookup adds no owner monitor and still returns the immutable publication.
                assertSame(authority, f.vm.textAccountAuthority())
                release.countDown()
            }
            f.vm.disconnect()
            observer.get(5, TimeUnit.SECONDS)
            assertTrue(observedBlocked.get()); assertEquals(7, commit.get(5, TimeUnit.SECONDS))
            assertNull(f.vm.textAccountAuthority()); assertExpired(captured)
        } finally { release.countDown(); workers.shutdownNow() }
    }

    private inner class Fixture(sharedClient: Boolean = false) {
        @Volatile var username = "reader"
        var factories = 0
        val created = mutableListOf<HttpTranslationStore>()
        var profileResponse: (suspend () -> TranslationStoreHttpResponse)? = null
        var registerResponse: (suspend () -> TranslationStoreHttpResponse)? = null
        var passwordResponse: suspend () -> TranslationStoreHttpResponse = { TranslationStoreHttpResponse(204) }
        private val transport = TranslationStoreHttpTransport { request -> when {
            request.url.endsWith("/csrf") -> TranslationStoreHttpResponse(200,
                mapOf("Set-Cookie" to listOf("JSESSIONID=session; Path=/; HttpOnly")),
                """{"headerName":"X-CSRF-TOKEN","token":"csrf-token"}""")
            request.url.endsWith("/register") -> registerResponse?.invoke() ?: TranslationStoreHttpResponse(201, body = profile())
            request.url.endsWith("/accounts/me") -> profileResponse?.invoke() ?: TranslationStoreHttpResponse(200, body = profile())
            request.url.contains("/translations?page=") -> TranslationStoreHttpResponse(200,
                body = """{"items":[],"page":0,"size":12,"totalItems":0,"totalPages":0,"hasNext":false}""")
            request.url.endsWith("/accounts/me/password") -> passwordResponse()
            else -> error("Unexpected request")
        } }
        private val shared = if (sharedClient) HttpTranslationStore("https://reader.example",
            TranslationStoreBasicAuth("reader", "old-password"), transport) else null
        val vm = ServerLibraryViewModel { endpoint, auth ->
            factories++
            (shared ?: HttpTranslationStore(endpoint, auth, transport)).also { created += it }
        }.also(models::add)
        fun profile() = """{"accountId":"11111111-1111-4111-8111-111111111111","username":"$username","displayName":"Reader","locale":"en","targetLanguage":"ko","effectiveLocale":"en"}"""
    }

    private fun ServerLibraryViewModel.credentials() {
        updateEndpoint("https://reader.example:443/"); updateUsername("READER"); updatePassword("old-password")
        updateProfileDraft(ServerAccountDraft("Reader", "en", "ko"))
    }
    private suspend fun ServerLibraryViewModel.idle() { state.first { !it.busy } }
    private fun validDraft() = ServerPasswordChangeDraft("old-password", "new-password", "new-password")
    private fun ticket(authority: PortableTextAccountAuthority): PortableTextOperationTicket =
        authority.operation("copy:0").also { it.activate() }.issue(authority.connection, "copy:0")
    private fun assertExpired(ticket: PortableTextOperationTicket) {
        assertThrows(PortableTextOperationExpired::class.java) { ticket.check() }
    }
    private fun assertExpired(authority: PortableTextAccountAuthority) {
        assertThrows(PortableTextOperationExpired::class.java) { authority.operation("copy:0") }
    }
    private fun accountTest(block: suspend TestScope.() -> Unit) = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try { block() } finally {
            models.forEach { vm -> vm.disconnect(); vm.viewModelScope.coroutineContext[Job]?.cancelAndJoin() }
            Dispatchers.resetMain()
        }
    }
}
