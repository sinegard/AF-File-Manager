package com.affilemanager.app.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomThemeRulesTest {
    @Test fun opaqueHexRoundTripsAndMalformedColorsAreRejected() {
        assertEquals(0xFFAb1234.toInt(), CustomThemeRules.parseHex("  #ab1234  "))
        assertEquals("#AB1234", CustomThemeRules.hex(0xFFAb1234.toInt()))
        listOf("", "#FFF", "#00FFFFFF", "12345g", "##123456", "-12345").forEach { assertNull(CustomThemeRules.parseHex(it)) }
        val colors = CustomThemeColors()
        assertEquals(colors, CustomThemeRules.parseStored(colors.values().joinToString(",", transform = CustomThemeRules::hex)))
        assertEquals(colors, CustomThemeRules.parseStored("#123456,".repeat(500)))
        assertEquals(colors, CustomThemeRules.parseStored(null))
        assertNull(CustomThemeRules.parseDraft(listOf("#123456")))
        assertNull(CustomThemeRules.parseDraft(List(5) { if (it == 2) "wrong" else "#123456" }))
        assertEquals(AppColorPalette.DEFAULT, AppearanceSettings().colorPalette)
    }

    @Test fun arbitraryPaletteKeepsTextReadableIncludingAllWhiteAndAllBlack() {
        val samples = listOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF777777.toInt(), 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())
        for (background in samples) {
            for (accent in samples) {
                val scheme = customColorScheme(CustomThemeColors(accent, accent, accent, background, background))
                listOf(
                    scheme.onPrimary to scheme.primary, scheme.onSecondary to scheme.secondary,
                    scheme.onTertiary to scheme.tertiary, scheme.onSurface to scheme.surface,
                    scheme.onBackground to scheme.background, scheme.onSurfaceVariant to scheme.surfaceVariant,
                    scheme.onPrimaryContainer to scheme.primaryContainer, scheme.onError to scheme.error,
                    scheme.onErrorContainer to scheme.errorContainer, scheme.primary to scheme.surface,
                ).forEach { (text, fill) -> assertTrue("$text on $fill", CustomThemeRules.contrast(text, fill) >= 4.5f) }
            }
        }
        assertEquals(Color.White, CustomThemeRules.foreground(Color.Black))
    }
}
