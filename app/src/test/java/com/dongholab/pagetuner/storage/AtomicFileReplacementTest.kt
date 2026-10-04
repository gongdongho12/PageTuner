package com.dongholab.pagetuner.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFileReplacementTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun replacesExistingFileEvenWhenLegacyRenameRefusesIt() {
        val target = temporaryFolder.newFile("record.json").apply { writeText("original") }
        val staged = object : File(temporaryFolder.root, "record.json.tmp") {
            override fun renameTo(dest: File): Boolean = false
        }.apply { writeText("replacement") }

        replaceFileAtomically(staged, target)

        assertEquals("replacement", target.readText())
        assertFalse(staged.exists())
    }

    @Test
    fun missingStagedFileLeavesExistingDestinationUntouched() {
        val target = temporaryFolder.newFile("record.json").apply { writeText("original") }
        val missing = File(temporaryFolder.root, "missing.tmp")

        assertThrows(IOException::class.java) { replaceFileAtomically(missing, target) }

        assertEquals("original", target.readText())
        assertFalse(missing.exists())
    }

    @Test
    fun invalidStagingLocationIsRejectedWithoutChangingEitherFile() {
        val target = temporaryFolder.newFile("record.json").apply { writeText("original") }
        val staged = temporaryFolder.newFolder("other").resolve("record.tmp").apply { writeText("replacement") }

        assertThrows(IOException::class.java) { replaceFileAtomically(staged, target) }
        assertThrows(IOException::class.java) { replaceFileAtomically(target, target) }

        assertEquals("original", target.readText())
        assertEquals("replacement", staged.readText())
    }
}
