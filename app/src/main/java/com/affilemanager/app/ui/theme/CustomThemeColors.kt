package com.affilemanager.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import java.util.Locale

data class CustomThemeColors(
    val primary: Int = 0xFF006B5D.toInt(),
    val secondary: Int = 0xFF23649A.toInt(),
    val tertiary: Int = 0xFF8B4E00.toInt(),
    val background: Int = 0xFFF6F8FC.toInt(),
    val surface: Int = 0xFFFFFFFF.toInt(),
    val popup: Int = surface,
    val controls: Int = defaultControlColor(primary, surface),
) {
    fun values(): List<Int> = listOf(primary, secondary, tertiary, background, surface, popup, controls)
}

private fun defaultControlColor(primary: Int, surface: Int): Int {
    var color = 0xFF000000.toInt()
    for (shift in listOf(16, 8, 0)) {
        val mixed = ((surface ushr shift and 255) * .82f + (primary ushr shift and 255) * .18f).toInt()
        color = color or (mixed shl shift)
    }
    return color
}

object CustomThemeRules {
    fun parseHex(value: String): Int? = value.trim().removePrefix("#")
        .takeIf { it.length == 6 && it.all { char -> char in '0'..'9' || char.lowercaseChar() in 'a'..'f' } }
        ?.toIntOrNull(16)?.or(0xFF000000.toInt())

    fun hex(value: Int): String = String.format(Locale.ROOT, "#%06X", value and 0xFFFFFF)

    fun parseDraft(values: List<String>): CustomThemeColors? {
        if (values.size !in setOf(5, 7)) return null
        val colors = values.map { parseHex(it) ?: return null }
        return CustomThemeColors(colors[0], colors[1], colors[2], colors[3], colors[4],
            popup = colors.getOrElse(5) { colors[4] }, controls = colors.getOrElse(6) { defaultControlColor(colors[0], colors[4]) })
    }

    fun parseStored(value: String?): CustomThemeColors =
        value?.takeIf { it.length <= 55 }?.split(',')?.let(::parseDraft) ?: CustomThemeColors()

    fun contrast(first: Color, second: Color): Float {
        val a = first.luminance()
        val b = second.luminance()
        return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
    }

    fun foreground(background: Color): Color =
        if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White

    // Accent colors also render as text, so keep them readable on the user's chosen surface.
    fun readableAccent(requested: Color, background: Color): Color {
        if (contrast(requested, background) >= 4.5f) return requested
        val target = foreground(background)
        for (step in 1..100) {
            val candidate = lerp(requested, target, step / 100f)
            if (contrast(candidate, background) >= 4.5f) return candidate
        }
        return target
    }
}

internal fun customColorScheme(colors: CustomThemeColors): ColorScheme {
    val surface = Color(colors.surface)
    val background = Color(colors.background)
    val foreground = CustomThemeRules.foreground(surface)
    val primary = CustomThemeRules.readableAccent(Color(colors.primary), surface)
    val secondary = CustomThemeRules.readableAccent(Color(colors.secondary), surface)
    val tertiary = CustomThemeRules.readableAccent(Color(colors.tertiary), surface)
    val primaryContainer = Color(colors.controls)
    val secondaryContainer = Color(colors.controls)
    val tertiaryContainer = lerp(surface, tertiary, .18f)
    val variant = surface
    val error = CustomThemeRules.readableAccent(Color(0xFFBA1A1A), surface)
    val errorContainer = lerp(surface, error, .18f)
    return lightColorScheme(
        primary = primary, onPrimary = CustomThemeRules.foreground(primary),
        primaryContainer = primaryContainer, onPrimaryContainer = CustomThemeRules.foreground(primaryContainer),
        secondary = secondary, onSecondary = CustomThemeRules.foreground(secondary),
        secondaryContainer = secondaryContainer, onSecondaryContainer = CustomThemeRules.foreground(secondaryContainer),
        tertiary = tertiary, onTertiary = CustomThemeRules.foreground(tertiary),
        tertiaryContainer = tertiaryContainer, onTertiaryContainer = CustomThemeRules.foreground(tertiaryContainer),
        background = background, onBackground = CustomThemeRules.foreground(background),
        surface = surface, onSurface = foreground,
        surfaceVariant = variant, onSurfaceVariant = CustomThemeRules.foreground(variant),
        surfaceTint = surface, outline = lerp(surface, foreground, .65f), outlineVariant = lerp(surface, foreground, .35f),
        error = error, onError = CustomThemeRules.foreground(error),
        errorContainer = errorContainer, onErrorContainer = CustomThemeRules.foreground(errorContainer),
        inverseSurface = foreground, inverseOnSurface = CustomThemeRules.foreground(foreground),
        inversePrimary = CustomThemeRules.readableAccent(primary, foreground),
        surfaceDim = surface, surfaceBright = variant, surfaceContainerLowest = surface,
        surfaceContainerLow = surface, surfaceContainer = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = surface,
    )
}
