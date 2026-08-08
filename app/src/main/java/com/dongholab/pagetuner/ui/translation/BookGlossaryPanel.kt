package com.dongholab.pagetuner.ui.translation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.translation.glossary.BookGlossaryEntry
import com.dongholab.pagetuner.translation.glossary.GlossaryTermKind
import com.dongholab.pagetuner.ui.common.EinkAutoFitPagingContainer
import com.dongholab.pagetuner.ui.common.EinkOperationIndicator
import com.dongholab.pagetuner.ui.common.EinkSegmentedControl
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPaper
import com.dongholab.pagetuner.ui.theme.EinkSoft

private val GlossaryRowHeight = 92.dp

@Composable
fun BookGlossaryPanel(
    bookTitle: String?,
    entries: List<BookGlossaryEntry>,
    busy: Boolean,
    error: String?,
    onSave: (BookGlossaryEntry) -> Unit,
    onDelete: (BookGlossaryEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf<BookGlossaryEntry?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = EinkPaper,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.glossary_title),
                        fontWeight = FontWeight.Bold,
                        color = EinkInk,
                    )
                    Text(
                        text = bookTitle ?: stringResource(R.string.glossary_no_book),
                        color = EinkMuted,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = { editing = null; showEditor = true },
                    enabled = bookTitle != null && !busy,
                    colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text(stringResource(R.string.glossary_add))
                }
            }

            Text(
                text = stringResource(R.string.glossary_description),
                color = EinkMuted,
            )
            EinkOperationIndicator(
                visible = busy,
                title = stringResource(R.string.glossary_saving),
                detail = error,
            )
            if (error != null && !busy) Text(error, color = EinkInk)

            EinkAutoFitPagingContainer(
                items = entries,
                modifier = Modifier.weight(1f),
                estimatedItemHeight = GlossaryRowHeight,
                fallbackPageSize = 4,
                busy = busy,
                emptyContent = {
                    Text(
                        text = stringResource(
                            if (bookTitle == null) R.string.glossary_no_book else R.string.glossary_empty,
                        ),
                        color = EinkMuted,
                    )
                },
            ) { entry ->
                GlossaryEntryRow(
                    entry = entry,
                    busy = busy,
                    onEdit = { editing = entry; showEditor = true },
                    onDelete = { onDelete(entry) },
                )
            }
        }
    }

    if (showEditor) {
        GlossaryEntryDialog(
            initial = editing,
            onDismiss = { showEditor = false },
            onSave = { entry -> onSave(entry); showEditor = false },
        )
    }
}

@Composable
private fun GlossaryEntryRow(
    entry: BookGlossaryEntry,
    busy: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(GlossaryRowHeight),
        color = EinkSoft,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "${entry.sourceTerm} → ${entry.translatedTerm}",
                    fontWeight = FontWeight.SemiBold,
                    color = EinkInk,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.displayTerm.takeIf(String::isNotBlank)?.let {
                        stringResource(R.string.glossary_display_alias_value, it)
                    } ?: entry.kind.localizedLabel(),
                    color = EinkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onEdit, enabled = !busy) {
                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.glossary_edit), tint = EinkInk)
            }
            IconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.glossary_delete), tint = EinkInk)
            }
        }
    }
}

@Composable
private fun GlossaryEntryDialog(
    initial: BookGlossaryEntry?,
    onDismiss: () -> Unit,
    onSave: (BookGlossaryEntry) -> Unit,
) {
    var source by remember(initial) { mutableStateOf(initial?.sourceTerm.orEmpty()) }
    var translated by remember(initial) { mutableStateOf(initial?.translatedTerm.orEmpty()) }
    var display by remember(initial) { mutableStateOf(initial?.displayTerm.orEmpty()) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: GlossaryTermKind.Character) }
    val kindLabels = GlossaryTermKind.entries.associateWith { it.localizedLabel() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.glossary_add else R.string.glossary_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                EinkSegmentedControl(
                    options = GlossaryTermKind.entries,
                    selected = kind,
                    onSelect = { kind = it },
                    label = { kindLabels.getValue(it) },
                )
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    label = { Text(stringResource(R.string.glossary_source_term)) },
                    maxLines = 2,
                )
                OutlinedTextField(
                    value = translated,
                    onValueChange = { translated = it },
                    label = { Text(stringResource(R.string.glossary_translation_term)) },
                    supportingText = { Text(stringResource(R.string.glossary_translation_hint)) },
                    maxLines = 2,
                )
                OutlinedTextField(
                    value = display,
                    onValueChange = { display = it },
                    label = { Text(stringResource(R.string.glossary_display_alias)) },
                    supportingText = { Text(stringResource(R.string.glossary_display_hint)) },
                    maxLines = 2,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(BookGlossaryEntry(
                        id = initial?.id.orEmpty(),
                        sourceTerm = source,
                        translatedTerm = translated,
                        displayTerm = display,
                        kind = kind,
                        caseSensitive = initial?.caseSensitive ?: false,
                        enabled = initial?.enabled ?: true,
                    ))
                },
                enabled = source.isNotBlank() && translated.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = EinkInk, contentColor = EinkPaper),
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun GlossaryTermKind.localizedLabel(): String = stringResource(
    when (this) {
        GlossaryTermKind.Character -> R.string.glossary_kind_character
        GlossaryTermKind.Place -> R.string.glossary_kind_place
        GlossaryTermKind.Term -> R.string.glossary_kind_term
    },
)
