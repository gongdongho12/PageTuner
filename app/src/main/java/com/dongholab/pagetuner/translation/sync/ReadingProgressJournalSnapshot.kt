package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.core.backup.exchange.StrictExchangeJson

/**
 * A logical comparison token for one canonical progress scope. Canonical absence is distinct from
 * an existing empty row and never implies absence of historical UUID-case aliases. No file layout,
 * recovery, account permission or permanent insertion generation is represented by this value.
 */
class ReadingProgressJournalSnapshot(target: ServerReadingTarget, aliases: List<ReadingProgressJournalObservation>) {
    val target: ServerReadingTarget = target.copy()
    private val rows: List<ReadingProgressJournalObservation>

    init {
        require(this.target.accountKey.matches(Regex("[a-f0-9]{64}")))
        ServerReadingProgressJson.validateTarget(this.target.kind, this.target.recordId)
        val captured = aliases.toList().map { observation ->
            val scope = observation.target.copy()
            require(scope.accountKey == this.target.accountKey && scope.kind == this.target.kind &&
                scope.recordId.equals(this.target.recordId, ignoreCase = true)) { "Progress snapshot aliases do not match their scope." }
            // Reuse the raw-case-aware schema validator; never normalize an alias or its view IDs.
            val json = encodeDeviceReadingProgress(scope, observation.record)
            val raw = json.toString()
            require(raw.toByteArray(Charsets.UTF_8).size <= 65_536) { "Reading progress record is too large" }
            StrictExchangeJson.validate(raw, integerNumbersOnly = true, maxArrayEntries = 0, maxValues = 128)
            decodeReadingProgressObservation(json, StableContentHash.sha256(scope.key) + ".json")
        }
        require(captured.map { it.target.key }.toSet().size == captured.size) { "Duplicate progress snapshot alias." }
        rows = captured.sortedBy { it.target.key }
    }

    /** Detached, exact-canonical row only. Null can coexist with noncanonical alias rows. */
    val canonical: DeviceReadingProgress?
        get() = rows.firstOrNull { it.target == target }?.record?.detached()

    /** Order-independent comparison retains original raw target/view UUID spelling and every field. */
    val aliases: List<ReadingProgressJournalObservation>
        get() = rows.map { it.copy(target = it.target.copy(), record = it.record.detached()) }

    internal fun detached(): ReadingProgressJournalSnapshot = ReadingProgressJournalSnapshot(target, rows)

    override fun equals(other: Any?): Boolean = other is ReadingProgressJournalSnapshot && target == other.target && rows == other.rows
    override fun hashCode(): Int = 31 * target.hashCode() + rows.hashCode()

    private fun DeviceReadingProgress.detached() = copy(
        remote = remote?.let { it.copy(anchor = it.anchor?.copy()) },
        pending = pending?.let { it.copy(anchor = it.anchor.copy()) },
        queuedAnchor = queuedAnchor?.copy(),
        conflict = conflict?.let { it.copy(anchor = it.anchor?.copy()) },
    )
}
