package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Detached display only. Confirmation accepts its opaque ID, never these editable values. */
internal data class PortableOrganizationComparison(
    val id: String,
    val snapshot: LibraryOrganizationSnapshot,
    val remote: ServerLibraryOrganization,
    val device: DeviceLibraryOrganization?,
)

/** A durable local intent, not an HTTP acknowledgment or a promise of immediate delivery. */
internal data class PortableOrganizationCommit(val target: ServerReadingTarget, val connection: ServerReadingConnection)

internal interface PortableOrganizationAdoptionCommand {
    suspend fun compare(): PortableOrganizationComparison
    suspend fun confirm(id: String): PortableOrganizationCommit
    fun close()
}

/**
 * One committed account/selection's explicit ZIP organization adoption. The caller owns the terminal
 * selection lease and must close it on disposal. No editor or normal synchronization actor is opened.
 */
internal class PortableOrganizationAdoption(
    private val entry: PortableLibraryEntry,
    private val authority: PortableTextAccountAuthority,
    private val ticket: PortableTextOperationTicket,
    private val currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
    private val bindings: PortableTextBindingAccess,
    private val captureBinding: (String, PortableLibraryEntry) -> PortableServerBindingSnapshot,
    private val storage: ServerLibraryOrganizationStore,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { connection, record, identity ->
        connection.client.verifyPortableIdentity(record, identity)
    },
    private val query: suspend (ServerReadingConnection, String, String) -> ServerLibraryOrganization = { connection, kind, record ->
        connection.client.libraryOrganization(kind, record)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PortableOrganizationAdoptionCommand {
    private data class Captured(val entry: PortableLibraryEntry, val binding: PortableServerBindingSnapshot,
        val snapshot: LibraryOrganizationSnapshot, val target: ServerReadingTarget)
    private data class Shown(val id: String, val comparison: LibraryOrganizationAdoptionComparison)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var captured: Captured? = null
    private var command: ServerLibraryOrganizationAdoption? = null
    private var shown: Shown? = null
    private var committed = false

    /** Non-blocking retirement; the selection owner also closes the ticket's outer operation gate. */
    override fun close() { closed.set(true); command?.close() }

    override suspend fun compare(): PortableOrganizationComparison = mutex.withLock {
        current(); check(!committed) { "This organization was already adopted." }
        shown = null
        val selected = capture()
        val actor = command ?: createCommand(selected).also { command = it }
        val comparison = actor.compare()
        current()
        shown = Shown(comparison.id, comparison)
        PortableOrganizationComparison(comparison.id, copySnapshot(selected.snapshot),
            copyRemote(comparison.remote, selected.target), copyRecord(comparison.record, selected.target))
    }

    override suspend fun confirm(id: String): PortableOrganizationCommit = mutex.withLock {
        current(); check(!committed) { "This organization was already adopted." }
        val selected = requireNotNull(captured)
        val privateComparison = requireNotNull(shown?.takeIf { it.id == id }) { "Compare again before adopting." }
        shown = null // Every confirmation is consumed, even a rejection.
        require(selected.snapshot.presence == LibraryOrganizationSnapshotPresence.PRESENT) {
            "An absent snapshot is informational and cannot change the account."
        }
        val value = requireNotNull(selected.snapshot.organization)
        val intended = LibraryOrganization(value.folder, value.tags.toList(), value.favorite)
        val result = PortableOrganizationCommit(selected.target, authority.connection)
        // All authority/canonical checks happen before and inside the durable write, never after it.
        check(requireNotNull(command).adopt(privateComparison.comparison, intended))
        committed = true
        result
    }

    private suspend fun capture(): Captured {
        captured?.let { return it }
        current()
        // The production callback waits for pending reader saves and reads actual ZIP bytes.
        val selected = detachedTextBindingEntry(entry.copy(document = currentDocument(entry)))
        current()
        val identity = requireNotNull(DocumentIdentityJson.fromDocument(selected.document))
        val snapshot = requireNotNull(LibraryOrganizationSnapshotJson.fromDocument(selected.document)) {
            "This ZIP has no account organization snapshot."
        }
        require(snapshot.identity == identity)
        val linked = withContext(io) { captureBinding(authority.connection.accountKey, selected) }
        current()
        val binding = requireNotNull(linked.binding) { "Connect this document to server records first." }
        require(binding.accountKey == authority.connection.accountKey && binding.localKey == selected.key &&
            binding.identity == identity && linked.nonce == binding.nonce)
        val target = ServerReadingTarget(binding.accountKey, identity.kind.name, binding.recordId)
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
        val value = Captured(selected, linked.copy(binding = binding.copy(identity = binding.identity.copy())),
            copySnapshot(snapshot), target)
        canonical(value)
        current()
        captured = value
        return value
    }

    private fun createCommand(selected: Captured): ServerLibraryOrganizationAdoption {
        val facade = object : ServerLibraryOrganizationStore {
            override fun read(target: ServerReadingTarget) = storage.read(target)
            override fun readNullable(target: ServerReadingTarget) = storage.readNullable(target)
            override fun targets(accountKey: String) = storage.targets(accountKey)
            override fun write(target: ServerReadingTarget, value: DeviceLibraryOrganization): Unit =
                error("Organization adoption requires guarded compare-and-write.")
            override fun compareAndWrite(target: ServerReadingTarget, expected: DeviceLibraryOrganization?,
                value: DeviceLibraryOrganization, guard: () -> Unit): Boolean {
                require(target == selected.target)
                return ticket.withCurrent { connection ->
                    requireSameConnection(connection)
                    bindings.verified(connection.accountKey, selected.entry, selected.binding, ::current) {
                        storage.compareAndWrite(target, expected, value, guard)
                    }
                }
            }
        }
        return ServerLibraryOrganizationAdoption(facade, selected.target, authority.connection, ::current,
            checkCanonical = {
                canonical(selected)
                verify(authority.connection, selected.target.recordId, selected.snapshot.identity)
                canonical(selected)
            },
            query = { query(authority.connection, selected.target.kind, selected.target.recordId) }, io = io)
    }

    private suspend fun canonical(selected: Captured) {
        current()
        val latest = detachedTextBindingEntry(selected.entry.copy(document = currentDocument(selected.entry)))
        require(latest.document == selected.entry.document) { "The selected document changed. Compare again." }
        current()
        withContext(io) {
            ticket.withCurrent { connection ->
                requireSameConnection(connection)
                bindings.verified(connection.accountKey, selected.entry, selected.binding, ::current) { }
            }
        }
        current()
    }

    /** Only memory state is read here; safe at the journal's last abortable guard. */
    private fun current() {
        check(!closed.get()) { "This organization comparison expired." }
        ticket.check()
        check(!closed.get()) { "This organization comparison expired." }
    }

    private fun requireSameConnection(value: ServerReadingConnection) {
        require(value.accountKey == authority.connection.accountKey && value.client === authority.connection.client)
    }
    private fun copySnapshot(value: LibraryOrganizationSnapshot) = LibraryOrganizationSnapshotJson.decode(
        LibraryOrganizationSnapshotJson.encode(value))
    private fun copyRemote(value: ServerLibraryOrganization, target: ServerReadingTarget) = ServerLibraryOrganizationJson.view(
        ServerLibraryOrganizationJson.encode(value), target.kind, target.recordId)
    private fun copyRecord(value: DeviceLibraryOrganization?, target: ServerReadingTarget) = value?.let {
        decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, it), target)
    }
}
