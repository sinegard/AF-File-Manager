package com.affilemanager.app.ui.preview

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.theme.AfDialog
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AppearancePage
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PickerPreviewDialog(entry: FileEntry, onClose: () -> Unit) {
    AfDialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AppearancePage(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                TextButton(onClick = onClose) { LText("Uždaryti") }
                ReadOnlyFilePreview(PreviewSource.Local(entry))
            }
        }
    }
}
