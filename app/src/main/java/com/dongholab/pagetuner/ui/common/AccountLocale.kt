package com.dongholab.pagetuner.ui.common

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import java.util.Locale

/** Locale affects resource lookup only; reader translation settings and credentials are untouched. */
@Composable
fun AccountLocale(locale: String?, content: @Composable () -> Unit) {
    if (locale == null) { content(); return }
    val base = LocalContext.current
    val currentConfiguration = LocalConfiguration.current
    val language = Locale.forLanguageTag(locale).language.takeIf { it in setOf("ko", "en") } ?: "en"
    val localized = remember(base, currentConfiguration, language) {
        val configuration = Configuration(currentConfiguration).apply { setLocale(Locale.forLanguageTag(language)) }
        AccountLocaleContext(base, base.createConfigurationContext(configuration).resources)
    }
    CompositionLocalProvider(
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
        LocalResources provides localized.resources,
    ) { content() }
}

/** Preserve Activity-backed operations such as share intents while changing resource lookup. */
private class AccountLocaleContext(base: Context, private val localizedResources: Resources) : ContextWrapper(base) {
    override fun getResources() = localizedResources
    override fun getAssets() = localizedResources.assets
}
