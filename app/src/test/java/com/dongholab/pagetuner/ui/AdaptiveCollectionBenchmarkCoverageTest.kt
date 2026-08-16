package com.dongholab.pagetuner.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the screen benchmark inventory synchronized with production collection call sites. */
class AdaptiveCollectionBenchmarkCoverageTest {
    @Test
    fun everyProductionAdaptiveCollectionCallSiteHasABenchmarkFixture() {
        val sourceRoot = sequenceOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull(File::isDirectory)
        assertTrue("Unable to locate the main Kotlin source directory", sourceRoot != null)

        val actual = requireNotNull(sourceRoot)
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.endsWith("/ui/common/AdaptiveCollection.kt") }
            .mapNotNull { source ->
                val count = AdaptiveCollectionCall.findAll(source.readText()).count()
                count.takeIf { it > 0 }?.let {
                    source.relativeTo(sourceRoot).invariantSeparatorsPath to count
                }
            }
            .toMap()

        assertEquals(ExpectedCallSites, actual)
        assertEquals(11, actual.values.sum())
    }

    private companion object {
        val AdaptiveCollectionCall = Regex("""\bAdaptiveCollection\s*\(""")
        val ExpectedCallSites = mapOf(
            "com/dongholab/pagetuner/ui/library/LocalDirectoryBrowserPanel.kt" to 1,
            "com/dongholab/pagetuner/ui/library/LocalLibraryPanel.kt" to 1,
            "com/dongholab/pagetuner/ui/reader/ReaderUi.kt" to 2,
            "com/dongholab/pagetuner/ui/source/FavoritesPanel.kt" to 1,
            "com/dongholab/pagetuner/ui/source/RemoteSourcesTodoPanel.kt" to 2,
            "com/dongholab/pagetuner/ui/source/WebCatalogPagePanel.kt" to 1,
            "com/dongholab/pagetuner/ui/source/WebNovelDetailDialog.kt" to 1,
            "com/dongholab/pagetuner/ui/source/WebNovelDetailPagePanel.kt" to 1,
            "com/dongholab/pagetuner/ui/translation/BookGlossaryPanel.kt" to 1,
        )
    }
}
