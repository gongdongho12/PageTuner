package com.dongholab.pagetuner.sharing

/** Retains metadata descriptors only. A selected-document GET never reopens unrelated archives. */
internal class SharingInventory<T>(private val identity: (T) -> String, private val scan: () -> List<T>) {
    private var byId: Map<String, T>? = null
    fun refresh(): List<T> = scan().also { values -> byId = values.associateBy(identity) }
    fun find(id: String): T? {
        if (byId == null) refresh()
        return byId?.get(id)
    }
}
