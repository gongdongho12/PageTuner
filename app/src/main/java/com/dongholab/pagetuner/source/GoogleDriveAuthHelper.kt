package com.dongholab.pagetuner.source

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class GoogleDriveAuthState(
    val isAuthenticated: Boolean,
    val accountEmail: String? = null,
    val accessToken: String? = null,
    val errorMessage: String? = null,
)

object GoogleDriveAuthHelper {
    const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"

    suspend fun validateAccessToken(token: String): Boolean = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext false
        runCatching {
            val url = java.net.URL("https://www.googleapis.com/oauth2/v3/tokeninfo?access_token=$token")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            val code = connection.responseCode
            connection.disconnect()
            code == 200
        }.getOrDefault(false)
    }

    fun buildAuthUrl(clientId: String, redirectUri: String): String {
        return Uri.parse("https://accounts.google.com/o/oauth2/v2/auth")
            .buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "token")
            .appendQueryParameter("scope", DRIVE_READONLY_SCOPE)
            .build()
            .toString()
    }
}
