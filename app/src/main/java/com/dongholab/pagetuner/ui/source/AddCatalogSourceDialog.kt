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
    var showAdvancedRules by remember { mutableStateOf(false) }

    var titleSelector by remember { mutableStateOf(".title, h1") }
    var synopsisSelector by remember { mutableStateOf(".description, .synopsis") }
    var chapterLinkSelector by remember { mutableStateOf("a[href*='/chapter/']") }
    var paragraphSelector by remember { mutableStateOf(".chapter-content p, article p") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = EinkPanel,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(1.dp, EinkLine),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Add Custom Web Novel Source",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = EinkInk,
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Source Title (e.g. RoyalRoad / Custom)") },
                    singleLine = true,
                )

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
                    label = { Text("Endpoint URL (http:// or https://)") },
                    singleLine = true,
                )

                // Advanced Custom Selectors Toggle
                TextButton(
                    onClick = { showAdvancedRules = !showAdvancedRules },
                    enabled = !busy,
                ) {
                    Text(
                        text = if (showAdvancedRules) "Hide Custom Selectors ▲" else "Advanced Custom Selectors ▼",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = EinkInk,
                    )
                }

                if (showAdvancedRules) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = com.dongholab.pagetuner.ui.theme.EinkSoft,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, EinkLine),
                    ) {
                        Column(
                            modifier = Modifier.padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            OutlinedTextField(
                                value = titleSelector,
                                onValueChange = { titleSelector = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                label = { Text("Title CSS Selector") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = synopsisSelector,
                                onValueChange = { synopsisSelector = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                label = { Text("Synopsis CSS Selector") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = chapterLinkSelector,
                                onValueChange = { chapterLinkSelector = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                label = { Text("Chapter Link CSS Selector") },
                                singleLine = true,
                            )
                            OutlinedTextField(
                                value = paragraphSelector,
                                onValueChange = { paragraphSelector = it },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !busy,
                                label = { Text("Content Paragraph CSS Selector") },
                                singleLine = true,
                            )
                        }
                    }
                }

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
                                val cleanTitle = title.ifBlank { "Custom Web Source" }
                                val customRule = com.dongholab.pagetuner.source.scraper.CustomWebSourceRule(
                                    id = "rule_${System.currentTimeMillis()}",
                                    name = cleanTitle,
                                    domainUrl = url,
                                    titleSelector = titleSelector,
                                    synopsisSelector = synopsisSelector,
                                    chapterLinkSelector = chapterLinkSelector,
                                    paragraphSelector = paragraphSelector,
                                )
                                com.dongholab.pagetuner.source.scraper.CustomWebSourceRuleStore.globalStore.addRule(customRule)
                                com.dongholab.pagetuner.source.scraper.WebNovelScraperRegistry.registerScraper(
                                    com.dongholab.pagetuner.source.scraper.CustomRuleScraperAdapter(customRule)
                                )
                                onAddCatalog(cleanTitle, url)
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
                        Text("Save Rule & Load 🚀")
                    }
                }
            }
        }
    }
}
