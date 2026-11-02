package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.core.content.StableContentHash
import java.util.UUID
import org.json.JSONObject

/** Detached journal state with its original lookup/UUID spelling, never a migrated row. */
data class ReadingProgressJournalObservation(val target: ServerReadingTarget, val record: DeviceReadingProgress)

/** A historical UUID may differ in case, but must retain the complete canonical UUID shape. */
private fun canonicalObservedUuid(value: String): String {
    require(value.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")))
    return UUID.fromString(value).toString().also { require(it.equals(value, ignoreCase = true)) }
}

/** Preserve Number types and nested values; serializing/reparsing could coerce 1.0 into 1. */
private fun JSONObject.observationCopy() = JSONObject().also { copy ->
    keys().asSequence().forEach { key -> copy.put(key, get(key)) }
}

internal fun decodeReadingProgressObservation(value: JSONObject, fileName: String): ReadingProgressJournalObservation {
    val required = setOf("format", "key", "remote", "pending", "queuedAnchor", "conflict")
    val fields = value.keys().asSequence().toSet()
    require(fields.containsAll(required) && fields.all { it in required || it == "retryAfterUntil" })
    val pieces = (value.get("key") as String).split(':')
    require(pieces.size == 3 && pieces[0].matches(Regex("[a-f0-9]{64}")))
    val rawTarget = ServerReadingTarget(pieces[0], pieces[1], pieces[2])
    val canonicalTarget = rawTarget.copy(recordId = canonicalObservedUuid(rawTarget.recordId))
    ServerReadingProgressJson.validateTarget(canonicalTarget.kind, canonicalTarget.recordId)
    require(fileName == StableContentHash.sha256(rawTarget.key) + ".json")

    val rawViewIds = mutableMapOf<String, String>()
    val captured = value.observationCopy().put("key", canonicalTarget.key)
    for (name in listOf("remote", "conflict")) {
        val raw = value.get(name)
        if (raw == JSONObject.NULL) continue
        require(raw is JSONObject)
        val recordId = raw.get("recordId") as String
        require(raw.get("kind") == rawTarget.kind && canonicalObservedUuid(recordId) == canonicalTarget.recordId)
        rawViewIds[name] = recordId
        captured.put(name, raw.observationCopy().put("recordId", canonicalTarget.recordId))
    }
    // Only temporary scope fields differ. Existing strict nested validators still inspect the
    // original numbers, anchor IDs, mutations and optional legacy retry deadline without coercion.
    val record = decodeDeviceReadingProgress(captured, canonicalTarget)
    return ReadingProgressJournalObservation(rawTarget, record.copy(
        remote = record.remote?.copy(recordId = requireNotNull(rawViewIds["remote"])),
        conflict = record.conflict?.copy(recordId = requireNotNull(rawViewIds["conflict"])),
    ))
}
