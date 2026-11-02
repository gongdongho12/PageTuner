package com.dongholab.pagetuner.translation.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dongholab.pagetuner.core.content.StableContentHash
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android stat/AtomicFile coverage; compilation alone does not execute these tests. */
@RunWith(AndroidJUnit4::class)
class ServerReadingProgressObservationInstrumentedTest {
    private val target = ServerReadingTarget("a".repeat(64), "ORIGINAL", "abcdefab-cdef-4abc-8def-abcdefabcdef")
    private val anchor = ServerReadingAnchor(" p:é e\u0301 🌏 ", 1)
    private fun value(id: String, version: Long) = DeviceReadingProgress(
        remote = ServerReadingProgress(target.kind, id, version, anchor, "2026-11-02T00:00:00Z"),
        pending = ServerReadingMutation(version, "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb", anchor),
        queuedAnchor = anchor.copy(characterOffset = 2), retryAfterUntil = 12345,
    )

    @Test fun absentDirectoryAndExistingEmptyJournalStayDistinct() = withRoot { root ->
        val directory = File(root, "journals")
        val store = FileServerReadingProgressStore(directory)
        assertTrue(store.observeAliases(target).isEmpty())
        assertFalse(directory.exists())
        store.write(target, DeviceReadingProgress())
        assertEquals(listOf(ReadingProgressJournalObservation(target, DeviceReadingProgress())), store.observeAliases(target))
    }

    @Test fun originalCaseAliasesAndTheirJournalsArePreserved() = withRoot { root ->
        val directory = File(root, "journals")
        val store = FileServerReadingProgressStore(directory)
        val upper = target.copy(recordId = target.recordId.uppercase(java.util.Locale.ROOT))
        val lowerValue = value(target.recordId, 3)
        val upperValue = value(upper.recordId, 8)
        store.write(target, lowerValue); store.write(upper, upperValue)
        val before = directory.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals(setOf(ReadingProgressJournalObservation(target, lowerValue),
            ReadingProgressJournalObservation(upper, upperValue)), store.observeAliases(target).toSet())
        val after = directory.listFiles()!!.associate { it.name to it.readBytes() }
        assertEquals(before.keys, after.keys)
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, after.getValue(name)) }
    }

    @Test fun backupOnlyObservationDoesNotInvokeLegacyRecovery() = withRoot { root ->
        val directory = File(root, "journals").also { check(it.mkdir()) }
        val base = File(directory, StableContentHash.sha256(target.key) + ".json")
        val expected = value(target.recordId, 7)
        val bytes = encodeDeviceReadingProgress(target, expected).toString().toByteArray(Charsets.UTF_8)
        val backup = File(base.path + ".bak").also { it.writeBytes(bytes) }
        val store = FileServerReadingProgressStore(directory)
        assertEquals(listOf(ReadingProgressJournalObservation(target, expected)), store.observeAliases(target))
        assertFalse(base.exists())
        assertArrayEquals(bytes, backup.readBytes())
        // Normal reads retain their old recovery behavior, separately from strict observation.
        assertEquals(expected, store.read(target))
        assertArrayEquals(bytes, base.readBytes())
    }

    @Test fun duplicatePendingKeysCannotHideAnIntentOnAndroidJson() = withRoot { root ->
        val directory = File(root, "journals").also { check(it.mkdir()) }
        val base = File(directory, StableContentHash.sha256(target.key) + ".json")
        val valid = encodeDeviceReadingProgress(target, value(target.recordId, 3)).toString()
        for (duplicate in listOf("pending", "\\u0070ending")) {
            val bytes = (valid.dropLast(1) + ",\"$duplicate\":null}").toByteArray(Charsets.UTF_8)
            base.writeBytes(bytes)
            assertThrows(Exception::class.java) { FileServerReadingProgressStore(directory).observeAliases(target) }
            assertArrayEquals(bytes, base.readBytes())
        }
    }

    private fun withRoot(block: (File) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "progress-observe-${UUID.randomUUID()}")
        check(root.mkdir())
        try { block(root) } finally { root.deleteRecursively() }
    }
}
