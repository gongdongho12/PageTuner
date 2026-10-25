package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** The production callback owner with injected commands; Compose/HTTP/platform IO are not executed. */
@OptIn(ExperimentalCoroutinesApi::class)
class PortableOrganizationAdoptionSessionTest {
    private val connection = ServerReadingConnection("a".repeat(64), HttpTranslationStore("https://reader.example"))
    private val identity = requireNotNull(DocumentIdentityJson.fromDocument(
        PortableDocumentMapper.server(NotesFixture.document().source).documents.single()))
    private val comparison = PortableOrganizationComparison("shown",
        LibraryOrganizationSnapshot(identity = identity, presence = LibraryOrganizationSnapshotPresence.PRESENT,
            organization = LibraryOrganizationSnapshotValue("", emptyList(), false)),
        ServerLibraryOrganization(identity.kind.name, NotesFixture.recordId, 0, null, null), null)
    private val commit = PortableOrganizationCommit(ServerReadingTarget(connection.accountKey, identity.kind.name,
        NotesFixture.recordId), connection)

    private inner class Fake : PortableOrganizationAdoptionCommand {
        var compares = 0; var confirms = 0; var closes = 0
        var compareWork: suspend () -> PortableOrganizationComparison = { comparison }
        var confirmWork: suspend () -> PortableOrganizationCommit = { commit }
        override suspend fun compare(): PortableOrganizationComparison { compares++; return compareWork() }
        override suspend fun confirm(id: String): PortableOrganizationCommit { confirms++; return confirmWork() }
        override fun close() { closes++ }
    }

    @Test fun speculativeRenderAndRetiredCallbacksNeverIssueCommandsFromNewSelection() = runTest {
        val authority = PortableTextAccountAuthority(connection); val fake = Fake(); var factories = 0
        val session = PortableOrganizationAdoptionSession(this, authority, "old", { factories++; fake }, { null })
        val oldBegin = session::compare
        oldBegin(); assertEquals(0, factories)
        session.activate(); assertTrue(session.current()); assertEquals(1, factories)
        session.close(); session.activate(); oldBegin(); runCurrent()
        assertFalse(session.current()); assertEquals(0, fake.compares); assertEquals(1, factories)
        val next = PortableOrganizationAdoptionSession(this, authority, "new", { factories++; Fake() }, { null })
        next.activate(); oldBegin(); runCurrent(); assertEquals(2, factories); assertEquals(0, fake.compares)
        next.close(); authority.close()
    }

    @Test fun sameClientAndKeyAccountABARejectsOldBeginWhileNewAuthorityCanCompare() = runTest {
        val old = PortableTextAccountAuthority(connection); val fake = Fake()
        val session = PortableOrganizationAdoptionSession(this, old, "same-key", { fake }, { null })
        session.activate(); old.close()
        val replacement = PortableTextAccountAuthority(connection)
        session.compare(); runCurrent(); assertEquals(0, fake.compares)
        val nextFake = Fake()
        val next = PortableOrganizationAdoptionSession(this, replacement, "same-key", { nextFake }, { null })
        next.activate(); next.compare(); runCurrent(); assertEquals(1, nextFake.compares)
        session.close(); next.close(); replacement.close()
    }

    @Test fun repeatedComparisonReusesCommandAndConfirmationRequiresTheDisplayedId() = runTest {
        val authority = PortableTextAccountAuthority(connection); val fake = Fake(); var factories = 0
        val session = PortableOrganizationAdoptionSession(this, authority, "selection", { factories++; fake }, { null })
        session.activate(); session.compare(); runCurrent(); session.compare(); runCurrent()
        assertEquals(1, factories); assertEquals(2, fake.compares)
        session.confirm("stale"); runCurrent(); assertEquals(0, fake.confirms)
        session.confirm("shown"); runCurrent(); session.confirm("shown"); runCurrent()
        assertEquals(1, fake.confirms); assertTrue(session.state.value.committed)
        session.close(); authority.close()
    }

    @Test fun heldComparisonAfterDisposalCannotPublishOrConfirm() = runTest {
        val authority = PortableTextAccountAuthority(connection); val fake = Fake()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        fake.compareWork = { entered.complete(Unit); withContext(NonCancellable) { release.await() }; comparison }
        val session = PortableOrganizationAdoptionSession(this, authority, "selection", { fake }, { null })
        session.activate(); session.compare(); entered.await(); session.close(); release.complete(Unit); runCurrent()
        assertNull(session.state.value.comparison); session.confirm("shown"); runCurrent()
        assertEquals(0, fake.confirms); authority.close()
    }

    @Test fun deliveryCapturesBeforeCommitAndNotificationFailureDoesNotUndoDurableSuccess() = runTest {
        val authority = PortableTextAccountAuthority(connection); val fake = Fake()
        var actorSession = 11; var deliveredSession = -1; var captures = 0
        fake.confirmWork = { actorSession = 12; commit }
        val session = PortableOrganizationAdoptionSession(this, authority, "selection", { fake }, { captured ->
            assertSame(connection, captured); captures++; val expected = actorSession
            val deliver: (ServerReadingTarget) -> Unit = { target ->
                assertEquals(commit.target, target); deliveredSession = expected; error("notification rejected")
            }
            deliver
        })
        session.activate(); session.compare(); runCurrent(); session.confirm("shown"); runCurrent()
        assertEquals(1, captures); assertEquals(11, deliveredSession); assertTrue(session.state.value.committed)
        assertNull(session.state.value.error); session.close(); authority.close()
    }

    @Test fun postcommitDisposalKeepsSuccessAndSkipsExpiredDelivery() = runTest {
        val authority = PortableTextAccountAuthority(connection); val fake = Fake(); var deliveries = 0
        lateinit var session: PortableOrganizationAdoptionSession
        fake.confirmWork = { session.close(); commit }
        session = PortableOrganizationAdoptionSession(this, authority, "selection", { fake }, { { deliveries++ } })
        session.activate(); session.compare(); runCurrent(); session.confirm("shown"); runCurrent()
        assertTrue(session.state.value.committed); assertNull(session.state.value.error); assertEquals(0, deliveries)
        authority.close()
    }
}
