package com.dongholab.pagetuner.source.wtr

import java.io.IOException
import java.net.URI

/** The public reader's signed content location is bound to one verified chapter and language. */
object WtrLabReaderContent {
    private const val PATH_PREFIX = "/api/v2/reader/content/"

    fun validateUrl(value: String, readerUrl: String, novelId: Long, chapterId: Long, language: String): String {
        val reader = safeUri(readerUrl)
        val location = safeUri(value)
        // The public reader returns a root-relative path. Resolve only that form;
        // never let a network-path reference or normalization change its authority/identity.
        val target = if (value.startsWith("/") && !value.startsWith("//") &&
            location.rawAuthority == null && location.normalize().rawPath == location.rawPath) {
            reader.resolve(location)
        } else location
        val signature = "[A-Za-z0-9_-][A-Za-z0-9._~-]{0,1023}"
        // Content hash, revision, expiry and signature are opaque provider values,
        // following the already checked book/chapter/language identity.
        val contentVersion = "[a-fA-F0-9]{32}/[1-9][0-9]{0,9}/[1-9][0-9]{0,12}"
        val expectedPath = Regex("^${PATH_PREFIX}v1/ai/$novelId/$chapterId/${Regex.escape(language)}/$contentVersion/$signature$")
        if (novelId <= 0 || chapterId <= 0 || !Regex("[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*").matches(language) ||
            !publicWtrOrigin(reader) || !publicWtrOrigin(target) ||
            !reader.host.equals(target.host, ignoreCase = true) ||
            target.rawQuery != null || target.rawFragment != null ||
            !expectedPath.matches(target.rawPath.orEmpty()) || target.normalize().rawPath != target.rawPath) {
            throw IOException("WTR-LAB reader content URL did not match the requested chapter and language.")
        }
        return target.toASCIIString()
    }

    /** Also recognizes malformed locations so transport diagnostics cannot reveal their signatures. */
    fun isContentUrl(value: String): Boolean = value.contains(PATH_PREFIX, ignoreCase = true)

    fun diagnosticTarget(value: String): String? =
        if (isContentUrl(value)) "wtr-lab.com${PATH_PREFIX}[redacted]" else null

    private fun safeUri(value: String): URI {
        if (value.length > 4_096 || value.any(Char::isISOControl) || '\\' in value) {
            throw IOException("Invalid WTR-LAB reader content location.")
        }
        return try {
            URI(value)
        } catch (_: Exception) {
            // URI parser exceptions include their input, which contains a signed path.
            throw IOException("Invalid WTR-LAB reader content location.")
        }
    }

    private fun publicWtrOrigin(uri: URI): Boolean = uri.scheme == "https" &&
        uri.host.orEmpty().equals("wtr-lab.com", ignoreCase = true) && uri.port in setOf(-1, 443) &&
        uri.rawUserInfo == null
}
