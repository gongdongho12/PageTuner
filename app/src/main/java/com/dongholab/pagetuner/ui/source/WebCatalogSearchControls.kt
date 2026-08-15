package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.webnovel.WebNovelCatalogOption
import com.dongholab.pagetuner.ui.common.EinkChoiceStepper
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel

/** Server-backed keyword and genre search shared by every Web Novel catalog entry point. */
@Composable
fun WebCatalogSearchControls(
    query: String,
    genreOptions: List<WebNovelCatalogOption>,
    selectedGenreKey: String?,
    busy: Boolean,
    onQueryChange: (String) -> Unit,
    onGenreSelected: (String?) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val genres = genreOptions.ifEmpty { listOf(WebNovelCatalogOption(null, "All Genres")) }
    val selectedGenre = genres.firstOrNull { it.key == selectedGenreKey } ?: genres.first()
    val genrePrefix = stringResource(R.string.web_catalog_genre_prefix)
    val submit = {
        focusManager.clearFocus()
        onSearch()
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text(stringResource(R.string.web_catalog_search_label)) },
            supportingText = { Text(stringResource(R.string.web_catalog_search_hint)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submit() }),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EinkChoiceStepper(
                options = genres,
                selected = selectedGenre,
                onSelect = { onGenreSelected(it.key) },
                modifier = Modifier.weight(1f),
                enabled = !busy,
                label = { genre -> "$genrePrefix: ${genre.label}" },
            )
            Button(
                onClick = submit,
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                shape = RoundedCornerShape(2.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EinkInk,
                    contentColor = EinkPanel,
                ),
            ) {
                Text(
                    text = stringResource(R.string.action_search_remote_catalog),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        TextButton(
            onClick = onClear,
            enabled = !busy && (query.isNotBlank() || selectedGenreKey != null),
        ) {
            Text(
                text = stringResource(R.string.action_clear_catalog_search),
                style = MaterialTheme.typography.labelSmall,
                color = EinkMuted,
            )
        }
    }
}
