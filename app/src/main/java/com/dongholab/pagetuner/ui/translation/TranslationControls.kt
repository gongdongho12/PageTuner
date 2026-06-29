@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.TranslationPaceMode
import com.dongholab.pagetuner.translation.TranslationProviderKind
import com.dongholab.pagetuner.ui.LanguagePreset
import com.dongholab.pagetuner.ui.text.apiKeyLabelRes
import com.dongholab.pagetuner.ui.text.localizedLabel
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkPaper

@Composable
fun TranslationControls(
    providerKind: TranslationProviderKind,
    onProviderKindChange: (TranslationProviderKind) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    llmEndpoint: String,
    onLlmEndpointChange: (String) -> Unit,
    llmModel: String,
    onLlmModelChange: (String) -> Unit,
    sourceLanguage: String,
    onSourceLanguageChange: (String) -> Unit,
    targetLanguage: String,
    onTargetLanguageChange: (String) -> Unit,
    readingWpm: Float,
    onReadingWpmChange: (Float) -> Unit,
    paceMode: TranslationPaceMode,
    onPaceModeChange: (TranslationPaceMode) -> Unit,
    busy: Boolean,
    canTranslate: Boolean,
    onLanguagePreset: (LanguagePreset) -> Unit,
    onTranslate: () -> Unit,
    onPrefetch: () -> Unit,
    onLoadCached: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TranslationProviderKind.entries.forEach { kind ->
                    FilterChip(
                        selected = providerKind == kind,
                        onClick = { onProviderKindChange(kind) },
                        enabled = !busy,
                        label = { Text(kind.localizedLabel()) },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = onApiKeyChange,
                    modifier = Modifier.weight(1.6f),
                    enabled = !busy,
                    label = { Text(stringResource(providerKind.apiKeyLabelRes)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = sourceLanguage,
                    onValueChange = onSourceLanguageChange,
                    modifier = Modifier.weight(0.8f),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.field_source_language)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = targetLanguage,
                    onValueChange = onTargetLanguageChange,
                    modifier = Modifier.weight(0.8f),
                    enabled = !busy,
                    label = { Text(stringResource(R.string.field_target_language)) },
                    singleLine = true,
                )
            }
            if (providerKind == TranslationProviderKind.OPENAI_COMPATIBLE_LLM) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = llmEndpoint,
                        onValueChange = onLlmEndpointChange,
                        modifier = Modifier.weight(1.4f),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.field_llm_endpoint)) },
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = llmModel,
                        onValueChange = onLlmModelChange,
                        modifier = Modifier.weight(0.9f),
                        enabled = !busy,
                        label = { Text(stringResource(R.string.field_llm_model)) },
                        singleLine = true,
                    )
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LanguagePreset.entries.forEach { preset ->
                    FilterChip(
                        selected = sourceLanguage == preset.sourceLanguage &&
                            targetLanguage == preset.targetLanguage,
                        onClick = { onLanguagePreset(preset) },
                        enabled = !busy,
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.reading_speed_wpm, readingWpm.toInt()),
                        style = MaterialTheme.typography.labelLarge,
                        color = EinkInk,
                    )
                    Slider(
                        value = readingWpm,
                        onValueChange = onReadingWpmChange,
                        valueRange = 120f..420f,
                        steps = 5,
                        enabled = !busy,
                    )
                }
                FlowRow(
                    modifier = Modifier.weight(1.2f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TranslationPaceMode.entries.forEach { mode ->
                        FilterChip(
                            selected = paceMode == mode,
                            onClick = { onPaceModeChange(mode) },
                            enabled = !busy,
                            label = { Text(mode.localizedLabel()) },
                        )
                    }
                }
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onTranslate,
                    enabled = !busy && canTranslate,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_translate_page))
                }
                Button(
                    onClick = onPrefetch,
                    enabled = !busy && canTranslate,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_prefetch_offline_cache))
                }
                TextButton(
                    onClick = onLoadCached,
                    enabled = !busy,
                ) {
                    Text(stringResource(R.string.action_load_offline_saved))
                }
            }
        }
    }
}
