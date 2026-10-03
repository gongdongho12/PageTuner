package com.dongholab.pagetuner.server.workflow

import com.dongholab.pagetuner.core.content.StableContentHash
import com.dongholab.pagetuner.translation.glossary.GlossaryTermKind
import com.dongholab.pagetuner.translation.glossary.GlossaryTextProcessor
import com.dongholab.pagetuner.translation.glossary.BookGlossary
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class WorkflowGlossaryCompatibilityTest {
    private val json = jacksonObjectMapper()
    private val chapter = StoredChapter(UUID.fromString("00000000-0000-0000-0000-000000000001"), "source", "book", "Book", "",
        "chapter", "Chapter", "", "en", "revision", listOf(SourceParagraph("p1", 0, "Alice visits City.")), Instant.EPOCH)
    private val oldEntries = listOf(WorkflowGlossaryEntry("Alice", "앨리스"), WorkflowGlossaryEntry("City", "도시"))

    private fun resolve(entries: List<WorkflowGlossaryEntry>): JobConfiguration = WorkflowProviders().resolve(
        CreateTranslationJobRequest(chapter.recordId, "GOOGLE_WEB_TRANSLATE_HTML", "ko", UUID.randomUUID(), glossary = entries), chapter,
    ).first

    @Test fun `omitted and explicit default fields retain the legacy job JSON and idempotency hash`() {
        val legacy = resolve(oldEntries)
        val explicit = resolve(oldEntries.map { it.copy(source = " ${it.source} ", kind = "Character", displayTerm = "  ", caseSensitive = false, enabled = true) })
        assertEquals("[{\"source\":\"Alice\",\"target\":\"앨리스\"},{\"source\":\"City\",\"target\":\"도시\"}]", json.writeValueAsString(legacy.glossary))
        assertEquals(legacy, explicit)
        val legacyConfiguration = json.writeValueAsString(legacy)
        assertEquals(legacy, json.readValue<JobConfiguration>(legacyConfiguration))
        fun requestHash(config: JobConfiguration) = StableContentHash.sha256(json.writeValueAsString(listOf(chapter.recordId.toString(), chapter.sourceRevision, config)))
        assertEquals(requestHash(legacy), requestHash(explicit))
        assertEquals("e8fb496c68249c6e", legacy.glossary("book")!!.translationFingerprint) // Shared browser fixture.
        assertEquals("protected-terms-v1:e8fb496c68249c6e", legacy.glossaryRevision)
    }

    @Test fun `new display options round trip without changing the translation fingerprint or stored terms`() {
        val legacy = resolve(oldEntries)
        val display = resolve(oldEntries.map { it.copy(kind = "Place", displayTerm = " 화면 이름 ") })
        assertEquals(legacy.glossaryRevision, display.glossaryRevision)
        assertEquals(display, json.readValue<JobConfiguration>(json.writeValueAsString(display)))
        assertEquals(oldEntries.map { it.source to it.target }, display.glossary.map { it.source to it.target })
        val runtime = display.glossary("book")!!
        assertTrue(runtime.entries.all { it.kind == GlossaryTermKind.Place && it.displayTerm == "화면 이름" })
        assertEquals("화면 이름 visits 화면 이름.", GlossaryTextProcessor.applyOriginalDisplayAliases("Alice visits City.", runtime.entries))
    }

    @Test fun `matching changes affect translation while disabled terms are excluded`() {
        val legacy = resolve(oldEntries)
        val sensitive = resolve(oldEntries.map { it.copy(caseSensitive = true) })
        assertNotEquals(legacy.glossaryRevision, sensitive.glossaryRevision)
        assertEquals("alice", GlossaryTextProcessor.protect("alice", sensitive.glossary("book")!!.entries).text)
        assertNotEquals("alice", GlossaryTextProcessor.protect("alice", legacy.glossary("book")!!.entries).text)
        assertEquals(legacy.glossaryRevision, resolve(oldEntries + WorkflowGlossaryEntry("Ignored", "무시", enabled = false)).glossaryRevision)
        val disabled = resolve(oldEntries.map { it.copy(enabled = false) })
        assertEquals("", disabled.glossaryRevision)
        assertEquals(emptyList<Any>(), disabled.glossary("book")!!.activeEntries)
        assertTrue(json.readValue<JobConfiguration>(json.writeValueAsString(disabled)).glossary.all { it.enabled == false })
    }

    @Test fun `invalid kinds oversized aliases and duplicate source terms are rejected before queuing`() {
        for (entries in listOf(
            listOf(WorkflowGlossaryEntry("Alice", "앨리스", kind = "Unknown")),
            listOf(WorkflowGlossaryEntry("Alice", "앨리스", displayTerm = "x".repeat(201))),
            listOf(WorkflowGlossaryEntry("Alice", "앨리스"), WorkflowGlossaryEntry("ALICE", "다른 이름", caseSensitive = true)),
        )) assertThrows(IllegalArgumentException::class.java) { resolve(entries) }
    }

    @Test fun `explicit original IDs preserve full fields order and Android translation fingerprint`() {
        val input = listOf(
            WorkflowGlossaryEntry(" City ", " 都市 ", "Place", " 마을 ", true, true, "android-existing-z"),
            WorkflowGlossaryEntry("Alice", "앨리스", "Character", "아리", false, true, "android-existing-a"),
            WorkflowGlossaryEntry("Ignored", "무시", "Term", "", false, false, "disabled-original"),
        )
        val config = resolve(input)
        assertEquals(input, config.glossary)
        val runtime = config.glossary("book")!!
        assertEquals(input.map { it.id }, runtime.entries.map { it.id })
        assertEquals(" City ", runtime.entries.first().sourceTerm)
        assertFalse(runtime.entries.last().enabled)
        val android = BookGlossary("book", input.map {
            BookGlossaryEntry(it.id!!, it.source, it.target, it.displayTerm!!, GlossaryTermKind.valueOf(it.kind!!), it.caseSensitive!!, it.enabled!!)
        })
        assertEquals(android, runtime)
        assertEquals(android.translationFingerprint, runtime.translationFingerprint)
        assertEquals(config, json.readValue<JobConfiguration>(json.writeValueAsString(config)))
        val displayOnly = resolve(input.map { it.copy(kind = "Term", displayTerm = " 다른 표시 ") })
        assertEquals(config.glossaryRevision, displayOnly.glossaryRevision)
        assertNotEquals(config.glossaryRevision, resolve(input.map { it.copy(caseSensitive = false) }).glossaryRevision)
    }

    @Test fun `duplicate sources require explicit distinct IDs and cannot collide with a legacy effective ID`() {
        val explicit = listOf(WorkflowGlossaryEntry("Alice", "A", id = "explicit-one"), WorkflowGlossaryEntry("alice", "B", id = "explicit-two"))
        assertEquals(explicit, resolve(explicit).glossary)
        for (bad in listOf(
            listOf(explicit[0], explicit[1].copy(id = null)),
            listOf(explicit[0], explicit[1].copy(id = explicit[0].id)),
            listOf(WorkflowGlossaryEntry("Other", "B", id = StableContentHash.sha256("alice").take(24)), WorkflowGlossaryEntry("Alice", "A")),
            listOf(explicit[0].copy(id = " boundary")), listOf(explicit[0].copy(id = "\ud800")),
            listOf(explicit[0].copy(source = "\u00a0\ufeff")),
            List(201) { WorkflowGlossaryEntry("Source $it", "Target $it", id = "id-$it") },
        )) assertThrows(IllegalArgumentException::class.java) { resolve(bad) }
    }
}
