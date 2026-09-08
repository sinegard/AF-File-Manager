package com.affilemanager.app.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CardElevation
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Text follows the surface it is on, not a different page/card role chosen elsewhere. */
@Composable
internal fun AppearanceContentOn(fill: Color, content: @Composable () -> Unit) {
    // Presets already define coherent content roles. Only independently chosen RGB roles
    // need per-surface adaptation, avoiding extra theme work in the normal browsing path.
    if (LocalAppearanceSettings.current.colorPalette != AppColorPalette.CUSTOM) { content(); return }
    val opaque = LocalOpaqueColors.current
    val scheme = MaterialTheme.colorScheme
    val effective = fill.compositeOver(opaque.background)
    val foreground = CustomThemeRules.foreground(effective)
    val adapted = remember(scheme, opaque, effective) {
        val primary = CustomThemeRules.readableAccent(opaque.primary, effective)
        val secondary = CustomThemeRules.readableAccent(opaque.secondary, effective)
        val tertiary = CustomThemeRules.readableAccent(opaque.tertiary, effective)
        val error = CustomThemeRules.readableAccent(opaque.error, effective)
        scheme.copy(onSurface = foreground, onSurfaceVariant = foreground,
            primary = primary, onPrimary = CustomThemeRules.foreground(primary),
            secondary = secondary, onSecondary = CustomThemeRules.foreground(secondary),
            tertiary = tertiary, onTertiary = CustomThemeRules.foreground(tertiary),
            error = error, onError = CustomThemeRules.foreground(error))
    }
    MaterialTheme(colorScheme = adapted) { CompositionLocalProvider(LocalContentColor provides foreground, content = content) }
}

@Composable
internal fun AfSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = Color.Unspecified,
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    AppearanceContentOn(color) {
        Surface(modifier = modifier, shape = shape, color = color,
            contentColor = contentColor.takeOrElse { LocalContentColor.current }, tonalElevation = tonalElevation,
            shadowElevation = if (color.alpha < 1f) 0.dp else shadowElevation, border = border, content = content)
    }
}

@Composable
internal fun AfCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppearanceContentOn(colors.containerColor) {
        Card(modifier = modifier, shape = shape, colors = colors.copy(contentColor = LocalContentColor.current),
            elevation = elevation, border = border, content = content)
    }
}

@Composable
internal fun AfCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.shape,
    colors: CardColors = CardDefaults.cardColors(),
    elevation: CardElevation = CardDefaults.cardElevation(),
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    AppearanceContentOn(if (enabled) colors.containerColor else colors.disabledContainerColor) {
        Card(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape,
            colors = colors.copy(contentColor = LocalContentColor.current, disabledContentColor = LocalContentColor.current.copy(alpha = .38f)),
            elevation = elevation, border = border, interactionSource = interactionSource, content = content)
    }
}

@Composable
internal fun AfElevatedCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = appearanceCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) = AfCard(onClick, modifier, enabled, shape, colors, elevation, content = content)

@Composable
internal fun AfElevatedCard(
    modifier: Modifier = Modifier,
    shape: Shape = CardDefaults.elevatedShape,
    colors: CardColors = CardDefaults.elevatedCardColors(),
    elevation: CardElevation = appearanceCardElevation(),
    content: @Composable ColumnScope.() -> Unit,
) = AfCard(modifier, shape, colors, elevation, content = content)
