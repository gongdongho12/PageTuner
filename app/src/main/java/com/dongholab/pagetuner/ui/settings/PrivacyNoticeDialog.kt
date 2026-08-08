package com.dongholab.pagetuner.ui.settings

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun PrivacyNoticeDialog(
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Translation Privacy Notice") },
        text = {
            Text(
                "PageTurner performs translations using your configured Translation Provider (Google Cloud, OpenAI, etc.).\n\n" +
                    "• Text excerpts are transmitted securely to the selected API endpoints.\n" +
                    "• PageTurner stores all translated outputs locally on your device for offline reading.\n" +
                    "• No personal reading history or credentials are submitted to PageTurner servers.",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        },
    )
}
