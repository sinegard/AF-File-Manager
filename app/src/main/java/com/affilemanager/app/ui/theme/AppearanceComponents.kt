package com.affilemanager.app.ui.theme

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.affilemanager.app.ui.localization.uiText
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** All app-owned popups use this role, independently of the transparency of ordinary cards. */
@Composable
internal fun popupColor(): Color {
    return resolvePopupColor(LocalAppearanceSettings.current, LocalOpaqueColors.current)
}

internal fun resolvePopupColor(settings: AppearanceSettings, palette: androidx.compose.material3.ColorScheme): Color {
    val opaque = if (settings.colorPalette == AppColorPalette.CUSTOM) Color(settings.customColors.popup)
        else palette.surfaceContainerHigh
    val alpha = if (settings.transparentMenus) 1f - settings.cardTransparency.coerceIn(0, 100) / 100f else 1f
    return opaque.copy(alpha = alpha)
}

@Composable
internal fun PopupContent(content: @Composable () -> Unit) {
    val attributes = (LocalView.current.parent as? DialogWindowProvider)?.window?.attributes
    val dim = if (attributes != null && attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
        attributes.dimAmount.coerceIn(0f, 1f) else 0f
    val backdrop = Color.Black.copy(alpha = dim).compositeOver(LocalOpaqueColors.current.background)
    val color = popupColor().compositeOver(backdrop)
    val foreground = CustomThemeRules.foreground(color)
    val scheme = MaterialTheme.colorScheme
    val adapted = remember(scheme, color) {
        val primary = CustomThemeRules.readableAccent(scheme.primary, color)
        val error = CustomThemeRules.readableAccent(scheme.error, color)
        scheme.copy(onSurface = foreground, onSurfaceVariant = foreground,
            primary = primary, onPrimary = CustomThemeRules.foreground(primary),
            error = error, onError = CustomThemeRules.foreground(error))
    }
    MaterialTheme(colorScheme = adapted) {
        // Leave the style color unspecified so nested controls can supply their own
        // foreground (for example white text on a dark filled button).
        CompositionLocalProvider(LocalContentColor provides foreground,
            LocalTextStyle provides LocalTextStyle.current.copy(color = Color.Unspecified), content = content)
    }
}

@Composable
internal fun AfButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = androidx.compose.material3.ButtonDefaults.shape,
    colors: androidx.compose.material3.ButtonColors = appearanceButtonColors(),
    elevation: androidx.compose.material3.ButtonElevation? = androidx.compose.material3.ButtonDefaults.buttonElevation(),
    border: androidx.compose.foundation.BorderStroke? = null,
    contentPadding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.material3.ButtonDefaults.ContentPadding,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) = androidx.compose.material3.Button(onClick, modifier, enabled, shape, colors, elevation, border,
    contentPadding, interactionSource, content)

@Composable
private fun appearanceButtonColors(): androidx.compose.material3.ButtonColors {
    val settings = LocalAppearanceSettings.current
    if (settings.colorPalette != AppColorPalette.CUSTOM) return androidx.compose.material3.ButtonDefaults.buttonColors()
    val fill = Color(settings.customColors.controls)
    return androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = fill,
        contentColor = CustomThemeRules.foreground(fill))
}

@Composable
internal fun AfDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset.Zero,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val fill = popupColor()
    PopupContent {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier,
            offset = offset, scrollState = scrollState, containerColor = fill,
            tonalElevation = 0.dp, shadowElevation = if (fill.alpha < 1f) 0.dp else 8.dp,
            content = content)
    }
}

@Composable
internal fun AfAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = MaterialTheme.shapes.extraLarge,
    properties: DialogProperties = DialogProperties(),
) {
    val fill = popupColor()
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = properties.dismissOnClickOutside,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = properties.decorFitsSystemWindows,
        )) {
        BoxWithConstraints(Modifier.imePadding(), contentAlignment = Alignment.Center) {
            Surface(
                modifier = Modifier.widthIn(max = 760.dp).fillMaxWidth(.94f)
                    .heightIn(max = maxHeight * .92f).then(modifier),
                shape = shape, color = fill, tonalElevation = 0.dp,
            ) {
                PopupContent {
                    Column {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            icon?.let { slot ->
                                androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { slot() }
                            }
                            Box(Modifier.weight(1f)) {
                                title?.let { slot -> ProvideTextStyle(MaterialTheme.typography.titleLarge, slot) }
                            }
                            IconButton(onClick = onDismissRequest) {
                                Icon(Icons.Rounded.Close, contentDescription = uiText("Uždaryti"))
                            }
                        }
                        HorizontalDivider()
                        text?.let { slot ->
                            Box(Modifier.fillMaxWidth().weight(1f, fill = false).padding(horizontal = 18.dp, vertical = 14.dp)) {
                                ProvideTextStyle(MaterialTheme.typography.bodyMedium, slot)
                            }
                        }
                        HorizontalDivider()
                        FlowRow(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.End,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                            itemVerticalAlignment = Alignment.CenterVertically,
                        ) {
                            dismissButton?.invoke()
                            confirmButton()
                        }
                    }
                }
            }
        }
    }
}

/** Shadows must not remain as dark rectangles when the card itself is transparent. */
@Composable
internal fun appearanceCardElevation() = if (LocalAppearanceSettings.current.cardTransparency > 0) {
    CardDefaults.elevatedCardElevation(defaultElevation = 0.dp, pressedElevation = 0.dp,
        focusedElevation = 0.dp, hoveredElevation = 0.dp, draggedElevation = 0.dp, disabledElevation = 0.dp)
} else CardDefaults.elevatedCardElevation()

@Composable
internal fun AfFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val custom = LocalAppearanceSettings.current.takeIf { it.colorPalette == AppColorPalette.CUSTOM }
    val fill = custom?.let { Color(it.customColors.controls) }
    val foreground = fill?.let(CustomThemeRules::foreground)
    val colors = if (fill == null || foreground == null) androidx.compose.material3.FilterChipDefaults.filterChipColors()
        else androidx.compose.material3.FilterChipDefaults.filterChipColors(containerColor = fill, labelColor = foreground,
            iconColor = foreground, selectedContainerColor = fill, selectedLabelColor = foreground,
            selectedLeadingIconColor = foreground, selectedTrailingIconColor = foreground)
    androidx.compose.material3.FilterChip(selected = selected, onClick = onClick, label = label,
        modifier = modifier, enabled = enabled, leadingIcon = leadingIcon, trailingIcon = trailingIcon, colors = colors,
        border = if (custom == null) androidx.compose.material3.FilterChipDefaults.filterChipBorder(enabled, selected)
            else androidx.compose.foundation.BorderStroke(if (selected) 2.dp else 1.dp,
                if (selected) CustomThemeRules.readableAccent(Color(custom.customColors.primary), fill!!) else foreground!!.copy(alpha = .5f)))
}
