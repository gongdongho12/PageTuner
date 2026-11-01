package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.*
import com.dongholab.pagetuner.core.model.glossary.BookGlossarySyncIdentity
import com.dongholab.pagetuner.translation.sync.*

/** Explicit read-only account query. Cached/pending device dictionaries never enter this snapshot. */
object PortableAccountGlossaryExport {
    suspend fun create(document: ExchangeDocument, recordId: String, selectedTargetLanguage: String,
        verify: suspend (String, DocumentIdentity) -> Unit,
        query: suspend (BookGlossarySyncIdentity) -> ServerBookGlossary,
        currentDocument: suspend () -> ExchangeDocument,
        isCurrent: () -> Boolean): ExchangeDocument {
        require(PortableIdentityVerification.canonicalUuid(recordId))
        val source = requireNotNull(DocumentIdentityJson.fromDocument(document))
        val identity = BookGlossarySyncIdentity(source.contentProviderId, source.bookId, selectedTargetLanguage)
        // Validate the explicit language selection before any network request.
        AccountGlossarySnapshotValidation.validateRequested(AccountGlossarySnapshot(identity.providerId, identity.bookId,
            identity.targetLanguage, AccountGlossarySnapshotState.ABSENT, null), source, selectedTargetLanguage)
        require(isCurrent()) { "Account or document selection changed." }
        verify(recordId, source)
        require(isCurrent()) { "Account or document selection changed." }
        val remote = query(identity)
        require(remote.identity == identity)
        val latestDocument = currentDocument()
        require(latestDocument == document && isCurrent()) { "Account or document changed during export." }
        val entries = remote.value.entries?.map { it.syncEntry() }
        val state = when {
            entries != null -> AccountGlossarySnapshotState.PRESENT
            remote.version == 0L -> AccountGlossarySnapshotState.ABSENT
            else -> AccountGlossarySnapshotState.DELETED
        }
        require(remote.version >= 0)
        return AccountGlossarySnapshotJson.withSnapshot(document,
            AccountGlossarySnapshot(identity.providerId, identity.bookId, identity.targetLanguage, state, entries), selectedTargetLanguage)
    }
}
