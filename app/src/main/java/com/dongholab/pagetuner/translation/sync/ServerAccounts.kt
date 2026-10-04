package com.dongholab.pagetuner.translation.sync

import java.util.Locale
import java.util.UUID
import org.json.JSONObject

data class ServerAccountProfile(
    val accountId: String,
    val username: String,
    val displayName: String,
    val locale: String,
    val targetLanguage: String,
    val effectiveLocale: String,
) {
    fun draft() = ServerAccountDraft(displayName, locale, targetLanguage)
}

data class ServerAccountDraft(val displayName: String = "", val locale: String = "ko", val targetLanguage: String = "ko")
data class ServerAccountLanguage(val tag: String, val nativeName: String, val displayName: String, val available: Boolean, val fallbackTag: String)
data class ServerAccountLanguages(val items: List<ServerAccountLanguage>, val defaultTag: String)

enum class ServerAccountInputField { Username, Password, CurrentPassword, PasswordUnchanged, PasswordConfirmation, DisplayName, Locale, TargetLanguage }
class ServerAccountInputException(val field: ServerAccountInputField) : IllegalArgumentException("Invalid account field: $field")

internal object ServerAccountJson {
    fun validateUsername(value: String) {
        if (!value.matches(Regex("[a-z0-9][a-z0-9_.-]{2,39}"))) throw ServerAccountInputException(ServerAccountInputField.Username)
    }
    fun validatePassword(value: String) {
        if (value.codePointCount(0, value.length) < 10 || value.toByteArray(Charsets.UTF_8).size > 72 || value.any(Char::isISOControl)) {
            throw ServerAccountInputException(ServerAccountInputField.Password)
        }
    }
    fun validate(draft: ServerAccountDraft) {
        if (draft.displayName.trim().length !in 1..80 || draft.displayName.any(Char::isISOControl)) throw ServerAccountInputException(ServerAccountInputField.DisplayName)
        if (canonicalLanguageTag(draft.locale, 35) == null) throw ServerAccountInputException(ServerAccountInputField.Locale)
        if (canonicalLanguageTag(draft.targetLanguage, 24) == null || draft.targetLanguage.trim().equals("auto", true)) throw ServerAccountInputException(ServerAccountInputField.TargetLanguage)
    }
    fun encode(draft: ServerAccountDraft): JSONObject {
        validate(draft)
        return JSONObject().put("displayName", draft.displayName.trim()).put("locale", canonicalLanguageTag(draft.locale, 35))
            .put("targetLanguage", canonicalLanguageTag(draft.targetLanguage, 24))
    }
    fun profile(json: JSONObject): ServerAccountProfile {
        val profile = ServerAccountProfile(json.string("accountId"), json.string("username"), json.string("displayName"),
            json.string("locale"), json.string("targetLanguage"), json.string("effectiveLocale"))
        require(UUID.fromString(profile.accountId).toString() == profile.accountId)
        validateUsername(profile.username)
        validate(profile.draft())
        require(validLanguageTag(profile.effectiveLocale, 35))
        return profile
    }
    fun languages(json: JSONObject): ServerAccountLanguages {
        val defaultTag = json.string("defaultTag").also { require(validLanguageTag(it, 35)) }
        val values = json.getJSONArray("items")
        require(values.length() in 1..500)
        val items = List(values.length()) { index ->
            val item = values.getJSONObject(index)
            ServerAccountLanguage(item.string("tag"), item.string("nativeName"), item.string("displayName"),
                item.get("available") as Boolean, item.string("fallbackTag")).also {
                require(validLanguageTag(it.tag, 35) && validLanguageTag(it.fallbackTag, 35))
                require(it.nativeName.length <= 100 && it.displayName.length <= 100)
            }
        }
        require(items.map { it.tag.lowercase() }.distinct().size == items.size)
        return ServerAccountLanguages(items, defaultTag)
    }
    private fun JSONObject.string(key: String) = (get(key) as? String)?.takeIf(String::isNotBlank) ?: error("Invalid account response")
    private fun validLanguageTag(value: String, limit: Int): Boolean {
        return canonicalLanguageTag(value, limit) != null
    }
    fun canonicalLanguageTag(value: String, limit: Int): String? {
        val input = value.trim()
        if (input.length !in 2..limit || !input.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) return null
        return runCatching { Locale.Builder().setLanguageTag(input).build() }.getOrNull()
            ?.takeIf { it.language.isNotBlank() && it.language != "und" }?.toLanguageTag()?.takeIf { it.length <= limit }
    }
}
