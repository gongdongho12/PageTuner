@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.TranslationLanguageOption
import com.dongholab.pagetuner.ui.theme.EinkInk

@Composable
fun TranslationLanguageSelectorRows(
    sourceLanguage: String,
    onSourceLanguageChange: (String) -> Unit,
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    busy: Boolean,
) {
    TranslationLanguageOptionRow(
        title = stringResource(R.string.language_source_title),
        options = TranslationLanguageOption.entries.filter { option -> option.canBeSource },
        selectedCode = sourceLanguage,
        busy = busy,
        onSelect = onSourceLanguageChange,
    )
    TranslationLanguageOptionRow(
        title = stringResource(R.string.language_target_title),
        options = TranslationLanguageOption.entries.filter { option -> option.canBeTarget },
        selectedCode = targetLanguage,
        busy = busy,
        onSelect = onTargetLanguageChange,
    )
}

@Composable
private fun TranslationLanguageOptionRow(
    title: String,
    options: List<TranslationLanguageOption>,
    selectedCode: String,
    busy: Boolean,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = EinkInk,
            modifier = Modifier.padding(top = 8.dp),
        )
        options.forEach { option ->
            FilterChip(
                selected = selectedCode.equals(option.code, ignoreCase = true),
                onClick = { onSelect(option.code) },
                enabled = !busy,
                label = { Text(stringResource(option.labelRes)) },
            )
        }
    }
}
