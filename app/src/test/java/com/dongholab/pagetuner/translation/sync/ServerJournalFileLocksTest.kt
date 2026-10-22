package com.dongholab.pagetuner.translation.sync

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerJournalFileLocksTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun canonicalAliasesExcludeReadersUntilThePendingJournalCommitFinishes() {
        val directory = temporary.newFolder()
        val executor = Executors.newSingleThreadExecutor()
        val file = File(directory, "journal.json").also { it.writeText("old") }
        try {
            val result = ServerJournalFileLocks.withDirectory(directory) {
                val entered = CountDownLatch(1)
                val reader = executor.submit<String> {
                    entered.countDown()
                    ServerJournalFileLocks.withDirectory(File(directory, ".")) { file.readText() }
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { reader.get(100, TimeUnit.MILLISECONDS) }
                file.writeText("pending mutation preserved")
                reader
            }
            assertEquals("pending mutation preserved", result.get(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }

    @Test fun failedAndReentrantOperationsReleaseTheCanonicalDirectoryLock() {
        val directory = temporary.newFolder()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertThrows(IllegalStateException::class.java) { ServerJournalFileLocks.withDirectory(directory) {
                ServerJournalFileLocks.withDirectory(File(directory, ".")) { error("failed read") }
            } }
            assertEquals("next read", executor.submit<String> {
                ServerJournalFileLocks.withDirectory(directory) { "next read" }
            }.get(5, TimeUnit.SECONDS))
        } finally { executor.shutdownNow(); assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS)) }
    }
}
