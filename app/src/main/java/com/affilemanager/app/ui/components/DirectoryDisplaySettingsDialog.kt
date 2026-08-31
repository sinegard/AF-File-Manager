package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.affilemanager.app.data.DirectoryDisplayRules
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryGridStyle
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import com.affilemanager.app.ui.localization.LText
import kotlin.math.roundToInt

@Composable
fun DirectoryDisplaySettingsDialog(
    initialSettings: DirectoryDisplaySettings,
    thumbnailsAvailable: Boolean,
    gridColumnRange: IntRange = DirectoryDisplayRules.MIN_GRID_COLUMNS..DirectoryDisplayRules.MAX_GRID_COLUMNS,
    initialSortMode: SortMode? = null,
    initialSortDirection: SortDirection = SortDirection.ASCENDING,
    onDismiss: () -> Unit,
    onApply: (DirectoryDisplaySettings) -> Unit,
    onApplySort: ((SortMode, SortDirection) -> Unit)? = null,
    onApplyToAll: ((DirectoryDisplaySettings, SortMode?, SortDirection) -> Unit)? = null,
) {
    require(gridColumnRange.first >= DirectoryDisplayRules.MIN_GRID_COLUMNS)
    require(gridColumnRange.last <= DirectoryDisplayRules.MAX_GRID_COLUMNS)
    var draft by remember(initialSettings, thumbnailsAvailable, gridColumnRange) {
        mutableStateOf(
            (if (thumbnailsAvailable) initialSettings else initialSettings.copy(showThumbnails = false))
                .copy(gridColumns = initialSettings.gridColumns.coerceIn(gridColumnRange)),
        )
    }
    var draftSortMode by remember(initialSortMode) { mutableStateOf(initialSortMode ?: SortMode.NAME) }
    var draftSortDirection by remember(initialSortDirection) { mutableStateOf(initialSortDirection) }
    AfModalDialog(
        title = "Rodinio nustatymai",
        icon = Icons.Rounded.GridView,
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("display_settings_dialog"),
        actions = {
            TextButton(
                onClick = {
                    draft = DirectoryDisplaySettings(gridColumns = gridColumnRange.first, showThumbnails = false)
                    draftSortMode = SortMode.NAME
                    draftSortDirection = SortDirection.ASCENDING
                },
            ) { LText("Atstatyti") }
            TextButton(onClick = onDismiss) { LText("Atšaukti") }
            onApplyToAll?.let { applyToAll ->
                TextButton(
                    onClick = {
                        applyToAll(
                            DirectoryDisplayRules.requireValid(draft),
                            initialSortMode?.let { draftSortMode },
                            draftSortDirection,
                        )
                    },
                    modifier = Modifier.testTag("display_apply_all"),
                ) {
                    LText("Taikyti visiems")
                }
            }
            TextButton(onClick = {
                onApply(DirectoryDisplayRules.requireValid(draft))
                if (initialSortMode != null) onApplySort?.invoke(draftSortMode, draftSortDirection)
            }, modifier = Modifier.testTag("display_apply")) {
                LText("Taikyti")
            }
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                LText("Išdėstymas", fontWeight = FontWeight.SemiBold)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = draft.layoutMode == DirectoryLayoutMode.LIST,
                        onClick = { draft = draft.copy(layoutMode = DirectoryLayoutMode.LIST) },
                        label = { LText("Sąrašas") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null) },
                        modifier = Modifier.weight(1f).testTag("display_mode_list"),
                    )
                    FilterChip(
                        selected = draft.layoutMode == DirectoryLayoutMode.GRID,
                        onClick = { draft = draft.copy(layoutMode = DirectoryLayoutMode.GRID) },
                        label = { LText("Tinklelis") },
                        leadingIcon = { Icon(Icons.Rounded.GridView, contentDescription = null) },
                        modifier = Modifier.weight(1f).testTag("display_mode_grid"),
                    )
                }

                PercentageSlider(
                    title = "Piktogramų ir aplankų dydis",
                    value = draft.iconScalePercent,
                    range = DirectoryDisplayRules.MIN_ICON_SCALE_PERCENT..DirectoryDisplayRules.MAX_ICON_SCALE_PERCENT,
                    steps = 6,
                    testTag = "display_icon_scale",
                    onValueChange = { draft = draft.copy(iconScalePercent = it) },
                )
                PercentageSlider(
                    title = "Elementų tarpai",
                    value = draft.spacingScalePercent,
                    range = DirectoryDisplayRules.MIN_SPACING_SCALE_PERCENT..DirectoryDisplayRules.MAX_SPACING_SCALE_PERCENT,
                    steps = 7,
                    testTag = "display_spacing_scale",
                    onValueChange = { draft = draft.copy(spacingScalePercent = it) },
                )

                if (draft.layoutMode == DirectoryLayoutMode.GRID) {
                    LText("Tinklelio stulpeliai", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        gridColumnRange.forEach { count ->
                            FilterChip(
                                selected = draft.gridColumns == count,
                                onClick = { draft = draft.copy(gridColumns = count) },
                                label = { Text(count.toString()) },
                                modifier = Modifier.weight(1f).testTag("display_grid_columns_$count"),
                            )
                        }
                    }
                    LText("Tinklelio stilius", fontWeight = FontWeight.SemiBold)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draft.gridStyle == DirectoryGridStyle.CARDS,
                            onClick = { draft = draft.copy(gridStyle = DirectoryGridStyle.CARDS) },
                            label = { LText("Kortelės") },
                            modifier = Modifier.weight(1f).testTag("display_grid_style_cards"),
                        )
                        FilterChip(
                            selected = draft.gridStyle == DirectoryGridStyle.CLASSIC,
                            onClick = { draft = draft.copy(gridStyle = DirectoryGridStyle.CLASSIC) },
                            label = { LText("Klasikinis") },
                            modifier = Modifier.weight(1f).testTag("display_grid_style_classic"),
                        )
                    }
                    if (draftSortMode == SortMode.SIZE) {
                        LText(
                            "Failai rūšiuojami pagal dydį. Aplankai lieka pagal pavadinimą, kad naršant nereikėtų lėtai skenuoti viso jų turinio.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (thumbnailsAvailable) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            LText("Failų miniatiūros", fontWeight = FontWeight.SemiBold)
                            LText(
                                "Išsaugoma atskirai šiam katalogui",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.showThumbnails,
                            onCheckedChange = { draft = draft.copy(showThumbnails = it) },
                            modifier = Modifier.testTag("display_thumbnails"),
                        )
                    }
                }

                if (initialSortMode != null) {
                    LText("Rūšiavimas", fontWeight = FontWeight.SemiBold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SortMode.entries.chunked(2).forEach { modes ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                modes.forEach { mode ->
                                    FilterChip(
                                        selected = draftSortMode == mode,
                                        onClick = { draftSortMode = mode },
                                        label = { LText(directorySortLabel(mode)) },
                                        modifier = Modifier.weight(1f).testTag("display_sort_${mode.name.lowercase()}"),
                                    )
                                }
                                if (modes.size == 1) androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = draftSortDirection == SortDirection.ASCENDING,
                            onClick = { draftSortDirection = SortDirection.ASCENDING },
                            label = { LText("Didėjančiai") },
                            modifier = Modifier.weight(1f).testTag("display_sort_ascending"),
                        )
                        FilterChip(
                            selected = draftSortDirection == SortDirection.DESCENDING,
                            onClick = { draftSortDirection = SortDirection.DESCENDING },
                            label = { LText("Mažėjančiai") },
                            modifier = Modifier.weight(1f).testTag("display_sort_descending"),
                        )
                    }
                }
        }
    }
}

private fun directorySortLabel(mode: SortMode): String = when (mode) {
    SortMode.NAME -> "Pagal pavadinimą"
    SortMode.MODIFIED -> "Pagal datą"
    SortMode.SIZE -> "Pagal dydį"
    SortMode.TYPE -> "Pagal tipą"
}

@Composable
private fun PercentageSlider(
    title: String,
    value: Int,
    range: IntRange,
    steps: Int,
    testTag: String,
    onValueChange: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth()) {
            LText(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("$value%", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val snapped = ((raw.roundToInt() - range.first + 5) / 10 * 10 + range.first)
                    .coerceIn(range.first, range.last)
                onValueChange(snapped)
            },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = steps,
            modifier = Modifier.fillMaxWidth().testTag(testTag),
        )
    }
}
