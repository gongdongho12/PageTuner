package com.dongholab.pagetuner.translation.sync

import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A displayed copy is never the authority used by the commit command. */
internal class LibraryOrganizationAdoptionComparison internal constructor(
    val id: String,
    val record: DeviceLibraryOrganization?,
    val remote: ServerLibraryOrganization,
)

/**
 * A scoped, serial, GET-only actor for a separate explicit adoption intent.
 * The caller must supply canonical/binding/session authority; this is not a ZIP permission grant.
 * The final guard runs inside the journal lock and must not acquire earlier library/binding locks.
 * Production integration also needs coordination with an already-running sync actor's cached writes.
 * It neither connects a normal sync actor nor sends PUT, including on rejection.
 */
internal class ServerLibraryOrganizationAdoption(
    private val storage: ServerLibraryOrganizationStore,
    private val target: ServerReadingTarget,
    private val connection: ServerReadingConnection,
    private val guard: () -> Unit,
    private val checkCanonical: suspend () -> Unit,
    private val query: suspend () -> ServerLibraryOrganization = {
        connection.client.libraryOrganization(target.kind, target.recordId)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Command(val id: String, val record: DeviceLibraryOrganization?, val remote: ServerLibraryOrganization)
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val poisoned = AtomicBoolean(false)
    private var shown: Command? = null
    private var highWater: ServerLibraryOrganization? = null
    private var journalHighWater: ServerLibraryOrganization? = null

    init {
        require(target.accountKey == connection.accountKey)
        require(target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    }

    fun close() { closed.set(true) }

    suspend fun compare(): LibraryOrganizationAdoptionComparison = mutex.withLock {
        shown = null
        current(); checkCanonical(); current()
        val before = readClean()
        val remote = fetch()
        val after = readClean()
        require(after == before) { "The device organization changed. Compare again." }
        atLeast(remote, highWater)
        current(); checkCanonical(); current()
        val command = Command(UUID.randomUUID().toString(), before, remote)
        shown = command
        LibraryOrganizationAdoptionComparison(command.id, copyRecord(before), copyRemote(remote))
    }

    /** true means a durable local intent, never a server ACK. There is no implicit retry. */
    suspend fun adopt(comparison: LibraryOrganizationAdoptionComparison, value: LibraryOrganization): Boolean {
        // Detach before waiting for another command or performing any I/O.
        val selected = ServerLibraryOrganizationJson.organization(ServerLibraryOrganizationJson.encode(value))
        return mutex.withLock {
            current()
            val command = requireNotNull(shown?.takeIf { it.id == comparison.id }) { "Compare again before adopting." }
            shown = null // Every confirmation is single-use, including a rejected one.
            checkCanonical(); current()
            require(readClean() == command.record) { "The device organization changed. Compare again." }
            val remote = fetch()
            require(remote == command.remote) { "The account organization changed. Compare again." }
            require(remote.version < MaxReadingVersion) { "The account version is exhausted." }
            require(readClean() == command.record) { "The device organization changed. Compare again." }
            checkCanonical(); current()
            val next = DeviceLibraryOrganization(remote = remote,
                pending = LibraryOrganizationMutation(remote.version, UUID.randomUUID().toString(), selected),
                retryAfterUntil = command.record?.retryAfterUntil ?: 0)
            val caller = currentCoroutineContext()
            var committed = false
            try {
                withContext(io) {
                    committed = storage.compareAndWrite(target, command.record, next) {
                        caller.ensureActive(); current(); atLeast(remote, highWater); caller.ensureActive()
                    }
                    require(committed) { "The device organization changed. Compare again." }
                }
                true
            } catch (error: Exception) {
                // Dispatcher return cancellation after the durable write cannot roll it back.
                if (committed) true else throw error
            }
        }
    }

    private fun current() {
        check(!closed.get() && !poisoned.get()) { "This organization comparison expired." }
        guard()
        check(!closed.get() && !poisoned.get()) { "This organization comparison expired." }
    }

    private suspend fun readClean(): DeviceLibraryOrganization? {
        current()
        val record = withContext(io) { copyRecord(storage.readNullable(target)) }
        current()
        require(record?.pending == null && record?.queued == null && record?.conflict == null) {
            "Resolve pending organization edits and conflicts first."
        }
        record?.remote?.let { known ->
            try {
                atLeast(known, journalHighWater)
                journalHighWater = known
                if (highWater == null || known.version > highWater!!.version) highWater = known
                else if (known.version == highWater!!.version) atLeast(known, highWater)
            } catch (error: Exception) { poisoned.set(true); throw error }
        }
        return record
    }

    private suspend fun fetch(): ServerLibraryOrganization {
        current()
        val response = try { query() }
        catch (error: TranslationStoreException) {
            if (error.failure == TranslationStoreFailure.INVALID_RESPONSE) poisoned.set(true)
            throw error
        }
        current()
        val value = try { copyRemote(response).also(::observe) }
        catch (error: Exception) { poisoned.set(true); throw error }
        return value
    }

    private fun observe(value: ServerLibraryOrganization) {
        atLeast(value, highWater)
        highWater = value
    }

    private fun atLeast(value: ServerLibraryOrganization, known: ServerLibraryOrganization?) {
        if (!(known == null || value.version > known.version || value == known)) {
            poisoned.set(true)
            error("The account organization observation is inconsistent. Start a new comparison.")
        }
    }

    private fun copyRemote(value: ServerLibraryOrganization) = ServerLibraryOrganizationJson.view(
        ServerLibraryOrganizationJson.encode(value), target.kind, target.recordId)
    private fun copyRecord(value: DeviceLibraryOrganization?) = value?.let {
        decodeLibraryOrganizationJournal(encodeLibraryOrganizationJournal(target, it), target)
    }
}
