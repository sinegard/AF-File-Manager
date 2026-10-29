package com.affilemanager.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfSurface

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NearbyOpenStorageButton(enabled: Boolean, onClick: () -> Unit, onAndroidPicker: () -> Unit) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    fun finishInput() { focus.clearFocus(force = true); keyboard?.hide() }
    // One combined gesture owner: a long press never also opens the AF picker.
    AfSurface(shape = ButtonDefaults.shape, color = Color.Transparent,
        contentColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = if (enabled) 1f else .12f)),
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("nearby_open_storage")
            .combinedClickable(enabled = enabled, role = Role.Button,
                onLongClickLabel = "${uiText("Rinktis failus")} · Android",
                onClick = { finishInput(); onClick() }, onLongClick = { finishInput(); onAndroidPicker() })) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally)) {
            Icon(Icons.Rounded.FolderOpen, contentDescription = null)
            LText("Atidaryti saugyklą")
        }
    }
}
