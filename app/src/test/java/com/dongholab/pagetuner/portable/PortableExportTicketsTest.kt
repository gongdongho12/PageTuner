package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.translation.sync.NotesFixture
import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PortableExportTicketsTest {
    private fun connection() = NotesFixture.connection { error("No HTTP") }

    @Test fun oldPickerAndRepeatedCallbackCannotConsumeNewBytes() {
        val tickets = PortableExportTickets()
        val old = tickets.begin("old.zip"); tickets.prepare(old, byteArrayOf(1), null)
        val current = tickets.begin("new.zip"); tickets.prepare(current, byteArrayOf(2), null)
        var opened = false
        assertThrows(PortableExportExpired::class.java) { tickets.write(old.id, { null }) { opened = true; ByteArrayOutputStream() } }
        assertFalse(opened)
        tickets.cancel(old.id)
        val output = ByteArrayOutputStream(); tickets.write(current.id, { null }) { output }
        assertArrayEquals(byteArrayOf(2), output.toByteArray())
        assertThrows(PortableExportExpired::class.java) { tickets.write(current.id, { null }) { fail("Duplicate open"); null } }
    }

    @Test fun logoutReconnectAndClientReplacementInvalidateAccountBytesOnly() {
        val tickets = PortableExportTickets(); val connection = connection()
        val request = tickets.begin("account.zip", connection); tickets.prepare(request, byteArrayOf(1), connection)
        tickets.connect(null); tickets.connect(connection)
        assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { connection }) { fail("Stale open"); null } }
        val replacement = tickets.begin("replacement.zip", connection); tickets.prepare(replacement, byteArrayOf(2), connection)
        assertThrows(PortableExportExpired::class.java) { tickets.write(replacement.id, { connection() }) { fail("Client changed"); null } }
        val native = tickets.begin("device.zip"); tickets.prepare(native, byteArrayOf(3), null)
        tickets.connect(connection); tickets.connect(null)
        val output = ByteArrayOutputStream(); tickets.write(native.id, { null }) { output }
        assertArrayEquals(byteArrayOf(3), output.toByteArray())
    }

    @Test fun accountSwitchDuringDestinationOpenWritesNothing() {
        val tickets = PortableExportTickets(); var current: ServerReadingConnection? = connection()
        val request = tickets.begin("account.zip", current); tickets.prepare(request, byteArrayOf(1), current)
        val output = ByteArrayOutputStream()
        assertThrows(PortableExportExpired::class.java) { tickets.write(request.id, { current }) { current = null; output } }
        assertEquals(0, output.size())
    }

    @Test fun preparingCopiesBytesAndCancelledRequestCannotBecomeReadyAgain() {
        val tickets = PortableExportTickets(); val request = tickets.begin("device.zip")
        val bytes = byteArrayOf(4); tickets.prepare(request, bytes, null); bytes[0] = 9
        val output = ByteArrayOutputStream(); tickets.write(request.id, { null }) { output }
        assertArrayEquals(byteArrayOf(4), output.toByteArray())
        val cancelled = tickets.begin("cancelled.zip"); tickets.cancel(cancelled.id)
        assertThrows(PortableExportExpired::class.java) { tickets.prepare(cancelled, byteArrayOf(5), null) }
    }
}
