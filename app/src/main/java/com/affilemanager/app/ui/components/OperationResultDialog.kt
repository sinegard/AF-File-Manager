package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.operations.OperationSnapshot
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.localization.LText

@Composable
internal fun OperationResultDialog(operation: OperationSnapshot, onClose: () -> Unit) {
    AfModalDialog(
        title = if (operation.status == OperationStatus.COMPLETED_WITH_ERRORS) "Operacija baigta su klaidomis" else "Operacija nepavyko",
        icon = Icons.Rounded.ErrorOutline,
        onDismissRequest = onClose,
        modifier = Modifier.testTag("operation_result_dialog"),
        actions = { TextButton(onClick = onClose) { LText("Uždaryti") } },
    ) {
        Column(Modifier.padding(18.dp)) {
            LText(operation.title, style = MaterialTheme.typography.titleSmall)
            LText(operation.message ?: "Nežinoma klaida", modifier = Modifier.testTag("operation_result_message"))
        }
    }
}
