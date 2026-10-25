package com.dongholab.pagetuner.portable

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.dongholab.pagetuner.translation.sync.ServerReadingConnection
import com.dongholab.pagetuner.translation.sync.ServerReadingTarget
import java.util.UUID

/** A route value captures the account publication; it cannot be rebound by a late callback. */
internal class PortableOrganizationSelection(
    val entry: PortableLibraryEntry,
    val authority: PortableTextAccountAuthority,
    val accountLabel: String,
    val id: String = UUID.randomUUID().toString(),
)

@Composable
internal fun PortableOrganizationAdoptionWorkspace(
    selection: PortableOrganizationSelection,
    currentAuthority: PortableTextAccountAuthority?,
    create: (PortableTextOperationTicket) -> PortableOrganizationAdoptionCommand,
    captureDelivery: (ServerReadingConnection) -> ((ServerReadingTarget) -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val session = remember(selection) {
        PortableOrganizationAdoptionSession(scope, selection.authority, selection.id, create, captureDelivery)
    }
    DisposableEffect(session) {
        session.activate()
        onDispose { session.close(); onBack() }
    }
    val state by session.state.collectAsState()
    BackHandler { session.close(); onBack() }
    PortableOrganizationAdoptionPanel(state.comparison, selection.accountLabel,
        currentAuthority === selection.authority && session.current(), state.busy, state.committed, state.error,
        sessionKey = session,
        onBack = { session.close(); onBack() }, onCompare = session::compare, onConfirm = session::confirm,
        onDisposeCaptured = session::close, modifier = modifier)
}
