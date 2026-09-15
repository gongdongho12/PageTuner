package com.dongholab.pagetuner.server.account

import java.util.Locale
import java.util.UUID

class RegisterAccountRequest(val username: String, val password: String, val displayName: String,
    val locale: String = "ko", val targetLanguage: String = "ko") {
    override fun toString() = "RegisterAccountRequest(credentials=REDACTED)"
}
class ChangePasswordRequest(val currentPassword: String, val newPassword: String) {
    override fun toString() = "ChangePasswordRequest(credentials=REDACTED)"
}
data class UpdateAccountRequest(val displayName: String, val locale: String, val targetLanguage: String)
data class AccountProfile(val accountId: UUID, val username: String, val displayName: String,
    val locale: String, val targetLanguage: String) {
    val effectiveLocale: String = LanguageCatalog.effective(locale)
}
data class AccountLanguage(val tag: String, val nativeName: String, val displayName: String,
    val available: Boolean, val fallbackTag: String)
data class AccountLanguages(val items: List<AccountLanguage>, val defaultTag: String = "ko")

/** Language preferences remain valid before the corresponding UI pack is shipped. */
object LanguageCatalog {
    val supportedPacks = setOf("ko", "en")
    fun normalize(value: String, maxLength: Int = 35): String {
        val input = value.trim()
        require(input.length in 2..maxLength && input.matches(Regex("[A-Za-z]{2,8}(-[A-Za-z0-9]{1,8})*"))) { "Invalid language tag." }
        val parsed = runCatching { Locale.Builder().setLanguageTag(input).build() }.getOrNull()
        require(parsed != null && parsed.language.isNotBlank() && parsed.language != "und") { "Invalid language tag." }
        val normalized = parsed.toLanguageTag()
        require(normalized.length <= maxLength) { "Language tag is too long." }
        return normalized
    }
    fun effective(tag: String): String = Locale.forLanguageTag(tag).language.takeIf { it in supportedPacks } ?: "en"
    fun list() = AccountLanguages(listOf(
        Triple("ko", "한국어", "Korean"), Triple("en", "English", "English"), Triple("ja", "日本語", "Japanese"),
        Triple("zh-Hans", "简体中文", "Chinese (Simplified)"), Triple("zh-Hant", "繁體中文", "Chinese (Traditional)"),
        Triple("es", "Español", "Spanish"), Triple("fr", "Français", "French"), Triple("de", "Deutsch", "German"),
        Triple("pt", "Português", "Portuguese"), Triple("ru", "Русский", "Russian"), Triple("ar", "العربية", "Arabic"),
        Triple("hi", "हिन्दी", "Hindi"), Triple("vi", "Tiếng Việt", "Vietnamese"), Triple("id", "Bahasa Indonesia", "Indonesian"),
    ).map { (tag, native, display) -> AccountLanguage(tag, native, display, tag in supportedPacks, effective(tag)) })
}

class AccountFailure(val code: String, val status: Int, message: String) : RuntimeException(message)
