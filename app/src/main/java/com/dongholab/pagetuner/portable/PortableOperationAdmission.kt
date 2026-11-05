package com.dongholab.pagetuner.portable

internal data class PortableOperationAdmission(val state: PortableLibraryState, val admitted: Boolean)

/** Admission happens before any UI state reset; rejected old callbacks preserve the current owner. */
internal fun admitPortableOperation(previous: PortableLibraryState, beforeStart: (() -> Unit)?): PortableOperationAdmission {
    if (previous.busy || runCatching { beforeStart?.invoke() }.isFailure)
        return PortableOperationAdmission(previous, false)
    return PortableOperationAdmission(previous.copy(busy = true, error = null, status = null,
        glossaryPresence = null, organizationPresence = null, progressPresence = null), true)
}