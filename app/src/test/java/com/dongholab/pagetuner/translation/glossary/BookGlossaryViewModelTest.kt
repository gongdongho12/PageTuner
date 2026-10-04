package com.dongholab.pagetuner.translation.glossary

import androidx.lifecycle.ViewModelStore
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BookGlossaryViewModelTest {
    @Test fun lateAliasCallbackCannotEditAnotherBookAndCorruptionIsVisible() = runTest(timeout = 10.seconds) {
        val directory = Files.createTempDirectory("pageturner-glossary-vm").toFile()
        val viewModels = ViewModelStore()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val store = BookGlossaryStore(directory)
            store.save(BookGlossary("first", listOf(BookGlossaryEntry("first-entry", "A", "Alpha"))))
            store.save(BookGlossary("second", listOf(BookGlossaryEntry("second-entry", "B", "Beta"))))
            val model = BookGlossaryViewModel(store); viewModels.put("glossary", model)
            model.selectBook("first"); model.uiState.first { it.glossary?.bookId == "first" }
            model.selectBook("second"); val second = model.uiState.first { it.glossary?.bookId == "second" }
            model.mergeLlmCharacterAliases("first", listOf(CharacterAliasSuggestion("Late", "Wrong")))
            runCurrent()
            assertEquals(second.glossary, model.uiState.value.glossary)
            assertEquals(second.glossary, store.load("second"))
            val file = directory.resolve(com.dongholab.pagetuner.document.DocumentIds.sha256("broken").take(32) + ".json")
            file.writeText("invalid json")
            model.selectBook("broken")
            val failed = model.uiState.first { it.error != null }
            assertNull(failed.glossary)
            model.upsert(BookGlossaryEntry("new", "New", "New")); runCurrent()
            assertEquals("invalid json", file.readText())
        } finally {
            viewModels.clear(); Dispatchers.resetMain(); directory.deleteRecursively()
        }
    }
}
