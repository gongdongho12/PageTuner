package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Explicit lifetime-owner behavior only; AccountViewModel/Compose lifecycle wiring is not exercised. */
class PortableTextOperationLeaseTest {
    private fun connection(key: String = "a".repeat(64)) = ServerReadingConnection(key,
        HttpTranslationStore("https://reader.example", TranslationStoreBasicAuth("reader", "password"),
            TranslationStoreHttpTransport { error("Lifetime primitives must not make HTTP requests") }))

    private class Fixture(val connection: ServerReadingConnection) {
        val account = PortableTextAccountLifetime(connection)
        val selection = PortableTextOperationLease(account, connection, "copy:0")
        fun activate(): Fixture { account.activate(); selection.activate(); return this }
        fun ticket() = selection.issue(connection, "copy:0")
    }

    @Test fun uncommittedAccountAndSelectionCannotIssueOrEnter() {
        val f = Fixture(connection())
        assertThrows(PortableTextOperationExpired::class.java) { f.ticket() }
        assertThrows(PortableTextOperationExpired::class.java) { f.selection.activate() }
        f.account.activate()
        assertThrows(PortableTextOperationExpired::class.java) { f.ticket() }
        f.selection.activate()
        val ticket = f.ticket()
        ticket.check()
        assertSame(f.connection, ticket.withCurrent { it })
    }

    @Test fun cleanupBeforeActivationPermanentlyRetiresAccountAndSelection() {
        val abandonedAccount = Fixture(connection())
        abandonedAccount.account.close(); abandonedAccount.account.activate()
        assertThrows(PortableTextOperationExpired::class.java) { abandonedAccount.selection.activate() }
        assertThrows(PortableTextOperationExpired::class.java) { abandonedAccount.ticket() }

        val abandonedSelection = Fixture(connection())
        abandonedSelection.selection.close(); abandonedSelection.account.activate(); abandonedSelection.selection.activate()
        assertThrows(PortableTextOperationExpired::class.java) { abandonedSelection.ticket() }
    }

    @Test fun repeatedActivationCannotReviveClosedSelectionsOrAccounts() {
        val f = Fixture(connection()).activate(); val ticket = f.ticket()
        f.selection.close(); f.selection.activate(); f.account.activate()
        assertThrows(PortableTextOperationExpired::class.java) { ticket.check() }
        assertThrows(PortableTextOperationExpired::class.java) { f.ticket() }

        val g = Fixture(connection()).activate(); val accountTicket = g.ticket()
        g.account.close(); g.account.activate(); g.selection.activate()
        assertThrows(PortableTextOperationExpired::class.java) { accountTicket.check() }
        assertThrows(PortableTextOperationExpired::class.java) { g.ticket() }
    }

    @Test fun expectedAccountClientAndExactSelectionMustMatch() {
        val f = Fixture(connection()).activate()
        val otherKeyWithSameClient = ServerReadingConnection("b".repeat(64), f.connection.client)
        assertThrows(PortableTextOperationExpired::class.java) { f.selection.issue(otherKeyWithSameClient, "copy:0") }
        assertThrows(PortableTextOperationExpired::class.java) { f.selection.issue(connection(), "copy:0") }
        assertThrows(PortableTextOperationExpired::class.java) { f.selection.issue(f.connection, "copy:1") }
        assertThrows(PortableTextOperationExpired::class.java) { f.selection.issue(f.connection, " copy:0 ") }
        f.ticket().check()
    }

    @Test fun selectionAbaCannotMakeRetainedIssuerBorrowCurrentSelection() {
        val f = Fixture(connection()).activate()
        val retainedIssuer = { f.selection.issue(f.connection, "copy:0") }
        val old = retainedIssuer()
        f.selection.close()
        val other = PortableTextOperationLease(f.account, f.connection, "copy:1").apply { activate() }
        other.close()
        val sameKeyAgain = PortableTextOperationLease(f.account, f.connection, "copy:0").apply { activate() }
        assertThrows(PortableTextOperationExpired::class.java) { retainedIssuer() }
        assertThrows(PortableTextOperationExpired::class.java) { old.withCurrent { error("Old epoch entered") } }
        sameKeyAgain.issue(f.connection, "copy:0").check()
    }

    @Test fun accountAbaWithSameClientCannotReviveOldSelectionOrIssuer() {
        val sharedConnection = connection()
        val first = Fixture(sharedConnection).activate()
        val retainedIssuer = { first.ticket() }; val old = retainedIssuer()
        first.account.close()
        val second = Fixture(ServerReadingConnection("b".repeat(64), sharedConnection.client)).activate()
        second.account.close()
        val sameAccountAgain = Fixture(sharedConnection).activate()
        assertThrows(PortableTextOperationExpired::class.java) { retainedIssuer() }
        assertThrows(PortableTextOperationExpired::class.java) { old.check() }
        sameAccountAgain.ticket().check()
    }

    @Test fun closeBeforeCommitEntryRejectsWithoutExecutingCallback() {
        for (closeAccount in listOf(false, true)) {
            val f = Fixture(connection()).activate(); val ticket = f.ticket(); var calls = 0
            if (closeAccount) f.account.close() else f.selection.close()
            assertThrows(PortableTextOperationExpired::class.java) { ticket.withCurrent { calls++; true } }
            assertEquals(0, calls)
        }
    }

    @Test fun accountAndSelectionInvalidationWaitForActualCommitCallback() {
        val f = Fixture(connection()).activate(); val ticket = f.ticket()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val accountClosing = CountDownLatch(1); val selectionClosing = CountDownLatch(1)
        val committed = AtomicBoolean(false)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val result = executor.submit<String> { ticket.withCurrent {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                ticket.check(); committed.set(true); "accepted"
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val accountClose = executor.submit { accountClosing.countDown(); f.account.close() }
            val selectionClose = executor.submit { selectionClosing.countDown(); f.selection.close() }
            assertTrue(accountClosing.await(5, TimeUnit.SECONDS)); assertTrue(selectionClosing.await(5, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { accountClose.get(100, TimeUnit.MILLISECONDS) }
            assertThrows(TimeoutException::class.java) { selectionClose.get(100, TimeUnit.MILLISECONDS) }
            assertFalse(committed.get())
            release.countDown()
            assertEquals("accepted", result.get(5, TimeUnit.SECONDS))
            accountClose.get(5, TimeUnit.SECONDS); selectionClose.get(5, TimeUnit.SECONDS)
            assertTrue(committed.get())
            assertThrows(PortableTextOperationExpired::class.java) { ticket.check() }
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun finalGuardIsMemoryOnlyWhileAnotherThreadOwnsBothLifetimeMonitors() {
        val f = Fixture(connection()).activate(); val ticket = f.ticket()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val owner = executor.submit { ticket.withCurrent {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            // A guard which reacquired gate/account would block here until release and fail this bound.
            executor.submit { ticket.check() }.get(1, TimeUnit.SECONDS)
            release.countDown(); owner.get(5, TimeUnit.SECONDS)
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun reentrantInvalidationBeforeWriteIsCaughtByLastMemoryGuard() {
        for (closeAccount in listOf(false, true)) {
            val f = Fixture(connection()).activate(); val ticket = f.ticket(); var committed = false
            assertThrows(PortableTextOperationExpired::class.java) { ticket.withCurrent {
                if (closeAccount) f.account.close() else f.selection.close()
                ticket.check(); committed = true
            } }
            assertFalse(committed)
        }
    }

    @Test fun invalidationAfterDurableCallbackWorkDoesNotReportRollback() {
        for (closeAccount in listOf(false, true)) {
            val f = Fixture(connection()).activate(); val ticket = f.ticket(); var committed = false
            val accepted = ticket.withCurrent {
                ticket.check(); committed = true
                if (closeAccount) f.account.close() else f.selection.close()
                "accepted"
            }
            assertTrue(committed); assertEquals("accepted", accepted)
            assertThrows(PortableTextOperationExpired::class.java) { ticket.check() }
        }
    }

    @Test fun callbackFailurePropagatesAndReleasesBothMonitors() {
        val f = Fixture(connection()).activate(); val ticket = f.ticket()
        val failure = IllegalStateException("Disk staging failed")
        assertSame(failure, assertThrows(IllegalStateException::class.java) { ticket.withCurrent { throw failure } })
        ticket.check()
        val executor = Executors.newSingleThreadExecutor()
        try { executor.submit { f.account.close(); f.selection.close() }.get(5, TimeUnit.SECONDS) }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
