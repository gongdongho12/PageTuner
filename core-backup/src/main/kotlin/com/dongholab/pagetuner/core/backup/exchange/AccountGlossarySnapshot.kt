package com.dongholab.pagetuner.core.backup.exchange

import com.dongholab.pagetuner.core.model.glossary.*

enum class AccountGlossarySnapshotState { PRESENT, ABSENT, DELETED }

data class AccountGlossarySnapshot(
    val providerId: String, val bookId: String, val targetLanguage: String,
    val state: AccountGlossarySnapshotState, val entries: List<BookGlossarySyncEntry>?,
) {
    val identity get() = BookGlossarySyncIdentity(providerId, bookId, targetLanguage)
}
data class AccountGlossarySnapshots(val version: Int = 1, val snapshots: List<AccountGlossarySnapshot>)

/** Passive portable records only; never a server mutation, authorization, or synchronization journal. */
object AccountGlossarySnapshotValidation {
    const val MAX_SNAPSHOTS = 16
    fun validate(value: AccountGlossarySnapshots, source: DocumentIdentity) {
        DocumentIdentities.validate(source)
        require(value.version == 1 && value.snapshots.size <= MAX_SNAPSHOTS)
        require(value.snapshots.map { it.identity }.distinct().size == value.snapshots.size)
        value.snapshots.forEach { snapshot ->
            BookGlossarySyncValidation.validateIdentity(snapshot.identity)
            require(snapshot.providerId == source.contentProviderId && snapshot.bookId == source.bookId)
            if (source.kind == DocumentIdentityKind.TRANSLATION) require(snapshot.targetLanguage == source.targetLanguage)
            require((snapshot.state == AccountGlossarySnapshotState.PRESENT) == (snapshot.entries != null))
            snapshot.entries?.let(BookGlossarySyncValidation::validateEntries)
        }
    }
    fun validateRequested(snapshot: AccountGlossarySnapshot, source: DocumentIdentity, selectedTargetLanguage: String) {
        validate(AccountGlossarySnapshots(snapshots = listOf(snapshot)), source)
        require(snapshot.targetLanguage == selectedTargetLanguage) { "Glossary export target language changed." }
    }
}
