package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Display copies are not the private single-use command or a ZIP/account permission grant. */
internal class ReadingProgressAdoptionComparison internal constructor(
    val id: String,
    val record: ReadingProgressJournalSnapshot,
    val remote: ServerReadingProgress,
)

/**
 * Internal, scoped direct-GET comparison and separately confirmed durable intent.
 *
 * The caller supplies terminal memory-only authority and canonical checks. A future production
 * store facade must hold ticket.withCurrent -> text verified -> journal CAS through the final
 * guard. This class does not establish canonical text membership, a binding or Compose ownership.
 * Network awaits are outside account/library/binding/journal monitors; only command serialization
 * is held. No normal controller is opened and no PUT, handoff or implicit retry is performed.
 */
internal class ServerReadingProgressAdoption(
    private val storage: ServerReadingProgressStore,
    private val target: ServerReadingTarget,
    private val connection: ServerReadingConnection,
    private val guard: () -> Unit,
    private val checkCanonical: suspend () -> Unit,
    private val query: suspend () -> ServerReadingProgress = {
        connection.client.readingProgress(target.kind, target.recordId)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class Command(
        val comparison: ReadingProgressAdoptionComparison,
        val record: ReadingProgressJournalSnapshot,
        val remote: ServerReadingProgress,
    )
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private var shown: Command? = null
    private var serverHighWater: ServerReadingProgress? = null
    private var journalHighWater: ServerReadingProgress? = null

    init {
        require(target.accountKey == connection.accountKey && target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    }

    fun close() { closed.set(true) }

    suspend fun compare(): ReadingProgressAdoptionComparison = command {
        shown = null
        canonical()
        val before = readClean()
        val remote = fetch()
        val after = readClean()
        ready(remote)
        require(after == before) { "The device reading progress changed. Compare again." }
        canonical()
        ready(remote)
        val comparison = ReadingProgressAdoptionComparison(UUID.randomUUID().toString(), before.detached(), remote.detached())
        shown = Command(comparison, before, remote)
        comparison
    }

    /** True is a new durable local intent, never a server ACK or an automatic navigation/reset. */
    suspend fun adopt(comparison: ReadingProgressAdoptionComparison, value: ServerReadingAnchor): Boolean {
        // Copy before waiting; validation follows token consumption so a rejected confirmation
        // cannot reuse its authority. Actual text membership is the canonical adapter's duty.
        val selected = value.copy()
        return command {
            val selectedCommand = requireNotNull(shown?.takeIf { it.comparison === comparison }) {
                "Compare again before adopting reading progress."
            }
            shown = null
            ServerReadingProgressJson.validateAnchor(selected)
            canonical()
            require(readClean() == selectedCommand.record) { "The device reading progress changed. Compare again." }
            val remote = fetch()
            require(remote == selectedCommand.remote) { "The account reading progress changed. Compare again." }
            require(remote.version < MaxReadingVersion) { "The account reading progress version is exhausted." }
            val latest = readClean()
            ready(remote)
            require(latest == selectedCommand.record) { "The device reading progress changed. Compare again." }
            canonical()
            ready(remote)
            val next = DeviceReadingProgress(
                remote = remote,
                pending = ServerReadingMutation(remote.version, UUID.randomUUID().toString(), selected),
                retryAfterUntil = selectedCommand.record.canonical?.retryAfterUntil,
            )
            persist(selectedCommand.record, next, remote)
        }
    }

    /** Cancellation while waiting for the serial command also permanently retires this instance. */
    private suspend fun <T> command(action: suspend () -> T): T = try {
        currentCoroutineContext().ensureActive()
        current()
        mutex.withLock {
            currentCoroutineContext().ensureActive()
            current()
            action()
        }
    } catch (cancelled: CancellationException) {
        close()
        throw cancelled
    }

    private fun current() {
        check(!closed.get()) { "This reading progress comparison expired." }
        try { guard() }
        catch (error: Throwable) { close(); throw error }
        check(!closed.get()) { "This reading progress comparison expired." }
    }

    private suspend fun canonical() {
        current()
        try { checkCanonical() }
        catch (error: Throwable) { close(); throw error }
        currentCoroutineContext().ensureActive()
        current()
    }

    private suspend fun readClean(): ReadingProgressJournalSnapshot {
        current()
        val snapshot = try {
            withContext(io) {
                storage.captureSnapshot(target).detached().also { require(it.target == target) }
            }
        } catch (error: Throwable) { close(); throw error }
        currentCoroutineContext().ensureActive()
        current()
        // Observe the entire batch before dirty/alias rejection. A lower old alias/row cannot
        // lower a retained high-water mark, and a rejected pending row cannot erase knowledge.
        val rows = snapshot.aliases
        val versions = mutableMapOf<Long, ServerReadingProgress>()
        for (row in rows) for (raw in listOfNotNull(row.record.remote, row.record.conflict)) {
            val view = try { copyView(raw, alias = true) }
            catch (_: Exception) { invalidResponse() }
            val previous = versions[view.version]
            if (previous != null && previous != view) invalidResponse()
            versions[view.version] = view
        }
        for (view in versions.values) {
            if (journalHighWater?.version == view.version && journalHighWater != view) invalidResponse()
            if (serverHighWater?.version == view.version && serverHighWater != view) invalidResponse()
        }
        versions.values.maxByOrNull { it.version }?.let { highest ->
            if (journalHighWater == null || highest.version > journalHighWater!!.version) journalHighWater = highest
        }
        require(rows.all { it.target == target }) {
            "Historical reading progress aliases require explicit recovery first."
        }
        require(rows.none { it.record.pending != null || it.record.queuedAnchor != null || it.record.conflict != null }) {
            "Resolve pending reading progress changes and conflicts first."
        }
        return snapshot
    }

    private suspend fun fetch(): ServerReadingProgress {
        current()
        currentCoroutineContext().ensureActive()
        val response = try { query() }
        catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (error: TranslationStoreException) {
            current()
            if (error.failure == TranslationStoreFailure.INVALID_RESPONSE) close()
            throw error
        } catch (_: IOException) {
            current()
            throw TranslationStoreException(TranslationStoreFailure.NETWORK)
        } catch (_: Exception) {
            current()
            invalidResponse()
        }
        currentCoroutineContext().ensureActive()
        current()
        val value = try { copyView(response, alias = false) }
        catch (_: Exception) { invalidResponse() }
        ready(value)
        serverHighWater = value
        return value
    }

    private suspend fun persist(expected: ReadingProgressJournalSnapshot, next: DeviceReadingProgress,
        remote: ServerReadingProgress): Boolean {
        val caller = currentCoroutineContext()
        val committed = AtomicBoolean(false)
        try {
            withContext(io) {
                committed.set(storage.compareAndWrite(target, expected, next) {
                    caller.ensureActive()
                    current()
                    ready(remote)
                    caller.ensureActive()
                })
            }
        } catch (error: Throwable) {
            if (error is CancellationException) close()
            // The dispatcher can reject delivery after the atomic commit. No later current,
            // canonical, observer or cancellation check may turn that durable intent into false.
            if (committed.get()) return true
            close()
            throw error
        }
        check(committed.get()) { "The device reading progress changed. Compare again." }
        return true
    }

    /** Memory-only and safe for the final journal guard; never reads storage or earlier monitors. */
    private fun ready(value: ServerReadingProgress) {
        current()
        if (!covers(value, serverHighWater) || !covers(value, journalHighWater)) invalidResponse()
    }

    private fun covers(value: ServerReadingProgress, known: ServerReadingProgress?) =
        known == null || value.version > known.version || value == known

    private fun copyView(value: ServerReadingProgress, alias: Boolean): ServerReadingProgress {
        require(value.kind == target.kind)
        if (alias) require(value.recordId.length == target.recordId.length && value.recordId.equals(target.recordId, ignoreCase = true))
        else require(value.recordId == target.recordId)
        val projected = value.copy(recordId = target.recordId, anchor = value.anchor?.copy())
        return ServerReadingProgressJson.decode(ServerReadingProgressJson.encode(projected), target.kind, target.recordId)
    }

    private fun invalidResponse(): Nothing {
        close()
        throw TranslationStoreException(TranslationStoreFailure.INVALID_RESPONSE)
    }

    private fun ServerReadingProgress.detached() = copy(anchor = anchor?.copy())
}
