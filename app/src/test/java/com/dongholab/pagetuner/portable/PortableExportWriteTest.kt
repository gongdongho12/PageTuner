package com.dongholab.pagetuner.portable

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual WRITE coroutine cancellation with injected provider streams, not a physical SAF execution. */
class PortableExportWriteTest {
    private class Output : ByteArrayOutputStream() {
        val closed = AtomicBoolean(false)
        override fun close() { closed.set(true); super.close() }
    }
    private fun await(latch: CountDownLatch) = assertTrue(latch.await(10, TimeUnit.SECONDS))

    @Test fun completedPreparationCoroutineDoesNotBecomeTheLaterWritersAuthority() = runBlocking<Unit> {
        val tickets = PortableExportTickets(); val request = tickets.begin("ready.zip")
        val preparing = launch { tickets.prepare(request, byteArrayOf(1, 2), null) }
        preparing.join(); assertTrue(preparing.isCompleted)
        val output = Output()
        assertEquals(request, withContext(Dispatchers.IO) { writePortableExport(tickets, request.id, { null }) { output } })
        assertArrayEquals(byteArrayOf(1, 2), output.toByteArray()); assertTrue(output.closed.get())
    }

    @Test fun cancellingWriteWhileCanonicalGuardBlocksPreventsProviderOpen() = runBlocking<Unit> {
        val tickets = PortableExportTickets(); val request = tickets.begin("guard.zip")
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val opened = AtomicBoolean(false)
        val hold = AtomicBoolean(false)
        tickets.prepare(request, byteArrayOf(3), null) { if (hold.get()) { entered.countDown(); await(release) } }
        hold.set(true)
        val writing = launch(Dispatchers.IO) { writePortableExport(tickets, request.id, { null }) { opened.set(true); Output() } }
        try { await(entered); writing.cancel(); release.countDown(); writing.join() }
        finally { release.countDown(); writing.cancelAndJoin() }
        assertTrue(writing.isCancelled); assertFalse(opened.get())
        assertThrows(PortableExportExpired::class.java) { tickets.checkBeforeWrite(request.id) { null } }
        Unit
    }

    @Test fun cancellingActualWriteDuringBlockingProviderOpenClosesWithoutWritingBytes() = runBlocking<Unit> {
        val tickets = PortableExportTickets(); val request = tickets.begin("provider.zip"); tickets.prepare(request, byteArrayOf(4), null)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val output = Output()
        val failure = AtomicReference<Throwable?>()
        val writing = launch(Dispatchers.IO) {
            try { writePortableExport(tickets, request.id, { null }) { entered.countDown(); await(release); output } }
            catch (error: Throwable) { failure.set(error); throw error }
        }
        try { await(entered); writing.cancel(); release.countDown(); writing.join() }
        finally { release.countDown(); writing.cancelAndJoin() }
        assertTrue(failure.get() is CancellationException); assertEquals(0, output.size()); assertTrue(output.closed.get())
        assertThrows(PortableExportExpired::class.java) { tickets.checkBeforeWrite(request.id) { null } }
        Unit
    }

    @Test fun currentCallbackCancellationAfterOpenCannotReachOutputWrite() = runBlocking<Unit> {
        val tickets = PortableExportTickets(); val request = tickets.begin("callback.zip"); tickets.prepare(request, byteArrayOf(5), null)
        val output = Output(); val opened = AtomicBoolean(false)
        val writing = launch(Dispatchers.IO) {
            val writeJob = currentCoroutineContext()[Job]!!
            writePortableExport(tickets, request.id, { if (opened.get()) writeJob.cancel(); null }) { opened.set(true); output }
        }
        writing.join(); assertTrue(writing.isCancelled)
        assertEquals(0, output.size()); assertTrue(output.closed.get())
    }

    @Test fun cancellationInsideCompletedStreamWriteDoesNotAddAFalseRollbackCheck() = runBlocking<Unit> {
        val tickets = PortableExportTickets(); val request = tickets.begin("saved.zip"); tickets.prepare(request, byteArrayOf(6), null)
        val returned = AtomicReference<PortableExportRequest?>(); val wrote = AtomicBoolean(false)
        val writing = launch(Dispatchers.IO) {
            val writeJob = currentCoroutineContext()[Job]!!
            val output = object : ByteArrayOutputStream() {
                override fun write(value: ByteArray) { super.write(value); wrote.set(true); writeJob.cancel() }
            }
            returned.set(writePortableExport(tickets, request.id, { null }) { output })
        }
        writing.join(); assertTrue(writing.isCancelled); assertTrue(wrote.get()); assertEquals(request, returned.get())
    }

    @Test fun savedRequestIdWithoutMemoryPayloadCannotOpenAfterRecreation() = runBlocking<Unit> {
        val old = PortableExportTickets(); val request = old.begin("old.zip"); old.prepare(request, byteArrayOf(7), null)
        val recreated = PortableExportTickets(); val opened = AtomicBoolean(false)
        val result = withContext(Dispatchers.IO) { runCatching { writePortableExport(recreated, request.id, { null }) { opened.set(true); Output() } } }
        assertTrue(result.exceptionOrNull() is PortableExportExpired); assertFalse(opened.get())
    }
}
