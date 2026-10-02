package com.dongholab.pagetuner.sharing

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dongholab.pagetuner.R
import com.dongholab.pagetuner.ui.common.AdaptiveCollection
import com.dongholab.pagetuner.ui.theme.EinkInk
import com.dongholab.pagetuner.ui.theme.EinkLine
import com.dongholab.pagetuner.ui.theme.EinkPaper
import kotlinx.coroutines.delay

@Composable
fun LocalSharingPanel(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val state by LocalSharingControl.state.collectAsState()
    var addresses by remember { mutableStateOf(sharingAddresses()) }
    var pendingAddress by remember { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingAddress?.let { address -> LocalSharingControl.start(context, address) }
        pendingAddress = null
    }
    LaunchedEffect(Unit) { while (true) { addresses = sharingAddresses(); delay(5_000) } }
    val info = state.session
    val rows = buildList {
        add("scope"); add("offline"); add("trust")
        if (info != null) { add("address"); add("code"); add("stop") }
        else {
            if (state.message != null) add("message")
            add("network")
            if (addresses.isEmpty()) add("empty")
            else addresses.forEach { add("start:${it.address}") }
        }
    }
    val rowHeight = (160 * LocalDensity.current.fontScale).dp
    AdaptiveCollection(rows, rowHeight, modifier.fillMaxSize(), itemKey = { it }) { row ->
        Surface(Modifier.fillMaxWidth().height(rowHeight), color = EinkPaper, border = BorderStroke(1.dp, EinkLine)) {
            Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (row) {
                    "scope" -> Text(stringResource(R.string.sharing_scope), color = EinkInk)
                    "offline" -> Text(stringResource(R.string.sharing_offline), color = EinkInk)
                    "trust" -> Text(stringResource(R.string.sharing_trust), color = EinkInk)
                    "message" -> state.message?.let { Text(stringResource(it), color = EinkInk) }
                    "empty" -> Text(stringResource(R.string.sharing_no_network), color = EinkInk)
                    "address" -> {
                        Text(stringResource(R.string.sharing_open_address), color = EinkInk)
                        SelectionContainer { Text("http://${info?.address}:${info?.port}/", color = EinkInk) }
                    }
                    "code" -> {
                        Text(stringResource(R.string.sharing_pairing_code), color = EinkInk)
                        SelectionContainer { Text(info?.pairingCode.orEmpty(), color = EinkInk) }
                        Text(stringResource(R.string.sharing_expiry), color = EinkInk)
                    }
                    "stop" -> OutlinedButton(onClick = { LocalSharingControl.stop(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                        Text(stringResource(R.string.sharing_stop))
                    }
                    "network" -> OutlinedButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
                    }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = !state.starting) { Text(stringResource(R.string.sharing_network_settings)) }
                    else -> {
                        val address = row.removePrefix("start:")
                        Text(addresses.firstOrNull { it.address == address }?.let { "${it.address} · ${it.interfaceName}" }.orEmpty(), color = EinkInk)
                        OutlinedButton(onClick = {
                            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                                pendingAddress = address
                                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else LocalSharingControl.start(context, address)
                        }, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp), enabled = !state.starting) {
                            Text(stringResource(if (state.starting) R.string.sharing_starting else R.string.sharing_start))
                        }
                    }
                }
            }
        }
    }
}
