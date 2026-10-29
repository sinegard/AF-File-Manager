package com.affilemanager.app.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FilterAlt
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.data.OptionalFeature

@Composable
internal fun directoryGridColumns(portrait: Int, landscape: Int? = null): Int {
    val orientation = androidx.compose.ui.platform.LocalConfiguration.current.orientation
    return (if (orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE) landscape ?: portrait else portrait)
        .coerceIn(1, com.affilemanager.app.data.DirectoryDisplayRules.MAX_GRID_COLUMNS)
}

/** Selection must not move file rows beneath an active long-press drag. */
@Composable
fun StableDirectoryHeader(selectionActive: Boolean, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        var browserHeightPx by remember(maxWidth, density.density, density.fontScale) { mutableIntStateOf(0) }
        val minimum = if (selectionActive) with(density) { browserHeightPx.toDp() } else 0.dp
        Box(
            Modifier.fillMaxWidth().heightIn(min = minimum)
                .then(if (selectionActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer) else Modifier)
                .onSizeChanged { if (!selectionActive) browserHeightPx = it.height },
            contentAlignment = Alignment.CenterStart,
        ) { content() }
    }
}

/**
 * Shared navigation chrome for every place that behaves like a file browser.
 * Unsupported navigation directions stay visible but disabled so the controls
 * do not jump around when the backing storage changes.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    onOpenPath: ((String) -> Unit)? = null,
    addressPath: String = path,
    onChooseProvider: (() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    var editingAddress by androidx.compose.runtime.remember(testTagPrefix, addressPath) { androidx.compose.runtime.mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val pathError = uiText("Aplanko atidaryti nepavyko")
    if (editingAddress && onOpenPath != null) {
        DirectoryAddressDialog(addressPath, { editingAddress = false }, { entered ->
            editingAddress = false
            runCatching { onOpenPath(entered) }.onFailure {
                android.widget.Toast.makeText(context, pathError, android.widget.Toast.LENGTH_LONG).show()
            }
        }, onChooseProvider?.let { choose -> { editingAddress = false; choose() } })
    }
    com.affilemanager.app.ui.theme.AppearanceContentOn(MaterialTheme.colorScheme.surfaceContainer) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(22.dp))
            .padding(horizontal = 2.dp)
            .testTag("directory_toolbar_$testTagPrefix"),
    ) {
        val showBack = featureVisible(OptionalFeature.TOOLBAR_BACK)
        val showForward = featureVisible(OptionalFeature.TOOLBAR_FORWARD)
        val showUp = featureVisible(OptionalFeature.TOOLBAR_UP)
        val showSearch = searchActive || featureVisible(OptionalFeature.TOOLBAR_SEARCH)
        val showLayout = featureVisible(OptionalFeature.TOOLBAR_LAYOUT)
        val controls = listOf(showBack, showForward, showUp, showSearch, showLayout).count { it } + 1
        val separateAddress = maxWidth < 48.dp * controls + 120.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        val navigation: @Composable () -> Unit = {
        if (showBack) IconButton(onClick = onBack, enabled = backEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = uiText("Atgal"))
        }
        if (showForward) IconButton(onClick = onForward, enabled = forwardEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = uiText("Pirmyn"))
        }
        if (showUp) IconButton(onClick = onUp, enabled = upEnabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = uiText("Aukštyn"))
        }
        }
        val address: @Composable (Modifier) -> Unit = { addressModifier ->
        Column(modifier = addressModifier.then(if (onOpenPath != null) Modifier
            .testTag("directory_address_$testTagPrefix")
            .combinedClickable(onClickLabel = uiText("Atidaryti aplanką"), onClick = { editingAddress = true }) else Modifier)) {
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
        }
        val search: @Composable () -> Unit = {
        DirectorySearchButton(
            active = searchActive,
            testTag = "directory_search_$testTagPrefix",
            onClick = onToggleSearch,
        )
        }
        val layout: @Composable () -> Unit = {
        DirectoryLayoutButton(
            grid = grid,
            testTag = "directory_layout_$testTagPrefix",
            onToggleLayout = onToggleLayout,
            onOpenSettings = onOpenSettings,
        )
        }
        val tools: @Composable () -> Unit = {
        search()
        layout()
        actions()
        }
        if (separateAddress) {
            val sideControls = (if (showLayout) 1 else 0) + 1
            val navigationControls = listOf(showBack, showForward, showUp, showSearch).count { it }
            val titleMinimum = maxOf(120.dp, 80.dp * LocalDensity.current.fontScale.coerceAtLeast(1f))
            val splitControls = maxWidth < 48.dp * controls &&
                maxWidth >= 48.dp * navigationControls && maxWidth >= 48.dp * sideControls + titleMinimum
            Column(Modifier.fillMaxWidth()) {
                if (splitControls) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        navigation()
                        search()
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        address(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp))
                        layout()
                        actions()
                    }
                } else {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    navigation()
                    tools()
                }
                address(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                navigation()
                address(Modifier.weight(1f))
                tools()
            }
        }
    }
    }
}

/** One explicit menu touch target for local, remote, virtual and privileged folders. */
@Composable
fun DirectoryActionsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(48.dp)) {
        Icon(Icons.Rounded.MoreVert, contentDescription = uiText("Aplanko veiksmai"))
    }
}

/** A tap switches view mode; a long press opens the same full settings in every browser. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DirectoryLayoutButton(
    grid: Boolean,
    testTag: String,
    onToggleLayout: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!featureVisible(OptionalFeature.TOOLBAR_LAYOUT)) return
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
    if (!active && !featureVisible(OptionalFeature.TOOLBAR_SEARCH)) return
    IconButton(onClick = onClick, modifier = modifier.size(48.dp).testTag(testTag)) {
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
    filterActive: Boolean = false,
    onOpenFilter: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onOpenFilter != null) {
                    IconButton(onClick = onOpenFilter) {
                        Icon(
                            Icons.Rounded.FilterAlt,
                            contentDescription = uiText(if (filterActive) "Filtras aktyvus" else "Filtras"),
                            tint = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti greitą paiešką"))
                }
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
    filterActive: Boolean = false,
    onOpenFilter: (() -> Unit)? = null,
) {
    if (onOpenFilter != null) {
        DropdownMenuItem(
            text = { LText(if (filterActive) "Filtras aktyvus" else "Filtras") },
            leadingIcon = {
                Icon(
                    Icons.Rounded.FilterAlt,
                    contentDescription = null,
                    tint = if (filterActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            },
            onClick = {
                onDismissMenu()
                onOpenFilter()
            },
        )
    }
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
