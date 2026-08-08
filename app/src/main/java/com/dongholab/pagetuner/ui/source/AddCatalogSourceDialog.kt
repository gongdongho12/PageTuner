package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPanel

@Composable
fun AddCatalogSourceDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onAddCatalog: (title: String, url: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://wtr-lab.com/en") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = EinkPanel,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, EinkLine),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Add New Web Novel Catalog Source",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EinkInk,
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Source Title (e.g. WTR-Lab English)") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Catalog URL (http:// or https://)") },
                    singleLine = true,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !busy) {
                        Text("Cancel", color = EinkInk)
                    }
                    Button(
                        onClick = {
                            if (url.isNotBlank()) {
                                onAddCatalog(title.ifBlank { "Custom Web Source" }, url)
                                onDismiss()
                            }
                        },
                        enabled = !busy && url.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EinkInk,
                            contentColor = EinkPanel,
                        ),
                        shape = RoundedCornerShape(2.dp),
                    ) {
                        Text("Add Source & Load 🚀")
                    }
                }
            }
        }
    }
}
