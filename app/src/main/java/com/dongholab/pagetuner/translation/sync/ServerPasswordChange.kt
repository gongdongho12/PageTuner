package com.dongholab.pagetuner.translation.sync

import java.io.IOException
import org.json.JSONObject

/** Password fields live only in the account screen's ViewModel, never saved state or preferences. */
data class ServerPasswordChangeDraft(
    val currentPassword: String = "",
    val newPassword: String = "",
    val confirmation: String = "",
) {
    override fun toString() = "ServerPasswordChangeDraft(passwords=REDACTED)"

    fun validate() {
        validateCurrentPassword(currentPassword)
        ServerAccountJson.validatePassword(newPassword)
        if (newPassword == currentPassword) throw ServerAccountInputException(ServerAccountInputField.PasswordUnchanged)
        if (newPassword != confirmation) throw ServerAccountInputException(ServerAccountInputField.PasswordConfirmation)
    }
}

internal fun validateCurrentPassword(value: String) {
    if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > 72 || value.any(Char::isISOControl)) {
        throw ServerAccountInputException(ServerAccountInputField.CurrentPassword)
    }
}

enum class ServerPasswordChangeFailure(val status: Int) {
    CURRENT_PASSWORD_INCORRECT(400), PASSWORD_UNCHANGED(400), INVALID_PASSWORD(400),
    PASSWORD_CHANGE_LIMIT(429), PASSWORD_CHANGE_CONFLICT(409),
}

/** Only recognized codes are retained; server response messages may contain sensitive input. */
class ServerPasswordChangeException(val failure: ServerPasswordChangeFailure) :
    IOException("Account password change failed: ${failure.name}")

internal fun passwordChangeFailure(response: TranslationStoreHttpResponse): ServerPasswordChangeFailure? {
    if (response.body.length > 4_096) return null
    val code = runCatching { JSONObject(response.body).get("code") as? String }.getOrNull() ?: return null
    return ServerPasswordChangeFailure.entries.firstOrNull { it.status == response.status && it.name == code }
}
