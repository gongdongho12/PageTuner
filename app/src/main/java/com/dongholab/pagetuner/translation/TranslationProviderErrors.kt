package com.dongholab.pagetuner.translation

import java.io.IOException

enum class TranslationProviderErrorKind {
    Authentication,
    RateLimited,
    Quota,
    BadRequest,
    Server,
    Network,
    ResponseFormat,
    Configuration,
    Unknown,
}

data class TranslationProviderFailure(
    val providerName: String,
    val kind: TranslationProviderErrorKind,
    val detail: String?,
)

class TranslationProviderException(
    val failure: TranslationProviderFailure,
    cause: Throwable? = null,
) : IOException(failure.toExceptionMessage(), cause)

internal fun providerHttpException(
    providerName: String,
    statusCode: Int,
    responseBody: String,
): TranslationProviderException {
    return TranslationProviderException(
        failure = TranslationProviderFailure(
            providerName = providerName,
            kind = statusCode.toProviderErrorKind(),
            detail = "HTTP $statusCode ${responseBody.compactProviderDetail()}".trim(),
        ),
    )
}

internal fun providerResponseFormatException(
    providerName: String,
    detail: String,
    cause: Throwable? = null,
): TranslationProviderException {
    return TranslationProviderException(
        failure = TranslationProviderFailure(
            providerName = providerName,
            kind = TranslationProviderErrorKind.ResponseFormat,
            detail = detail,
        ),
        cause = cause,
    )
}

internal fun providerConfigurationException(
    providerName: String,
    detail: String,
): TranslationProviderException {
    return TranslationProviderException(
        failure = TranslationProviderFailure(
            providerName = providerName,
            kind = TranslationProviderErrorKind.Configuration,
            detail = detail,
        ),
    )
}

internal fun providerNetworkException(
    providerName: String,
    detail: String?,
    cause: Throwable? = null,
): TranslationProviderException {
    return TranslationProviderException(
        failure = TranslationProviderFailure(
            providerName = providerName,
            kind = TranslationProviderErrorKind.Network,
            detail = detail,
        ),
        cause = cause,
    )
}

internal fun Throwable.providerFailureOrNull(): TranslationProviderFailure? {
    return (this as? TranslationProviderException)?.failure
}

internal fun Throwable.asProviderNetworkFailure(providerName: String): Throwable {
    return when (this) {
        is TranslationProviderException -> this
        is IOException -> providerNetworkException(
            providerName = providerName,
            detail = message,
            cause = this,
        )
        else -> this
    }
}

private fun Int.toProviderErrorKind(): TranslationProviderErrorKind {
    return when (this) {
        400, 422 -> TranslationProviderErrorKind.BadRequest
        401, 403 -> TranslationProviderErrorKind.Authentication
        402 -> TranslationProviderErrorKind.Quota
        408, 425, 429 -> TranslationProviderErrorKind.RateLimited
        in 500..599 -> TranslationProviderErrorKind.Server
        else -> TranslationProviderErrorKind.Unknown
    }
}

private fun String.compactProviderDetail(): String {
    return lineSequence()
        .joinToString(separator = " ") { it.trim() }
        .replace(Regex("\\s+"), " ")
        .take(500)
}

private fun TranslationProviderFailure.toExceptionMessage(): String {
    val safeDetail = detail?.takeIf { it.isNotBlank() } ?: kind.name
    return "$providerName provider error: $safeDetail"
}
