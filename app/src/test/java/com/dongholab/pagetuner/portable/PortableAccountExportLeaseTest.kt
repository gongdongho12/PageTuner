package com.dongholab.pagetuner.portable

import org.junit.Assert.assertThrows
import org.junit.Test

class PortableAccountExportLeaseTest {
    @Test fun uncommittedAndClosedSelectionsCannotIssueGuards() {
        val lease = PortableAccountExportLease()
        assertThrows(PortableExportExpired::class.java) { lease.issue() }
        lease.activate()
        val guard = lease.issue()
        guard()
        lease.close()
        assertThrows(PortableExportExpired::class.java) { guard() }
        assertThrows(PortableExportExpired::class.java) { lease.issue() }
    }

    @Test fun accountOrOptionsAbaNeverRevivesAnOldCallback() {
        val first = PortableAccountExportLease().apply { activate() }
        val oldCallback = { first.issue() }
        val waitingForSaf = oldCallback()
        first.close()
        val second = PortableAccountExportLease().apply { activate() }
        second.close()
        val sameOptionsAgain = PortableAccountExportLease().apply { activate() }
        first.activate()
        assertThrows(PortableExportExpired::class.java) { oldCallback() }
        assertThrows(PortableExportExpired::class.java) { waitingForSaf() }
        sameOptionsAgain.issue()()
    }

    @Test fun effectCleanupBeforeActivationIsTerminal() {
        val abandoned = PortableAccountExportLease()
        abandoned.close()
        abandoned.activate()
        assertThrows(PortableExportExpired::class.java) { abandoned.issue() }
    }
}
