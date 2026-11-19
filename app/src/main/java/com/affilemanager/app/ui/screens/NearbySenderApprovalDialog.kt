package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.LanTransferStatus
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AfAlertDialog

/** The app lock gates this whole window, not merely its confirm button. */
@Composable
internal fun NearbySenderApprovalDialog(state: LanTransferState, unlocked: Boolean, onDecision: (String, String, Boolean) -> Unit) {
    if (!unlocked || !state.phoneReceiver || state.status != LanTransferStatus.RUNNING) return
    val sessionId = state.receiveSessionId ?: return
    val request = state.pendingSenders.firstOrNull() ?: return
    val deny = { onDecision(sessionId, request.id, false) }
    AfAlertDialog(onDismissRequest = deny,
        title = { LText("Gauti iš kito telefono") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Sender-provided names are untrusted data. Always show the actual socket address too.
                Text(request.name, modifier = Modifier.testTag("nearby_sender_name"))
                Text(request.address, modifier = Modifier.testTag("nearby_sender_address"))
                LText("Leisti šiam siuntėjui siųsti failus iki atsijungimo?")
            }
        },
        confirmButton = {
            TextButton(onClick = { onDecision(sessionId, request.id, true) }, modifier = Modifier.testTag("nearby_sender_allow")) { LText("Patvirtinti") }
        },
        dismissButton = {
            TextButton(onClick = deny, modifier = Modifier.testTag("nearby_sender_deny")) { LText("Atšaukti") }
        },
    )
}
