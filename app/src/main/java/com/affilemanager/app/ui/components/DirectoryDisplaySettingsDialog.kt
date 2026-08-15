package com.affilemanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material3.AlertDialog
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
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.ui.localization.LText
import kotlin.math.roundToInt

@Composable
fun DirectoryDisplaySettingsDialog(
    initialSettings: DirectoryDisplaySettings,
    thumbnailsAvailable: Boolean,
    gridColumnRange: IntRange = DirectoryDisplayRules.MIN_GRID_COLUMNS..DirectoryDisplayRules.MAX_GRID_COLUMNS,
    onDismiss: () -> Unit,
    onApply: (DirectoryDisplaySettings) -> Unit,
) {
    require(gridColumnRange.first >= DirectoryDisplayRules.MIN_GRID_COLUMNS)
    require(gridColumnRange.last <= DirectoryDisplayRules.MAX_GRID_COLUMNS)
    var draft by remember(initialSettings, thumbnailsAvailable, gridColumnRange) {
        mutableStateOf(
            (if (thumbnailsAvailable) initialSettings else initialSettings.copy(showThumbnails = false))
                .copy(gridColumns = initialSettings.gridColumns.coerceIn(gridColumnRange)),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LText("Rodinio nustatymai") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("display_settings_dialog"),
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
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(DirectoryDisplayRules.requireValid(draft)) }, modifier = Modifier.testTag("display_apply")) {
                LText("Taikyti")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        draft = DirectoryDisplaySettings(gridColumns = gridColumnRange.first, showThumbnails = false)
                    },
                ) { LText("Atstatyti") }
                TextButton(onClick = onDismiss) { LText("Atšaukti") }
            }
        },
    )
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
