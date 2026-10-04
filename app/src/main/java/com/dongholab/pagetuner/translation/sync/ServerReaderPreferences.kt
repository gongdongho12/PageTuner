package com.dongholab.pagetuner.translation.sync

import com.dongholab.pagetuner.reader.PageTurnMode
import com.dongholab.pagetuner.settings.ListLayoutMode
import com.dongholab.pagetuner.settings.ReaderSettings
import java.util.UUID
import kotlin.math.roundToInt
import org.json.JSONObject

/** Only portable reader fields belong here; provider credentials and device display settings do not. */
data class SharedReaderPreferences(val fontSize: Int, val lineHeightPercent: Int, val pageMargin: Int,
    val touchDirection: String, val listMode: String) {
    fun overlay(device: ReaderSettings) = device.copy(readerFontSizeSp = fontSize,
        readerLineSpacing = lineHeightPercent / 100f, readerPageMarginDp = pageMargin,
        pageTurnMode = when (touchDirection) {
            "left-next" -> PageTurnMode.LeftNextRightPrevious
            "buttons-only" -> PageTurnMode.ButtonsOnly
            else -> PageTurnMode.LeftPreviousRightNext
        }, listLayoutMode = if (listMode == "scroll") ListLayoutMode.Scroll else ListLayoutMode.Paged)
}

fun ReaderSettings.sharedPreferences() = SharedReaderPreferences(readerFontSizeSp,
    (readerLineSpacing * 100).roundToInt(), readerPageMarginDp, when (pageTurnMode) {
        PageTurnMode.LeftPreviousRightNext -> "left-previous"
        PageTurnMode.LeftNextRightPrevious -> "left-next"
        PageTurnMode.ButtonsOnly -> "buttons-only"
    }, if (listLayoutMode == ListLayoutMode.Scroll) "scroll" else "paged")

/** Field intents are merged in the actor, so rapid changes to different controls never lose a field. */
data class ReaderPreferencesPatch(val fontSize: Int? = null, val lineHeightPercent: Int? = null,
    val pageMargin: Int? = null, val touchDirection: String? = null, val listMode: String? = null) {
    fun apply(value: SharedReaderPreferences) = value.copy(fontSize = fontSize ?: value.fontSize,
        lineHeightPercent = lineHeightPercent ?: value.lineHeightPercent, pageMargin = pageMargin ?: value.pageMargin,
        touchDirection = touchDirection ?: value.touchDirection, listMode = listMode ?: value.listMode)
        .also(ServerReaderPreferencesJson::validate)
}

data class ServerReaderPreferences(val version: Long, val preferences: SharedReaderPreferences?, val updatedAt: String?)
data class ReaderPreferencesMutation(val expectedVersion: Long, val mutationId: String, val preferences: SharedReaderPreferences)
class ReaderPreferencesConflict(val current: ServerReaderPreferences) : Exception("Reader preferences conflict")
class ReaderPreferencesRateLimited(val retryAfterSeconds: Long) : Exception("Reader preferences rate limited")

internal object ServerReaderPreferencesJson {
    fun validate(value: SharedReaderPreferences) {
        require(value.fontSize in 14..36 && value.lineHeightPercent in 110..240 && value.pageMargin in 0..48)
        require(value.touchDirection in setOf("left-previous", "left-next", "buttons-only") && value.listMode in setOf("paged", "scroll"))
    }
    fun preferences(value: JSONObject) = SharedReaderPreferences(number(value, "fontSize", 36).toInt(),
        number(value, "lineHeightPercent", 240).toInt(), number(value, "pageMargin", 48).toInt(),
        value.get("touchDirection") as String, value.get("listMode") as String).also(::validate)
    fun encode(value: SharedReaderPreferences): JSONObject {
        validate(value)
        return JSONObject().put("fontSize", value.fontSize).put("lineHeightPercent", value.lineHeightPercent)
            .put("pageMargin", value.pageMargin).put("touchDirection", value.touchDirection).put("listMode", value.listMode)
    }
    fun view(value: JSONObject): ServerReaderPreferences {
        val version = number(value, "version", MaxReadingVersion)
        val preferences = if (value.get("preferences") == JSONObject.NULL) null else preferences(value.getJSONObject("preferences"))
        val updated = if (value.get("updatedAt") == JSONObject.NULL) null else ServerReadingNotesJson.timestamp(value.get("updatedAt") as String)
        require(if (version == 0L) preferences == null && updated == null else preferences != null && updated != null)
        return ServerReaderPreferences(version, preferences, updated)
    }
    fun encode(value: ServerReaderPreferences) = JSONObject().put("version", value.version)
        .put("preferences", value.preferences?.let(::encode) ?: JSONObject.NULL).put("updatedAt", value.updatedAt ?: JSONObject.NULL)
        .also { view(it) }
    fun encode(value: ReaderPreferencesMutation): JSONObject {
        require(value.expectedVersion in 0 until MaxReadingVersion && UUID.fromString(value.mutationId).toString() == value.mutationId)
        return JSONObject().put("expectedVersion", value.expectedVersion).put("mutationId", value.mutationId).put("preferences", encode(value.preferences))
    }
    fun mutation(value: JSONObject) = ReaderPreferencesMutation(number(value, "expectedVersion", MaxReadingVersion - 1),
        value.get("mutationId") as String, preferences(value.getJSONObject("preferences"))).also { encode(it) }
    fun number(value: JSONObject, key: String, max: Long = MaxReadingVersion): Long {
        val number = value.get(key)
        require(number is Int || number is Long)
        return (number as Number).toLong().also { require(it in 0..max) }
    }
}
