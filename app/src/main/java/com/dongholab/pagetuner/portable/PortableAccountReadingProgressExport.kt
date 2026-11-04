package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class PreparedAccountReadingProgress(
    val document: ExchangeDocument,
    val snapshot: ReadingProgressSnapshot,
    val source: PortableAccountExportSource? = null,
    val checkBeforeWrite: () -> Unit,
)

/**
 * One export operation, authorized by a captured real text account/selection ticket. No normal
 * sync actor or mutation API is opened. The returned guard performs blocking storage IO; a future
 * SAF consumer must call it on its storage dispatcher and also check its own active write job.
 */
internal class PortableAccountReadingProgressExport(
    private val entry: PortableLibraryEntry,
    private val authority: PortableTextAccountAuthority,
    private val ticket: PortableTextOperationTicket,
    private val currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
    private val bindings: PortableTextBindingAccess,
    private val storage: ServerReadingProgressStore,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { connection, record, identity ->
        connection.client.verifyPortableIdentity(record, identity)
    },
    private val query: suspend (ServerReadingConnection, String, String) -> ServerReadingProgress = { connection, kind, record ->
        connection.client.readingProgress(kind, record)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Captured(val entry: PortableLibraryEntry, val binding: PortableServerBindingSnapshot)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    @Volatile private var reader: FreshServerReadingProgressReader? = null

    /** Non-blocking retirement. The caller owns, and separately retires, the selection ticket. */
    fun close() { closed.set(true); reader?.close() }

    /** Single use prevents new observations from being split between independently live readers. */
    suspend fun prepare(): PreparedAccountReadingProgress {
        check(started.compareAndSet(false, true)) { "Prepare a new export operation." }
        try {
            return withContext(io) {
                checkpoint()
                ticket.withCurrent(::requireSameConnection)
                // Production currentDocument waits for pending reader saves and rereads actual ZIP bytes.
                val selected = detachedTextBindingEntry(entry.copy(document = currentDocument(entry)))
                checkpoint()
                val identity = requireNotNull(DocumentIdentityJson.fromDocument(selected.document))
                // Refuse unsupported/corrupt owned metadata before requesting any account data.
                ReadingProgressSnapshotJson.fromDocument(selected.document)
                LibraryOrganizationSnapshotJson.fromDocument(selected.document)
                BookGlossarySnapshotsJson.fromDocument(selected.document)
                val snapshot = ticket.withCurrent { connection ->
                    requireSameConnection(connection)
                    bindings.capture(connection.accountKey, selected)
                }
                checkpoint()
                val linked = requireNotNull(snapshot.binding) { "Connect this document to server records first." }
                require(linked.accountKey == authority.connection.accountKey && linked.localKey == selected.key &&
                    linked.identity == identity && snapshot.nonce == linked.nonce)
                val target = ServerReadingTarget(linked.accountKey, identity.kind.name, linked.recordId)
                ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
                val captured = Captured(selected, snapshot.copy(binding = linked.copy(identity = linked.identity.copy())))
                canonical(captured)
                try { verify(authority.connection, target.recordId, identity) }
                catch (error: Exception) { checkpoint(); throw error }
                canonical(captured)
                val observer = FreshServerReadingProgressReader(storage, target, authority.connection, ::current,
                    query = { query(authority.connection, target.kind, target.recordId) }, io = io)
                reader = observer
                checkpoint()
                val fresh = observer.read()
                canonical(captured)
                observer.checkReady(fresh)
                canonical(captured)
                val value = ReadingProgressSnapshot(identity = identity,
                    presence = if (fresh.version == 0L) ReadingProgressSnapshotPresence.ABSENT else ReadingProgressSnapshotPresence.PRESENT,
                    anchor = fresh.anchor?.let { ReadingProgressSnapshotAnchor(it.paragraphId, it.characterOffset) })
                // Typed attachment validates actual paragraph membership, exact UTF-16 boundaries and full identity.
                val updated = ReadingProgressSnapshotJson.withSnapshot(selected.document, value)
                val output = detachedTextBindingEntry(selected.copy(document = updated)).document
                val source = detachedAccountExportSource(selected, authority.connection, linked)
                fun guard() {
                    try { verified(captured) { observer.checkBeforeWrite(fresh) } }
                    catch (error: Exception) { close(); throw error }
                }
                // After all async journal work, reread canonical data and hold library/binding through
                // the final synchronous journal check. A later SAF call repeats this entire guard.
                canonical(captured) { observer.checkBeforeWrite(fresh) }
                checkpoint()
                PreparedAccountReadingProgress(output,
                    ReadingProgressSnapshotJson.decode(ReadingProgressSnapshotJson.encode(value)), source, ::guard)
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private suspend fun canonical(captured: Captured, journal: () -> Unit = {}) {
        checkpoint()
        val latest = detachedTextBindingEntry(captured.entry.copy(document = currentDocument(captured.entry)))
        require(latest.document == captured.entry.document) { "The selected document changed. Export again." }
        checkpoint()
        withContext(io) { verified(captured, journal) }
        checkpoint()
    }

    /** gate -> account -> library -> exact v2 binding -> journal; no network awaits or earlier locks. */
    private fun verified(captured: Captured, journal: () -> Unit) {
        current()
        ticket.withCurrent { connection ->
            requireSameConnection(connection)
            bindings.verified(connection.accountKey, captured.entry, captured.binding, ::current, journal)
        }
        current()
    }

    /** Memory-only: this is also called from inside binding/journal verification scopes. */
    private fun current() {
        check(!closed.get()) { "This reading progress export expired." }
        authority.check(ticket)
        check(!closed.get()) { "This reading progress export expired." }
    }

    private suspend fun checkpoint() {
        currentCoroutineContext().ensureActive()
        current()
    }

    private fun requireSameConnection(value: ServerReadingConnection) {
        require(value.accountKey == authority.connection.accountKey && value.client === authority.connection.client) {
            "The text account changed. Export again."
        }
    }
}
