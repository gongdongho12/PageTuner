package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.translation.sync.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PortableGlossaryComparison(val id: String, val snapshot: BookGlossarySnapshot,
    val remote: ServerBookGlossary, val device: DeviceBookGlossary, val binding: PortableServerBinding)

data class PortableGlossaryAdoptionState(val entry: PortableLibraryEntry? = null,
    val snapshots: List<BookGlossarySnapshot> = emptyList(), val comparison: PortableGlossaryComparison? = null,
    val busy: Boolean = false, val committed: Boolean = false, val error: String? = null)

/** Reading a ZIP or comparing it never opens a synchronization actor or modifies its journal. */
class PortableGlossaryAdoption(
    private val scope: CoroutineScope,
    private val currentDocument: suspend (PortableLibraryEntry) -> ExchangeDocument,
    private val readDocument: (PortableLibraryEntry) -> ExchangeDocument,
    private val binding: (String, PortableLibraryEntry) -> PortableServerBinding?,
    private val journal: (BookGlossaryTarget) -> DeviceBookGlossary,
    private val verify: suspend (ServerReadingConnection, String, DocumentIdentity) -> Unit = { account, record, identity ->
        account.client.verifyPortableIdentity(record, identity)
    },
    private val query: suspend (ServerReadingConnection, com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity) -> ServerBookGlossary = { account, identity ->
        account.client.bookGlossary(identity)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(PortableGlossaryAdoptionState())
    val state = mutableState.asStateFlow()
    @Volatile private var connection: ServerReadingConnection? = null
    @Volatile private var generation = 0L
    private var operation: Job? = null
    private val consumed = mutableSetOf<String>()
    fun matches(value: ServerReadingConnection?): Boolean = connection?.let { PortableExportTickets.sameConnection(it, value) } == true

    fun connect(value: ServerReadingConnection?) {
        if (connection == value) return
        connection = value; close()
    }
    fun close() { generation++; operation?.cancel(); operation = null; mutableState.value = PortableGlossaryAdoptionState() }
    private fun check(ticket: Long, expected: ServerReadingConnection, current: () -> ServerReadingConnection?) {
        check(generation == ticket && PortableExportTickets.sameConnection(expected, connection) &&
            PortableExportTickets.sameConnection(expected, current())) { "This comparison expired. Select the ZIP again." }
    }

    fun select(entry: PortableLibraryEntry, account: ServerReadingConnection?, current: () -> ServerReadingConnection?) {
        close(); connection = account
        mutableState.value = PortableGlossaryAdoptionState(entry = entry, busy = true)
        val ticket = generation
        operation = scope.launch {
            try {
                val expected = requireNotNull(account) { "Sign in and connect this document first." }
                check(ticket, expected, current)
                val document = currentDocument(entry)
                check(ticket, expected, current)
                val selected = entry.copy(document = document)
                val snapshots = withContext(io) {
                    requireNotNull(binding(expected.accountKey, selected)) { "Connect this document to server records first." }
                    requireNotNull(BookGlossarySnapshotsJson.fromDocument(document)) { "This ZIP has no account glossary snapshots." }.snapshots
                }
                check(ticket, expected, current)
                mutableState.value = PortableGlossaryAdoptionState(entry = selected, snapshots = snapshots)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == ticket) mutableState.value = state.value.copy(busy = false, error = error.message) }
        }
    }

    fun compare(index: Int, current: () -> ServerReadingConnection?) {
        val selected = state.value
        val entry = selected.entry ?: return
        val snapshot = selected.snapshots.getOrNull(index) ?: return
        val expected = connection ?: return
        if (selected.busy || selected.committed) return
        val ticket = ++generation
        operation?.cancel()
        mutableState.value = selected.copy(comparison = null, busy = true, error = null)
        operation = scope.launch {
            try {
                check(ticket, expected, current)
                val document = currentDocument(entry)
                check(ticket, expected, current)
                val linked = withContext(io) { requireNotNull(binding(expected.accountKey, entry.copy(document = document))) }
                val target = BookGlossaryTarget(expected.accountKey, snapshot.identity)
                fun guard() = validate(ticket, expected, current, entry, linked, snapshot)
                withContext(io) { guard() }
                verify(expected, linked.recordId, linked.identity)
                check(ticket, expected, current)
                val remote = query(expected, target.identity)
                check(ticket, expected, current)
                val local = withContext(io) { guard(); journal(target).also { validateBase(it, remote, target) } }
                check(ticket, expected, current)
                mutableState.value = state.value.copy(busy = false,
                    comparison = PortableGlossaryComparison(UUID.randomUUID().toString(), snapshot, remote, local, linked))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == ticket) mutableState.value = state.value.copy(busy = false, error = error.message) }
        }
    }

    fun confirm(id: String, current: () -> ServerReadingConnection?, sync: ServerBookGlossarySync) =
        confirm(id, current) { comparison, account, guard ->
            sync.adoptSnapshot(comparison.id, BookGlossaryTarget(account.accountKey, comparison.snapshot.identity), account,
                comparison.device, comparison.remote, comparison.snapshot, guard)
        }

    internal fun confirm(id: String, current: () -> ServerReadingConnection?,
        apply: suspend (PortableGlossaryComparison, ServerReadingConnection, () -> Unit) -> Result<Unit>) {
        val selected = state.value
        val entry = selected.entry ?: return
        val shown = selected.comparison?.takeIf { it.id == id } ?: return
        val expected = connection ?: return
        if (selected.busy || selected.committed || shown.snapshot.presence == BookGlossarySnapshotPresence.ABSENT || !consumed.add(id)) return
        val ticket = generation
        mutableState.value = selected.copy(busy = true, error = null)
        operation = scope.launch {
            try {
                fun guard() = validate(ticket, expected, current, entry, shown.binding, shown.snapshot)
                withContext(io) { guard() }
                verify(expected, shown.binding.recordId, shown.binding.identity)
                check(ticket, expected, current)
                val refreshed = query(expected, shown.snapshot.identity)
                check(ticket, expected, current)
                require(refreshed == shown.remote) { "The server glossary changed. Compare again before adopting." }
                withContext(io) {
                    guard()
                    val target = BookGlossaryTarget(expected.accountKey, shown.snapshot.identity)
                    val local = journal(target)
                    validateBase(local, refreshed, target)
                    require(local == shown.device) { "The device glossary changed. Compare again before adopting." }
                }
                check(ticket, expected, current)
                apply(shown, expected, ::guard).getOrThrow()
                if (generation == ticket) mutableState.value = state.value.copy(busy = false, committed = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == ticket) mutableState.value = state.value.copy(busy = false, comparison = null, error = error.message) }
        }
    }

    private fun validate(ticket: Long, expected: ServerReadingConnection, current: () -> ServerReadingConnection?,
        entry: PortableLibraryEntry, linked: PortableServerBinding, snapshot: BookGlossarySnapshot) {
        check(ticket, expected, current)
        val document = readDocument(entry)
        require(linked.accountKey == expected.accountKey && linked.localKey == entry.key &&
            binding(expected.accountKey, entry.copy(document = document)) == linked && document.assets.isEmpty() &&
            DocumentIdentityJson.fromDocument(document) == linked.identity) { "The document connection changed. Compare again." }
        require(snapshot.identity.providerId == linked.identity.contentProviderId && snapshot.identity.bookId == linked.identity.bookId &&
            (linked.identity.kind == DocumentIdentityKind.ORIGINAL || snapshot.identity.targetLanguage == linked.identity.targetLanguage)) {
            "This snapshot does not match the original book and translation language."
        }
        require(BookGlossarySnapshotsJson.fromDocument(document)?.snapshots?.singleOrNull { it.identity == snapshot.identity } == snapshot) {
            "The ZIP glossary changed. Compare again."
        }
        check(ticket, expected, current)
    }

    private fun validateBase(local: DeviceBookGlossary, remote: ServerBookGlossary, target: BookGlossaryTarget) {
        require(remote.identity == target.identity)
        ServerBookGlossaryJson.encode(remote)
        require(local.pending == null && local.queued == null && local.conflict == null) { "Resolve pending glossary edits and conflicts first." }
        local.remote?.let { known -> require(known.identity == remote.identity && known.version <= remote.version &&
            (known.version != remote.version || known == remote)) { "The account glossary changed. Compare again." } }
    }
}
