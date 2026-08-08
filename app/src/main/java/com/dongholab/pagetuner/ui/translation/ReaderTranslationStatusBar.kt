package com.dongholab.pagetuner.ui.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

/**
 * Persistent recovery action for a reader page whose translation is not visible.
 *
 * Translation errors must remain actionable after the busy indicator disappears,
 * especially on E-Ink displays where transient animation or snackbars are unreliable.
 */
@Composable
fun ReaderTranslationStatusBar(
    targetLanguage: String,
    providerConfigured: Boolean,
    hasError: Boolean,
    inProgress: Boolean,
    errorMessage: String,
    onTranslate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = when {
        inProgress -> stringResource(R.string.reader_translation_in_progress)
        hasError -> errorMessage
        !providerConfigured -> stringResource(R.string.reader_translation_provider_missing)
        else -> stringResource(
            R.string.reader_translation_missing,
            targetLanguage.uppercase(),
        )
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (hasError) EinkSoft else EinkPanel,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, if (hasError) EinkInk else EinkLine),
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (hasError) FontWeight.SemiBold else FontWeight.Normal,
                color = if (providerConfigured) EinkInk else EinkMuted,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Button(
                onClick = onTranslate,
                enabled = providerConfigured && !inProgress,
                modifier = Modifier.heightIn(min = 44.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EinkInk,
                    contentColor = EinkPanel,
                    disabledContainerColor = EinkLine,
                    disabledContentColor = EinkMuted,
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp),
            ) {
                Text(
                    text = if (inProgress) {
                        stringResource(R.string.action_translating)
                    } else if (hasError) {
                        stringResource(R.string.action_retry_translation)
                    } else {
                        stringResource(
                            R.string.action_translate_to_language,
                            targetLanguage.uppercase(),
                        )
                    },
                    maxLines = 2,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
