package com.affilemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText

/**
 * Shared navigation chrome for every place that behaves like a file browser.
 * Unsupported navigation directions stay visible but disabled so the controls
 * do not jump around when the backing storage changes.
 */
@Composable
fun DirectoryBrowserToolbar(
    title: String,
    path: String,
    backEnabled: Boolean,
    forwardEnabled: Boolean,
    upEnabled: Boolean,
    searchActive: Boolean,
    grid: Boolean,
    testTagPrefix: String,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onUp: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 2.dp)
            .testTag("directory_toolbar_$testTagPrefix"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, enabled = backEnabled) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Atgal"))
        }
        IconButton(onClick = onForward, enabled = forwardEnabled) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Pirmyn"))
        }
        IconButton(onClick = onUp, enabled = upEnabled) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Aukštyn"))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = path,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DirectorySearchButton(
            active = searchActive,
            testTag = "directory_search_$testTagPrefix",
            onClick = onToggleSearch,
        )
        DirectoryLayoutButton(
            grid = grid,
            testTag = "directory_layout_$testTagPrefix",
            onToggleLayout = onToggleLayout,
            onOpenSettings = onOpenSettings,
        )
        actions()
    }
}

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
    hiddenFilesAvailable: Boolean = true,
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
    if (hiddenFilesAvailable) {
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
    }
    DropdownMenuItem(
        text = { LText(if (grid) "Rodyti sąrašą" else "Rodyti tinklelį") },
        leadingIcon = {
            Icon(if (grid) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView, contentDescription = null)
        },
        onClick = {
            onDismissMenu()
            onToggleLayout()
        },
    )
    if (thumbnailsAvailable) {
        DropdownMenuItem(
            text = { LText(if (showThumbnails) "Rodyti piktogramas" else "Rodyti miniatiūras") },
            leadingIcon = {
                Icon(
                    if (showThumbnails) Icons.AutoMirrored.Rounded.InsertDriveFile else Icons.Rounded.PhotoLibrary,
                    contentDescription = null,
                )
            },
            onClick = {
                onDismissMenu()
                onToggleThumbnails()
            },
        )
    }
    DropdownMenuItem(
        text = {
            Column {
                LText("Rodinio nustatymai")
                Text(
                    directorySortSummary(sortMode, sortDirection),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingIcon = {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = null)
        },
        modifier = Modifier.testTag(displaySettingsTestTag),
        onClick = {
            onDismissMenu()
            onOpenSettings()
        },
    )
}

@Composable
private fun directorySortSummary(mode: SortMode, direction: SortDirection): String {
    val modeLabel = when (mode) {
        SortMode.NAME -> "Pagal pavadinimą"
        SortMode.MODIFIED -> "Pagal datą"
        SortMode.SIZE -> "Pagal dydį"
        SortMode.TYPE -> "Pagal tipą"
    }
    val directionLabel = if (direction == SortDirection.ASCENDING) "Didėjančiai" else "Mažėjančiai"
    return "${uiText(modeLabel)} · ${uiText(directionLabel)}"
}
