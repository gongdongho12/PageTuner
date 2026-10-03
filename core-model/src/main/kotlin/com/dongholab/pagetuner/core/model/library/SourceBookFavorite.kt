package com.dongholab.pagetuner.core.model.library

import java.net.URI

/** Display metadata only. Identity always comes from the provider's original IDs. */
data class SourceBookFavoriteMetadata(
    val title: String,
    val authors: List<String>,
    val language: String,
    val url: String,
)

object SourceBookFavoriteValidation {
    fun validateIdentity(providerId: String, bookId: String) {
        require(canonical(providerId, 100) && canonical(bookId, 500)) { "Invalid source book identity." }
    }

    fun validateMetadata(metadata: SourceBookFavoriteMetadata) {
        require(canonical(metadata.title, 500) && metadata.authors.size <= 20 &&
            metadata.authors.all { canonical(it, 200) } && canonical(metadata.language, 35)) {
            "Invalid source book metadata."
        }
        val url = metadata.url
        require(url.length in 1..2048 && url.all { it.code in 33..126 && it != '\\' }) { "Invalid source book URL." }
        val parsed = try { URI(url) } catch (_: Exception) { throw IllegalArgumentException("Invalid source book URL.") }
        require(parsed.scheme?.lowercase() in setOf("http", "https") && !parsed.host.isNullOrEmpty() &&
            parsed.rawUserInfo == null && '%' !in parsed.rawAuthority.orEmpty() && parsed.port in -1..65535 &&
            portableHost(parsed.host)) {
            "Invalid source book URL."
        }
    }

    /** URI and browser URL parsing disagree on numeric hosts; accept only their unambiguous subset. */
    private fun portableHost(host: String): Boolean {
        if (host.startsWith('[')) return host.endsWith(']') &&
            host.substring(1, host.length - 1).all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' || it == ':' }
        val name = host.removeSuffix(".")
        val labels = name.split('.')
        if (name.length > 253 || labels.any { !DnsLabel.matches(it) }) return false
        val last = labels.last()
        val numeric = last.all { it in '0'..'9' } || HexNumber.matches(last)
        if (numeric) return !host.endsWith('.') && labels.size == 4 && labels.all {
            DecimalOctet.matches(it) && it.toInt() <= 255
        }
        return labels.size == 1 || last.first().lowercaseChar() in 'a'..'z'
    }

    private val DnsLabel = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
    private val HexNumber = Regex("0[xX][0-9a-fA-F]*")
    private val DecimalOctet = Regex("0|[1-9][0-9]{0,2}")

    private fun canonical(value: String, maximum: Int): Boolean {
        if (value.length !in 1..maximum || value.any(Char::isISOControl) || LibraryFilter.trim(value) != value) return false
        var offset = 0
        while (offset < value.length) {
            val character = value[offset++]
            if (Character.isHighSurrogate(character)) {
                if (offset == value.length || !Character.isLowSurrogate(value[offset++])) return false
            } else if (Character.isLowSurrogate(character)) return false
        }
        return true
    }
}
