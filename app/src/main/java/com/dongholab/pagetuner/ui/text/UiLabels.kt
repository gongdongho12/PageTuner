package com.dongholab.pagetuner.ui.text

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.display.DisplayMode
import com.dongholab.pagetuner.document.DocumentFormat
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind

@Composable
fun DocumentFormat.localizedName(): String {
    return stringResource(
        when (this) {
            DocumentFormat.TEXT -> R.string.format_text
            DocumentFormat.MARKDOWN -> R.string.format_markdown
            DocumentFormat.PDF -> R.string.format_pdf
            DocumentFormat.EPUB -> R.string.format_epub
        },
    )
}

@Composable
fun TranslationPaceMode.localizedLabel(): String {
    return stringResource(labelRes)
}

fun TranslationPaceMode.localizedLabel(context: Context): String {
    return context.getString(labelRes)
}

val TranslationPaceMode.labelRes: Int
    @StringRes get() = when (this) {
        TranslationPaceMode.READING -> R.string.pace_reading
        TranslationPaceMode.FAST -> R.string.pace_fast
        TranslationPaceMode.OFFLINE_PREFETCH -> R.string.pace_offline_prefetch
    }

@Composable
fun TranslationProviderKind.localizedLabel(): String {
    return stringResource(labelRes)
}

val TranslationProviderKind.labelRes: Int
    @StringRes get() = when (this) {
        TranslationProviderKind.GOOGLE_CLOUD -> R.string.provider_google_cloud
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> R.string.provider_openai_compatible_llm
    }

val TranslationProviderKind.apiKeyLabelRes: Int
    @StringRes get() = when (this) {
        TranslationProviderKind.GOOGLE_CLOUD -> R.string.field_google_api_key
        TranslationProviderKind.OPENAI_COMPATIBLE_LLM -> R.string.field_llm_api_key
    }

@Composable
fun DisplayMode.localizedLabel(): String {
    return stringResource(labelRes)
}
