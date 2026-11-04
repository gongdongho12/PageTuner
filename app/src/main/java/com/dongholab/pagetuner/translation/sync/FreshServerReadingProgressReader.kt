package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal enum class FreshReadingProgressFailure {
    STALE, PENDING, NOT_READ, STALE_VIEW, INVALID_RESPONSE, STORAGE,
}

/** Fixed internal messages never include a transport body, journal value or account credentials. */
internal class FreshReadingProgressException(val failure: FreshReadingProgressFailure) : IllegalStateException(
    when (failure) {
        FreshReadingProgressFailure.STALE -> "This reading progress session expired."
        FreshReadingProgressFailure.PENDING -> "Resolve pending reading progress changes and conflicts first."
        FreshReadingProgressFailure.NOT_READ -> "Read the latest account reading progress first."
        FreshReadingProgressFailure.STALE_VIEW -> "The observed reading progress changed. Read it again."
        FreshReadingProgressFailure.INVALID_RESPONSE -> "The account reading progress response is inconsistent."
        FreshReadingProgressFailure.STORAGE -> "The device reading progress journals could not be verified."
    },
)

/**
 * A scoped GET-only observer, never a normal sync controller or a ZIP permission grant.
 * The required current guard belongs to a caller-owned terminal account/selection lifetime.
 * Network awaits and external guards never run while holding the memory or journal monitor.
 */
internal class FreshServerReadingProgressReader(
    private val storage: ServerReadingProgressStore,
    private val target: ServerReadingTarget,
    private val connection: ServerReadingConnection,
    private val current: () -> Unit,
    private val query: suspend () -> ServerReadingProgress = {
        connection.client.readingProgress(target.kind, target.recordId)
    },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private data class JournalBatch(val views: List<ServerReadingProgress>, val dirty: Boolean)
    private val closed = AtomicBoolean(false)
    private val memory = Any()
    private var serverHighWater: ServerReadingProgress? = null
    private var journalHighWater: ServerReadingProgress? = null
    private var approved: ServerReadingProgress? = null

    init {
        require(target.accountKey == connection.accountKey && target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(target.kind, target.recordId)
    }

    fun close() { closed.set(true) }

    /** Memory/lifetime check only; this does not approve previously prepared bytes. */
    fun assertCurrent() {
        open()
        try { current() }
        catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (_: Exception) { close(); throw FreshReadingProgressException(FreshReadingProgressFailure.STALE) }
        open()
    }

    suspend fun read(): ServerReadingProgress = cancellable {
        inspectAsync()
        assertCurrent(); currentCoroutineContext().ensureActive()
        val response = fetch()
        assertCurrent(); currentCoroutineContext().ensureActive()
        val fresh = try { captureView(response, alias = false) }
        catch (_: Exception) { invalidResponse() }
        assertCurrent()
        synchronized(memory) {
            open()
            atLeast(fresh, serverHighWater)
            atLeast(fresh, journalHighWater)
            // Retain a validated GET even if the following journal inspection rejects pending data.
            serverHighWater = fresh
        }
        inspectAsync()
        assertCurrent(); currentCoroutineContext().ensureActive()
        synchronized(memory) {
            open()
            // Another GET or journal read can finish while the final IO return is dispatched.
            atLeast(fresh, serverHighWater)
            atLeast(fresh, journalHighWater)
            approved = fresh
        }
        fresh.detached()
    }

    /** IO is dispatched off the caller, then the pinned view is checked again after that await. */
    suspend fun checkReady(expected: ServerReadingProgress) = cancellable {
        val pinned = captureExpected(expected)
        assertCurrent()
        synchronized(memory) { ready(pinned) }
        inspectAsync()
        assertCurrent(); currentCoroutineContext().ensureActive()
        synchronized(memory) { ready(pinned) }
    }

    /**
     * Blocking journal IO for a future SAF writer already running on its storage dispatcher.
     * The caller must also check its active coroutine/ticket and canonical document/binding.
     * No coroutine or account authority can be inferred from a previously completed read job.
     */
    fun checkBeforeWrite(expected: ServerReadingProgress) {
        val pinned = captureExpected(expected)
        assertCurrent()
        synchronized(memory) { ready(pinned) }
        val batch = captureJournal()
        assertCurrent()
        synchronized(memory) { publishJournal(batch) }
        assertCurrent()
        synchronized(memory) { ready(pinned) }
    }

    private suspend fun <T> cancellable(action: suspend () -> T): T {
        try {
            currentCoroutineContext().ensureActive()
            assertCurrent()
            return action()
        } catch (cancelled: CancellationException) {
            close()
            throw cancelled
        }
    }

    private suspend fun fetch(): ServerReadingProgress {
        try { return query() }
        catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (error: Exception) {
            // Late failures belong to their captured session, never to replacement credentials.
            assertCurrent()
            if (error is TranslationStoreException) {
                if (error.failure == TranslationStoreFailure.INVALID_RESPONSE) close()
                throw error
            }
            if (error is IOException) throw TranslationStoreException(TranslationStoreFailure.NETWORK)
            invalidResponse()
        }
    }

    private suspend fun inspectAsync() {
        assertCurrent()
        val batch = withContext(io) { captureJournal() }
        assertCurrent(); currentCoroutineContext().ensureActive()
        synchronized(memory) { publishJournal(batch) }
    }

    private fun captureJournal(): JournalBatch {
        assertCurrent()
        val batch = try {
            val records = storage.observeAliases(target).toList().map { observation ->
                require(observation.target.accountKey == target.accountKey && observation.target.kind == target.kind &&
                    canonicalUuid(observation.target.recordId) == target.recordId)
                val raw = observation.record
                val projected = raw.copy(
                    remote = raw.remote?.let { captureView(it, alias = true) },
                    conflict = raw.conflict?.let { captureView(it, alias = true) },
                    pending = raw.pending?.let { it.copy(anchor = it.anchor.copy()) },
                    queuedAnchor = raw.queuedAnchor?.copy(),
                )
                // Typed fakes must obey the same full journal invariants as the production store.
                decodeDeviceReadingProgress(encodeDeviceReadingProgress(target, projected), target)
            }
            val versions = mutableMapOf<Long, ServerReadingProgress>()
            for (record in records) for (view in listOfNotNull(record.remote, record.conflict)) {
                val previous = versions.putIfAbsent(view.version, view)
                require(previous == null || previous == view)
            }
            JournalBatch(versions.values.toList(), records.any { it.pending != null || it.queuedAnchor != null || it.conflict != null })
        } catch (cancelled: CancellationException) { close(); throw cancelled }
        catch (_: Exception) {
            assertCurrent()
            close()
            throw FreshReadingProgressException(FreshReadingProgressFailure.STORAGE)
        }
        assertCurrent()
        return batch
    }

    /** Called with memory held only after all directory IO and external guards have finished. */
    private fun publishJournal(batch: JournalBatch) {
        open()
        for (view in batch.views) {
            if (journalHighWater?.version == view.version && journalHighWater != view) invalidResponse()
            if (serverHighWater?.version == view.version && serverHighWater != view) invalidResponse()
        }
        val highest = batch.views.maxByOrNull { it.version }
        if (highest != null && (journalHighWater == null || highest.version > journalHighWater!!.version)) journalHighWater = highest
        // Updating the high-water mark must precede this nonterminal rejection.
        if (batch.dirty) throw FreshReadingProgressException(FreshReadingProgressFailure.PENDING)
    }

    /** Called with memory held. Expected is the actual returned GET, not merely a later latest memo. */
    private fun ready(expected: ServerReadingProgress) {
        open()
        if (approved == null) throw FreshReadingProgressException(FreshReadingProgressFailure.NOT_READ)
        if (approved != expected || !covers(expected, serverHighWater) || !covers(expected, journalHighWater)) {
            throw FreshReadingProgressException(FreshReadingProgressFailure.STALE_VIEW)
        }
    }

    private fun captureExpected(value: ServerReadingProgress): ServerReadingProgress = try {
        captureView(value, alias = false)
    } catch (_: Exception) {
        throw FreshReadingProgressException(FreshReadingProgressFailure.STALE_VIEW)
    }

    private fun captureView(value: ServerReadingProgress, alias: Boolean): ServerReadingProgress {
        require(value.kind == target.kind)
        if (alias) require(canonicalUuid(value.recordId) == target.recordId) else require(value.recordId == target.recordId)
        val canonical = value.copy(recordId = target.recordId, anchor = value.anchor?.copy())
        return ServerReadingProgressJson.decode(ServerReadingProgressJson.encode(canonical), target.kind, target.recordId)
    }

    private fun canonicalUuid(value: String): String {
        val canonical = UUID.fromString(value).toString()
        require(canonical.length == value.length && canonical.equals(value, ignoreCase = true))
        return canonical
    }

    private fun covers(value: ServerReadingProgress, known: ServerReadingProgress?) =
        known == null || value.version > known.version || value == known

    private fun atLeast(value: ServerReadingProgress, known: ServerReadingProgress?) {
        if (!covers(value, known)) invalidResponse()
    }

    private fun invalidResponse(): Nothing {
        close()
        throw FreshReadingProgressException(FreshReadingProgressFailure.INVALID_RESPONSE)
    }

    private fun open() {
        if (closed.get()) throw FreshReadingProgressException(FreshReadingProgressFailure.STALE)
    }

    private fun ServerReadingProgress.detached() = copy(anchor = anchor?.copy())
}
