package com.affilemanager.app.ui.components

import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.IndeterminateCheckBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SelectionHeader(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti"))
            }
            LText("Pasirinkta: $count", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    if (allSelected) Icons.Rounded.IndeterminateCheckBox else Icons.Rounded.CheckBox,
                    contentDescription = uiText(if (allSelected) "Atžymėti visus" else "Pasirinkti visus"),
                )
            }
        }
    }
}

@Composable
fun SelectionActionDock(
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = modifier.widthIn(max = 560.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 5.dp,
        shadowElevation = 5.dp,
    ) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = actions,
        )
    }
}

/** Shared selection interaction used by local and remote file lists. */
@Composable
fun SelectionActionBar(
    count: Int,
    allSelected: Boolean,
    onClose: () -> Unit,
    onToggleSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.primaryContainer) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti"))
            }
            LText("Pasirinkta: $count", fontWeight = FontWeight.SemiBold)
            IconButton(onClick = onToggleSelectAll) {
                Icon(
                    if (allSelected) Icons.Rounded.IndeterminateCheckBox else Icons.Rounded.CheckBox,
                    contentDescription = uiText(if (allSelected) "Atžymėti visus" else "Pasirinkti visus"),
                )
            }
            actions()
        }
    }
}
