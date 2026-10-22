package com.dongholab.pagetuner.translation.sync

import java.io.File

/** AtomicFile needs external exclusion, including reads which may recover a backup. */
internal object ServerJournalFileLocks {
    private val locks = mutableMapOf<String, Any>()
    fun <T> withDirectory(directory: File, block: () -> T): T {
        val lock = synchronized(locks) { locks.getOrPut(directory.canonicalPath) { Any() } }
        return synchronized(lock) { block() }
    }
}
