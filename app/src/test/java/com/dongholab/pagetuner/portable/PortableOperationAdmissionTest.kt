package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.NotesFixture
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

/** Actual production operation admission and memory tickets; no Android Context/VM/Compose execution. */
class PortableOperationAdmissionTest {
    private val shown = PortableLibraryState(status = 123, error = "Current owner's existing message",
        glossaryPresence = BookGlossarySnapshotPresence.PRESENT,
        organizationPresence = LibraryOrganizationSnapshotPresence.PRESENT,
        progressPresence = ReadingProgressSnapshotPresence.PRESENT)

    @Test fun staleUriAfterReplacementCannotClearTheNewOwnersPresenceOrError() {
        val tickets = PortableExportTickets(); val old = tickets.begin("old.zip"); tickets.prepare(old, byteArrayOf(1), null)
        val replacement = tickets.begin("new.zip"); tickets.prepare(replacement, byteArrayOf(2), null)
        val result = admitPortableOperation(shown) { tickets.checkReadyId(old.id, null) }
        assertFalse(result.admitted); assertSame(shown, result.state)
        assertEquals(replacement, tickets.checkReadyId(replacement.id, null))
    }

    @Test fun exactReadyRequestAdmitsWithoutRunningBlockingSourceGuard() {
        val tickets = PortableExportTickets(); val request = tickets.begin("ready.zip"); var sourceChecks = 0
        tickets.prepare(request, byteArrayOf(3), null) { sourceChecks++ }
        val result = admitPortableOperation(shown) { assertEquals(request, tickets.checkReadyId(request.id, null)) }
        assertTrue(result.admitted); assertTrue(result.state.busy); assertNull(result.state.error); assertNull(result.state.status)
        assertNull(result.state.progressPresence); assertNull(result.state.organizationPresence); assertNull(result.state.glossaryPresence)
        assertEquals(1, sourceChecks)
    }

    @Test fun busyOperationIsNotAdmittedAndDoesNotCallAnAdmissionGuard() {
        val busy = shown.copy(busy = true); var called = false
        val result = admitPortableOperation(busy) { called = true }
        assertFalse(result.admitted); assertSame(busy, result.state); assertFalse(called)
    }

    @Test fun expiredCommittedGrantCannotResetAReplacementScreenBeforePreparation() {
        val owner = PortableTextAccountAuthority(NotesFixture.connection { error("No HTTP") })
        val session = PortableAccountExportSession(owner, "entry").apply { activate() }; val grant = session.issue()
        session.close()
        val result = admitPortableOperation(shown, grant::checkCurrent)
        assertFalse(result.admitted); assertSame(shown, result.state)
    }

    @Test fun duplicateCallbackDuringWriterClaimCannotStartAnotherUiOperation() {
        val tickets = PortableExportTickets(); val request = tickets.begin("one-shot.zip"); tickets.prepare(request, byteArrayOf(4), null)
        var rejected = false
        tickets.write(request.id, { null }) {
            val result = admitPortableOperation(shown) { tickets.checkReadyId(request.id, null) }
            rejected = !result.admitted; assertSame(shown, result.state)
            ByteArrayOutputStream()
        }
        assertTrue(rejected)
    }
}