package com.affilemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

/**
 * The same view-mode control is used for every local and remote directory.
 * A tap switches between list and grid; a long press opens the full settings.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryLayoutButton(
    grid: Boolean,
    testTag: String,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val toggleLabel = uiText(if (grid) "Rodyti sąrašą" else "Rodyti tinklelį")
    Box(
        modifier = modifier
            .size(48.dp)
            .testTag(testTag)
            .clip(CircleShape)
            .combinedClickable(
                onClickLabel = toggleLabel,
                onLongClickLabel = uiText("Rodinio nustatymai"),
                onClick = onToggleLayout,
                onLongClick = onOpenSettings,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView,
            contentDescription = toggleLabel,
        )
    }
}

@Composable
fun DirectorySearchButton(
    active: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(onClick = onClick, modifier = modifier.testTag(testTag)) {
        Icon(
            Icons.Rounded.Search,
            contentDescription = uiText(if (active) "Uždaryti greitą paiešką" else "Greita paieška šiame aplanke"),
        )
    }
}

@Composable
fun DirectoryQuickSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti greitą paiešką"))
            }
        },
        placeholder = { LText("Filtruoti šį aplanką") },
        singleLine = true,
    )
}

/** Common directory-view actions shared by local and remote folder menus. */
@Composable
fun DirectoryDisplayMenuItems(
    grid: Boolean,
    includeHidden: Boolean,
    showThumbnails: Boolean,
    thumbnailsAvailable: Boolean,
    sortMode: SortMode,
    sortDirection: SortDirection,
    displaySettingsTestTag: String,
    onToggleHidden: () -> Unit,
    onToggleLayout: () -> Unit,
    onToggleThumbnails: () -> Unit,
    onOpenSettings: () -> Unit,
    onSort: (SortMode) -> Unit,
    onDismissMenu: () -> Unit,
) {
    DropdownMenuItem(
        text = { LText(if (includeHidden) "Slėpti paslėptus failus" else "Rodyti paslėptus failus") },
        leadingIcon = {
            Icon(if (includeHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, contentDescription = null)
        },
        onClick = {
            onDismissMenu()
            onToggleHidden()
        },
    )
    DropdownMenuItem(
        text = { LText("Rodinio nustatymai") },
        leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
        modifier = Modifier.testTag(displaySettingsTestTag),
        onClick = {
            onDismissMenu()
            onOpenSettings()
        },
    )
}
