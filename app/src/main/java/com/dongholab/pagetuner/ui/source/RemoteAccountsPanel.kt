@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.RemoteSourceAccount
import com.dongholab.pagetuner.source.RemoteSourceType
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel

@Composable
fun RemoteAccountsPanel(
    accounts: List<RemoteSourceAccount>,
    busy: Boolean,
    onAddAccount: (RemoteSourceAccount) -> Unit,
    onDeleteAccount: (RemoteSourceAccount) -> Unit,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedType by remember { mutableStateOf(RemoteSourceType.FtpServer) }
    var title by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.remote_source_accounts_title, accounts.size),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = EinkInk,
                )
                Button(
                    onClick = { showAddDialog = !showAddDialog },
                    enabled = !busy,
                ) {
                    Text(if (showAddDialog) stringResource(R.string.action_cancel) else stringResource(R.string.action_add_account))
                }
            }

            if (showAddDialog) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RemoteSourceType.values().forEach { type ->
                            TextButton(
                                onClick = { selectedType = type },
                                enabled = selectedType != type,
                            ) {
                                Text(type.name)
                            }
                        }
                    }
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text(stringResource(R.string.field_account_title)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    OutlinedTextField(
                        value = endpoint,
                        onValueChange = { endpoint = it },
                        label = { Text(stringResource(R.string.field_account_endpoint)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (selectedType == RemoteSourceType.FtpServer) {
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text(stringResource(R.string.field_account_username)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                    }
                    Button(
                        onClick = {
                            if (title.isNotBlank() && endpoint.isNotBlank()) {
                                val now = System.currentTimeMillis()
                                onAddAccount(
                                    RemoteSourceAccount(
                                        id = "${selectedType.name.lowercase()}_$now",
                                        sourceType = selectedType,
                                        title = title.trim(),
                                        endpoint = endpoint.trim(),
                                        username = username.trim().takeIf { it.isNotBlank() },
                                        createdAtMillis = now,
                                        updatedAtMillis = now,
                                    ),
                                )
                                title = ""
                                endpoint = ""
                                username = ""
                                showAddDialog = false
                            }
                        },
                        enabled = title.isNotBlank() && endpoint.isNotBlank(),
                    ) {
                        Text(stringResource(R.string.action_save_account))
                    }
                }
            }

            accounts.forEach { account ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(account.title, fontWeight = FontWeight.Medium, color = EinkInk)
                        Text("${account.sourceType.name} • ${account.displayEndpoint}", style = MaterialTheme.typography.bodySmall, color = EinkMuted)
                    }
                    TextButton(onClick = { onDeleteAccount(account) }, enabled = !busy) {
                        Text(stringResource(R.string.action_delete_remote_source))
                    }
                }
            }
        }
    }
}
