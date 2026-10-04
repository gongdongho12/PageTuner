package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncEntry
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncValidation

enum class BookGlossarySnapshotPresence { ABSENT, DELETED, PRESENT }

/** An observed value, without account ownership, CAS versions or a synchronization command. */
data class BookGlossarySnapshot(
    val identity: BookGlossarySyncIdentity,
    val presence: BookGlossarySnapshotPresence,
    val entries: List<BookGlossarySyncEntry>?,
)

/** Ordered passive snapshots. A separate adapter must check their relationship to a document. */
data class BookGlossarySnapshots(
    val version: Int = 1,
    val snapshots: List<BookGlossarySnapshot>,
)

object BookGlossarySnapshotValidation {
    const val MAX_SCOPES = 100

    fun validate(value: BookGlossarySnapshots) {
        require(value.version == 1) { "Unsupported book glossary snapshots version." }
        require(value.snapshots.size <= MAX_SCOPES) { "Too many book glossary snapshot scopes." }
        require(value.snapshots.map { it.identity }.toSet().size == value.snapshots.size) {
            "Duplicate book glossary snapshot scope."
        }
        value.snapshots.forEach(::validateSnapshot)
    }

    fun validateSnapshot(snapshot: BookGlossarySnapshot) {
        BookGlossarySyncValidation.validateIdentity(snapshot.identity)
        when (snapshot.presence) {
            BookGlossarySnapshotPresence.PRESENT -> BookGlossarySyncValidation.validateEntries(
                requireNotNull(snapshot.entries) { "A present glossary snapshot requires an entries array." })
            BookGlossarySnapshotPresence.ABSENT, BookGlossarySnapshotPresence.DELETED ->
                require(snapshot.entries == null) { "An absent or deleted glossary snapshot requires null entries." }
        }
    }
}
