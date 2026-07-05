package com.dongholab.pagetuner.translation

import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationProviderErrorsTest {
    @Test
    fun mapsHttpStatusCodesToProviderErrorKinds() {
        assertEquals(
            TranslationProviderErrorKind.Authentication,
            providerHttpException("Provider", 401, "bad key").failure.kind,
        )
        assertEquals(
            TranslationProviderErrorKind.RateLimited,
            providerHttpException("Provider", 429, "slow down").failure.kind,
        )
        assertEquals(
            TranslationProviderErrorKind.Server,
            providerHttpException("Provider", 503, "unavailable").failure.kind,
        )
    }

    @Test
    fun keepsProviderFailureAvailableFromException() {
        val failure = providerResponseFormatException(
            providerName = "Provider",
            detail = "Unexpected shape.",
        ).providerFailureOrNull()

        assertEquals("Provider", failure?.providerName)
        assertEquals(TranslationProviderErrorKind.ResponseFormat, failure?.kind)
        assertEquals("Unexpected shape.", failure?.detail)
    }
}
