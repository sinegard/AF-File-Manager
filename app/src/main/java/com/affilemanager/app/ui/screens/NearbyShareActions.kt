package com.affilemanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.localization.uiText
import com.affilemanager.app.ui.theme.AfButton

internal data class NearbyShareAction(
    val label: String,
    val icon: ImageVector,
    val onClick: () -> Unit,
    val tag: String,
    val enabled: Boolean = true,
    val primary: Boolean = false,
)

/** Use the app's existing themed foreground and button shapes across nearby sharing. */
@Composable
internal fun NearbyShareActionButton(
    action: NearbyShareAction,
    modifier: Modifier = Modifier.fillMaxWidth(),
    iconAboveLabel: Boolean = false,
) {
    val button = modifier.heightIn(min = 48.dp).testTag(action.tag)
    val padding = PaddingValues(horizontal = if (iconAboveLabel) 12.dp else 16.dp, vertical = 10.dp)
    val content: @Composable RowScope.() -> Unit = {
        if (iconAboveLabel) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                LText(action.label, textAlign = TextAlign.Center)
            }
        } else {
            Icon(action.icon, contentDescription = null, modifier = Modifier.size(20.dp))
            LText(action.label, modifier = Modifier.padding(start = 7.dp), textAlign = TextAlign.Center)
        }
    }
    if (action.primary) AfButton(action.onClick, button, action.enabled, contentPadding = padding, content = content)
    else OutlinedButton(action.onClick, button, action.enabled, contentPadding = padding, content = content)
}

/** Keep peers balanced when both translated labels fit. Never truncate to force one row. */
@Composable
internal fun NearbyShareActionPair(first: NearbyShareAction, second: NearbyShareAction) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 4)
    val style = MaterialTheme.typography.labelLarge
    val firstLabel = uiText(first.label)
    val secondLabel = uiText(second.label)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val gap = 8.dp
        fun fits(label: String, width: Int): Boolean = !measurer.measure(
            label, style, maxLines = 3, constraints = Constraints(maxWidth = width),
        ).hasVisualOverflow
        val together = maxWidth >= 240.dp && density.fontScale < 1.4f &&
            with(density) {
                // Leave one pixel for rounding when the weighted cells split an odd width.
                val labelWidth = ((maxWidth - gap) / 2 - 24.dp).roundToPx() - 1
                fits(firstLabel, labelWidth) && fits(secondLabel, labelWidth)
            }
        if (together) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                NearbyShareActionButton(first, Modifier.weight(1f).fillMaxHeight(), iconAboveLabel = true)
                NearbyShareActionButton(second, Modifier.weight(1f).fillMaxHeight(), iconAboveLabel = true)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                NearbyShareActionButton(first)
                NearbyShareActionButton(second)
            }
        }
    }
}
