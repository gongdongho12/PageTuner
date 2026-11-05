package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.NotesFixture
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

/** JVM concurrency and injected streams; not a physical Android SAF/provider execution. */
class PortableExportStagingConcurrencyTest {
    private fun account() = NotesFixture.connection { error("No HTTP is allowed") }
    private fun await(latch: CountDownLatch) = assertTrue("Expected latch signal", latch.await(10, TimeUnit.SECONDS))
    private fun denied(result: Result<*>) {
        val error = result.exceptionOrNull()
        assertTrue("Expected an expired or sealed export, received $error", error is IllegalStateException)
    }
    private fun pool(block: (ExecutorService) -> Unit) {
        val executor = Executors.newFixedThreadPool(3)
        try { block(executor) }
        finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)) }
    }
    private fun save(tickets: PortableExportTickets, request: PortableExportRequest): ByteArray {
        val output = ByteArrayOutputStream()
        assertEquals(request, tickets.write(request.id, { null }) { output })
        return output.toByteArray()
    }

    @Test fun cancelCompletesWhilePreparationGuardIsHeldAndPreventsLatePublication() = pool { executor ->
        val tickets = PortableExportTickets(); val request = tickets.begin("held.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(request, byteArrayOf(1), null) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            executor.submit { tickets.cancel(request.id) }.get(10, TimeUnit.SECONDS)
            release.countDown(); denied(preparing.get(10, TimeUnit.SECONDS))
            assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { null }) { fail("Cancelled open"); null } }
        } finally { release.countDown() }
    }

    @Test fun replacementCanPrepareWhileOldGuardIsHeldAndOldPublicationCannotOverwriteIt() = pool { executor ->
        val tickets = PortableExportTickets(); val old = tickets.begin("old.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(old, byteArrayOf(1), null) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            val next = executor.submit<PortableExportRequest> {
                tickets.begin("new.zip").also { tickets.prepare(it, byteArrayOf(2), null) }
            }.get(10, TimeUnit.SECONDS)
            release.countDown(); denied(preparing.get(10, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(2), save(tickets, next))
        } finally { release.countDown() }
    }

    @Test fun logoutReconnectAbaDuringHeldGuardCannotReviveAccountPreparation() = pool { executor ->
        val tickets = PortableExportTickets(); val account = account(); val request = tickets.begin("account.zip", account)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(request, byteArrayOf(1), account) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            executor.submit { tickets.connect(null); tickets.connect(account) }.get(10, TimeUnit.SECONDS)
            release.countDown(); denied(preparing.get(10, TimeUnit.SECONDS))
            assertThrows(PortableExportExpired::class.java) { tickets.checkBeforeWrite(request.id) { account } }
        } finally { release.countDown() }
    }

    @Test fun replacementClientDuringHeldGuardInvalidatesOnlyCapturedAccountRequest() = pool { executor ->
        val tickets = PortableExportTickets(); val account = account(); val replacement = account()
        assertEquals(account.accountKey, replacement.accountKey); assertNotSame(account.client, replacement.client)
        val request = tickets.begin("account.zip", account)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(request, byteArrayOf(1), account) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            val next = executor.submit<PortableExportRequest> {
                tickets.connect(replacement)
                tickets.begin("device.zip").also { tickets.prepare(it, byteArrayOf(3), null) }
            }.get(10, TimeUnit.SECONDS)
            release.countDown(); denied(preparing.get(10, TimeUnit.SECONDS))
            assertArrayEquals(byteArrayOf(3), save(tickets, next))
        } finally { release.countDown() }
    }

    @Test fun reentrantGuardReplacementRejectsOldPublicationWithoutRetiringNewRequest() {
        val tickets = PortableExportTickets(); val old = tickets.begin("old.zip")
        lateinit var next: PortableExportRequest
        val result = runCatching {
            tickets.prepare(old, byteArrayOf(1), null) {
                tickets.cancel(old.id)
                next = tickets.begin("new.zip")
                tickets.prepare(next, byteArrayOf(2), null)
            }
        }
        denied(result); assertArrayEquals(byteArrayOf(2), save(tickets, next))
    }

    @Test fun aLateGuardFailureDoesNotClearAnAlreadyPreparedReplacement() = pool { executor ->
        val tickets = PortableExportTickets(); val old = tickets.begin("old.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(old, byteArrayOf(1), null) { entered.countDown(); await(release); throw IOException("fixture failure") }
        } }
        try {
            await(entered)
            val next = executor.submit<PortableExportRequest> {
                tickets.begin("new.zip").also { tickets.prepare(it, byteArrayOf(4), null) }
            }.get(10, TimeUnit.SECONDS)
            release.countDown()
            assertTrue(preparing.get(10, TimeUnit.SECONDS).exceptionOrNull() is IOException)
            assertArrayEquals(byteArrayOf(4), save(tickets, next))
        } finally { release.countDown() }
    }

    @Test fun concurrentPrepareHasOneReservationAndLosingGuardNeverRuns() = pool { executor ->
        val tickets = PortableExportTickets(); val request = tickets.begin("one.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val losingGuards = AtomicInteger()
        val first = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(request, byteArrayOf(5), null) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            val second = executor.submit<Result<Unit>> { runCatching {
                tickets.prepare(request, byteArrayOf(9), null) { losingGuards.incrementAndGet() }
            } }.get(10, TimeUnit.SECONDS)
            denied(second); assertEquals(0, losingGuards.get())
            release.countDown(); first.get(10, TimeUnit.SECONDS).getOrThrow()
            assertArrayEquals(byteArrayOf(5), save(tickets, request))
        } finally { release.countDown() }
    }

    @Test fun readyRequestCannotBePreparedAgainOrReplaceItsRetainedGuardAndBytes() {
        val tickets = PortableExportTickets(); val request = tickets.begin("sealed.zip")
        var originalGuards = 0; var replacementGuards = 0
        tickets.prepare(request, byteArrayOf(6), null) { originalGuards++ }
        denied(runCatching { tickets.prepare(request, byteArrayOf(9), null) { replacementGuards++ } })
        assertEquals(0, replacementGuards)
        assertArrayEquals(byteArrayOf(6), save(tickets, request)); assertTrue(originalGuards >= 3)
    }

    @Test fun providerOpenCannotBeUsedToReplaceTheWritersCapturedPayload() = pool { executor ->
        val tickets = PortableExportTickets(); val request = tickets.begin("writing.zip")
        tickets.prepare(request, byteArrayOf(7), null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val output = ByteArrayOutputStream()
        val writing = executor.submit<Result<PortableExportRequest>> { runCatching {
            tickets.write(request.id, { null }) { entered.countDown(); await(release); output }
        } }
        try {
            await(entered)
            denied(executor.submit<Result<Unit>> { runCatching { tickets.prepare(request, byteArrayOf(9), null) } }
                .get(10, TimeUnit.SECONDS))
            release.countDown(); assertEquals(request, writing.get(10, TimeUnit.SECONDS).getOrThrow())
            assertArrayEquals(byteArrayOf(7), output.toByteArray())
        } finally { release.countDown() }
    }

    @Test fun preparationCopiesInputBeforeCallingAnExternalGuard() {
        val tickets = PortableExportTickets(); val request = tickets.begin("copy.zip"); val input = byteArrayOf(8)
        tickets.prepare(request, input, null) { input[0] = 99 }
        assertArrayEquals(byteArrayOf(8), save(tickets, request))
    }

    @Test fun preparingRequestIsNeverAdvertisedAsReadyAndCannotOpenADestination() = pool { executor ->
        val tickets = PortableExportTickets(); val request = tickets.begin("not-ready.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val preparing = executor.submit<Result<Unit>> { runCatching {
            tickets.prepare(request, byteArrayOf(1), null) { entered.countDown(); await(release) }
        } }
        try {
            await(entered)
            executor.submit {
                assertThrows(PortableExportExpired::class.java) { tickets.checkBeforeWrite(request.id) { null } }
                assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { null }) { fail("Premature destination"); null } }
            }.get(10, TimeUnit.SECONDS)
            release.countDown(); preparing.get(10, TimeUnit.SECONDS).getOrThrow()
            assertArrayEquals(byteArrayOf(1), save(tickets, request))
        } finally { release.countDown() }
    }

    @Test fun failedGuardConsumesThatPreparationAndCannotBeRetriedWithAnEmptyGuard() {
        val tickets = PortableExportTickets(); val request = tickets.begin("failed.zip")
        assertThrows(IOException::class.java) { tickets.prepare(request, byteArrayOf(1), null) { throw IOException("fixture") } }
        denied(runCatching { tickets.prepare(request, byteArrayOf(2), null) })
        assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { null }) { fail("Failed guard open"); null } }
        val next = tickets.begin("retry-is-new.zip"); tickets.prepare(next, byteArrayOf(3), null)
        assertArrayEquals(byteArrayOf(3), save(tickets, next))
    }

    @Test fun cancellationFromPostOpenGuardWritesNothingAndClosesOpenedStream() {
        val tickets = PortableExportTickets(); val request = tickets.begin("cancel-at-open.zip")
        var cancelFromGuard = false
        tickets.prepare(request, byteArrayOf(1), null) { if (cancelFromGuard) tickets.cancel(request.id) }
        var closed = false
        val output = object : ByteArrayOutputStream() { override fun close() { closed = true; super.close() } }
        assertThrows(PortableExportExpired::class.java) {
            tickets.write(request.id, { null }) { cancelFromGuard = true; output }
        }
        assertEquals(0, output.size()); assertTrue(closed)
    }

    @Test fun oldWriterFailureFinallyCannotCancelANewPreparedRequest() {
        val tickets = PortableExportTickets(); val old = tickets.begin("old.zip"); tickets.prepare(old, byteArrayOf(1), null)
        lateinit var next: PortableExportRequest
        assertThrows(IOException::class.java) {
            tickets.write(old.id, { null }) {
                next = tickets.begin("new.zip"); tickets.prepare(next, byteArrayOf(2), null)
                throw IOException("Provider failed after replacement")
            }
        }
        assertArrayEquals(byteArrayOf(2), save(tickets, next))
    }

    @Test fun aSecondWriterCannotOpenTheSameOneShotRequestDuringProviderWait() = pool { executor ->
        val tickets = PortableExportTickets(); val request = tickets.begin("one-shot.zip"); tickets.prepare(request, byteArrayOf(1), null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val opens = AtomicInteger()
        val writing = executor.submit<Result<PortableExportRequest>> { runCatching {
            tickets.write(request.id, { null }) { opens.incrementAndGet(); entered.countDown(); await(release); ByteArrayOutputStream() }
        } }
        try {
            await(entered)
            denied(executor.submit<Result<PortableExportRequest>> { runCatching {
                tickets.write(request.id, { null }) { opens.incrementAndGet(); ByteArrayOutputStream() }
            } }.get(10, TimeUnit.SECONDS))
            assertEquals(1, opens.get()); release.countDown(); writing.get(10, TimeUnit.SECONDS).getOrThrow()
        } finally { release.countDown() }
    }

    @Test fun externalCurrentGuardOpenAndStreamCallbacksRunWithoutTheTicketMonitor() {
        val tickets = PortableExportTickets(); val request = tickets.begin("monitor.zip")
        var guardCalls = 0; var currentCalls = 0; var openCalls = 0; var outputCalls = 0
        tickets.prepare(request, byteArrayOf(1), null) { assertFalse(Thread.holdsLock(tickets)); guardCalls++ }
        tickets.checkBeforeWrite(request.id) { assertFalse(Thread.holdsLock(tickets)); currentCalls++; null }
        val output = object : OutputStream() {
            override fun write(value: Int) { assertFalse(Thread.holdsLock(tickets)); outputCalls++ }
            override fun close() { assertFalse(Thread.holdsLock(tickets)) }
        }
        tickets.write(request.id, { assertFalse(Thread.holdsLock(tickets)); currentCalls++; null }) {
            assertFalse(Thread.holdsLock(tickets)); openCalls++; output
        }
        assertTrue(guardCalls >= 4); assertTrue(currentCalls >= 6); assertEquals(1, openCalls); assertEquals(1, outputCalls)
    }

    @Test fun providerFailureConsumesTheCapturedRequestWithoutAnySecondOpen() {
        val tickets = PortableExportTickets(); val request = tickets.begin("provider-failure.zip"); tickets.prepare(request, byteArrayOf(1), null)
        assertThrows(IOException::class.java) { tickets.write(request.id, { null }) { throw IOException("fixture") } }
        assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { null }) { fail("Second open"); null } }
        val next = tickets.begin("next.zip"); tickets.prepare(next, byteArrayOf(2), null)
        assertArrayEquals(byteArrayOf(2), save(tickets, next))
    }
}