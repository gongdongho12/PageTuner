package com.dongholab.pagetuner.ui.source

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.source.RemoteSourceTodo
import com.dongholab.pagetuner.source.RemoteSourceTodos
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkMuted
import com.dongholab.pagetuner.ui.theme.EinkPanel
import com.dongholab.pagetuner.ui.theme.EinkSoft

@Composable
fun RemoteSourcesTodoPanel() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = EinkPanel,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, EinkLine),
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.remote_sources_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = EinkInk,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.remote_sources_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = EinkMuted,
                    )
                }
            }
            RemoteSourceTodos.items.forEach { item ->
                RemoteSourceTodoRow(item)
            }
        }
    }
}

@Composable
private fun RemoteSourceTodoRow(item: RemoteSourceTodo) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            color = EinkSoft,
            shape = RoundedCornerShape(4.dp),
            border = BorderStroke(1.dp, EinkLine),
        ) {
            Text(
                text = stringResource(item.phaseRes),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = EinkInk,
                fontFamily = FontFamily.Monospace,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(item.titleRes),
                style = MaterialTheme.typography.labelLarge,
                color = EinkInk,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(item.descriptionRes),
                style = MaterialTheme.typography.bodySmall,
                color = EinkMuted,
            )
        }
    }
}
