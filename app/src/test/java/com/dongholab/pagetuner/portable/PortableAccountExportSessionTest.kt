package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.NotesFixture
import org.junit.Assert.*
import org.junit.Test

/** Production committed-issuer primitive, not a Compose runtime or physical account/device test. */
class PortableAccountExportSessionTest {
    private fun authority() = PortableTextAccountAuthority(NotesFixture.connection { error("Issuing must not request HTTP") })
    private fun expired(action: () -> Unit) { assertTrue(runCatching(action).exceptionOrNull() is IllegalStateException) }

    @Test fun newSessionCannotIssueUntilItsCommittedActivation() {
        val owner = authority(); val session = PortableAccountExportSession(owner, "selected")
        expired { session.issue() }; expired { session.check() }
        session.activate()
        val grant = session.issue(); assertSame(owner, grant.authority)
        owner.check(grant.ticket); grant.checkCurrent(); session.check()
    }

    @Test fun disposeBeforeActivationAndStrictModeReplayCannotReviveAbandonedInstance() {
        val owner = authority(); val abandoned = PortableAccountExportSession(owner, "same")
        abandoned.close(); abandoned.activate(); expired { abandoned.issue() }
        val committed = PortableAccountExportSession(owner, "same").apply { activate() }
        committed.issue().checkCurrent(); abandoned.activate(); expired { abandoned.issue() }
    }

    @Test fun retainedBeginAndGrantCannotBorrowTheNewOptionsLeaseAfterAba() {
        val owner = authority(); val first = PortableAccountExportSession(owner, "entry/options-A").apply { activate() }
        val begin = { first.issue() }; val waitingForPicker = begin()
        first.close()
        PortableAccountExportSession(owner, "entry/options-B").apply { activate(); close() }
        val again = PortableAccountExportSession(owner, "entry/options-A").apply { activate() }
        first.activate(); expired { begin() }; expired { waitingForPicker.checkCurrent() }
        again.issue().checkCurrent()
    }

    @Test fun closingOnlyTheCapturedSessionDoesNotRetireALaterEqualSelection() {
        val owner = authority(); val old = PortableAccountExportSession(owner, "same").apply { activate() }
        val replacement = PortableAccountExportSession(owner, "same").apply { activate() }
        val valid = replacement.issue(); old.close(); old.close()
        valid.checkCurrent(); expired { old.issue() }; replacement.check()
    }

    @Test fun sameClientAndKeyNewAuthorityCannotReviveAnOlderGrant() {
        val connection = NotesFixture.connection { error("No HTTP") }
        val firstOwner = PortableTextAccountAuthority(connection)
        val first = PortableAccountExportSession(firstOwner, "entry").apply { activate() }
        val oldCallback = { first.issue() }; val old = oldCallback()
        firstOwner.close()
        val newOwner = PortableTextAccountAuthority(connection)
        val replacement = PortableAccountExportSession(newOwner, "entry").apply { activate() }.issue()
        assertSame(old.authority.connection.client, replacement.authority.connection.client)
        assertNotSame(old.authority, replacement.authority)
        expired { old.checkCurrent() }; expired { oldCallback() }; replacement.checkCurrent()
    }

    @Test fun accountAtoBtoAUsesNewTerminalOwnerRatherThanConnectionEquality() {
        val a = authority(); val oldSession = PortableAccountExportSession(a, "entry").apply { activate() }; val old = oldSession.issue()
        a.close()
        val b = PortableTextAccountAuthority(NotesFixture.connection("other") { error("No HTTP") })
        PortableAccountExportSession(b, "entry").apply { activate() }.issue().checkCurrent(); b.close()
        val again = PortableTextAccountAuthority(a.connection)
        PortableAccountExportSession(again, "entry").apply { activate() }.issue().checkCurrent()
        expired { old.checkCurrent() }; expired { oldSession.issue() }
    }

    @Test fun replacedClientWithSameAccountDoesNotBecomeOldCallbacksAuthority() {
        val firstOwner = authority(); val first = PortableAccountExportSession(firstOwner, "entry").apply { activate() }.issue()
        firstOwner.close(); val next = authority()
        assertEquals(firstOwner.connection.accountKey, next.connection.accountKey)
        assertNotSame(firstOwner.connection.client, next.connection.client)
        val second = PortableAccountExportSession(next, "entry").apply { activate() }.issue()
        expired { first.checkCurrent() }; second.checkCurrent()
    }

    @Test fun absentAndAlreadyRetiredAuthorityFailClosedWithoutCrashingCommittedEffect() {
        val absent = PortableAccountExportSession(null, "entry")
        absent.activate(); expired { absent.issue() }; expired { absent.check() }
        val owner = authority(); owner.close()
        val lateRender = PortableAccountExportSession(owner, "entry")
        assertTrue(runCatching { lateRender.activate() }.isSuccess)
        expired { lateRender.issue() }; lateRender.close(); lateRender.activate(); expired { lateRender.issue() }
    }

    @Test fun grantGuardIsUsableInsideTheAlreadyHeldTextCommitGate() {
        val session = PortableAccountExportSession(authority(), "entry").apply { activate() }
        val grant = session.issue()
        grant.ticket.withCurrent { connection ->
            assertSame(grant.authority.connection.client, connection.client)
            grant.checkCurrent()
        }
        session.close(); expired { grant.checkCurrent() }
    }

    @Test fun retainedOptionCallbackCannotMutateSharedStateAfterANewerChoiceCommits() {
        val owner = authority(); val old = PortableAccountExportSession(owner, "options-A").apply { activate() }
        val choice = PortableAccountExportLease().apply { activate() }; var changes = 0
        val callback = { retirePortableExportChoice(choice, old) { changes++ } }
        callback(); assertEquals(1, changes)
        val next = PortableAccountExportSession(owner, "options-B").apply { activate() }.issue()
        callback(); assertEquals(1, changes); next.checkCurrent()
        expired { old.issue() }
    }

    @Test fun offlineDeselectionConsumesUiChoiceWithoutIssuingAccountAuthority() {
        val offline = PortableAccountExportSession(null, "offline").apply { activate() }
        val choice = PortableAccountExportLease().apply { activate() }; var selected = true
        retirePortableExportChoice(choice, offline) { selected = false }
        assertFalse(selected); expired { offline.issue() }
        retirePortableExportChoice(choice, offline) { selected = true }
        assertFalse(selected)
    }

    @Test fun uncommittedAndDisposedChoiceCallbacksCannotChangeOptions() {
        val session = PortableAccountExportSession(authority(), "entry")
        val choice = PortableAccountExportLease(); var changed = false
        retirePortableExportChoice(choice, session) { changed = true }; assertFalse(changed)
        choice.close(); session.close(); choice.activate(); session.activate()
        retirePortableExportChoice(choice, session) { changed = true }; assertFalse(changed)
    }

    @Test fun duplicateValueCallbackDoesNotConsumeTheStillCurrentChoiceOrGrant() {
        val session = PortableAccountExportSession(authority(), "entry").apply { activate() }
        val choice = PortableAccountExportLease().apply { activate() }; val grant = session.issue()
        var changed = false
        retirePortableExportChoice(choice, session, changed = false) { changed = true }
        assertFalse(changed); choice.check(); grant.checkCurrent()
        retirePortableExportChoice(choice, session) { changed = true }
        assertTrue(changed); expired { grant.checkCurrent() }
    }
}
