package com.dongholab.pagetuner.ui.library

import android.os.Environment
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft
import java.io.File

@Composable
fun LocalDirectoryBrowserPanel(
    busy: Boolean,
    onImportFile: (File) -> Unit,
) {
    val context = LocalContext.current
    val initialDir = remember(context) {
        val storage = Environment.getExternalStorageDirectory()
        if (storage != null && storage.exists() && storage.canRead()) {
            storage
        } else {
            context.filesDir
        }
    }
    var currentDir by remember { mutableStateOf(initialDir) }

    val entries = remember(currentDir) {
        runCatching {
            val files = currentDir.listFiles().orEmpty()
            val dirs = files.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }
            val readableFiles = files.filter { file ->
                file.isFile && isReadableBookExtension(file.extension)
            }.sortedBy { it.name.lowercase() }
            dirs + readableFiles
        }.getOrDefault(emptyList())
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, tint = EinkInk, modifier = Modifier.size(20.dp))
                Text(
                    text = currentDir.absolutePath,
                    style = MaterialTheme.typography.labelLarge,
                    color = EinkInk,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (currentDir.parentFile != null && currentDir.parentFile?.canRead() == true) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) {
                            currentDir.parentFile?.let { currentDir = it }
                        },
                    color = EinkSoft,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, EinkLine),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = EinkMuted, modifier = Modifier.size(18.dp))
                        Text(".. (Up Parent Directory)", style = MaterialTheme.typography.bodySmall, color = EinkInk)
                    }
                }
            }

            if (entries.isEmpty()) {
                Text(
                    text = "No readable books (.txt, .md, .pdf, .epub) in this folder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = EinkMuted,
                )
            } else {
                com.dongholab.pagetuner.ui.common.EinkPagingContainer(
                    items = entries,
                    pageSize = 6,
                    busy = busy,
                ) { file ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) {
                                if (file.isDirectory) {
                                    currentDir = file
                                } else {
                                    onImportFile(file)
                                }
                            },
                        color = EinkSoft,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, EinkLine),
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = if (file.isDirectory) Icons.Filled.Folder else Icons.Filled.Description,
                                contentDescription = null,
                                tint = if (file.isDirectory) EinkInk else EinkMuted,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = file.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (file.isDirectory) FontWeight.SemiBold else FontWeight.Normal,
                                    color = EinkInk,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (!file.isDirectory) {
                                Text(
                                    text = "Import",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EinkInk,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun isReadableBookExtension(ext: String): Boolean {
    return when (ext.lowercase()) {
        "txt", "text", "md", "markdown", "pdf", "epub" -> true
        else -> false
    }
}
