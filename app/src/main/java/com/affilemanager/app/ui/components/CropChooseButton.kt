package com.affilemanager.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfSurface

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CropChooseButton(label: String, onClick: () -> Unit, onCrop: () -> Unit, modifier: Modifier = Modifier) {
    AfSurface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), modifier = modifier
            .combinedClickable(onClick = onClick, onLongClick = onCrop, role = Role.Button, onLongClickLabel = uiText("Apkirpti paveikslėlį"))) {
        LText(label, Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
    }
}
