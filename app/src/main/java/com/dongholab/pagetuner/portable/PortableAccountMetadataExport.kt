package com.dongholab.pagetuner.portable

import com.dongholab.pagetuner.core.backup.exchange.ExchangeDocument
import com.dongholab.pagetuner.core.backup.exchange.LibraryOrganizationSnapshotJson

internal data class PreparedAccountMetadata(val document: ExchangeDocument, val checkBeforeWrite: () -> Unit)

/** Compose on one output document; the retained source guards still compare canonical inputs. */
internal fun combineAccountMetadataExports(glossary: PreparedAccountGlossary?, organization: PreparedAccountOrganization?,
    current: () -> Unit): PreparedAccountMetadata {
    require(glossary != null || organization != null)
    val guard = {
        current()
        glossary?.checkBeforeWrite?.invoke()
        organization?.checkBeforeWrite?.invoke()
        current()
    }
    guard()
    val document = when {
        glossary != null && organization != null -> LibraryOrganizationSnapshotJson.withSnapshot(glossary.document, organization.snapshot)
        glossary != null -> glossary.document
        else -> requireNotNull(organization).document
    }
    guard()
    return PreparedAccountMetadata(document, guard)
}
