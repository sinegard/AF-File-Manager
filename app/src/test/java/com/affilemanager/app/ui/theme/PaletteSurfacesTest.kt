package com.affilemanager.app.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.*
import org.junit.Test

class PaletteSurfacesTest {
    @Test fun surfaceRolesFollowTheSelectedSecondaryInsteadOfMaterialDefaults() {
        val blue = darkColorScheme(surface = Color(0xFF111827), secondary = Color(0xFF8AABDF)).withPaletteSurfaces()
        val red = darkColorScheme(surface = Color(0xFF111827), secondary = Color(0xFFDF8A8A)).withPaletteSurfaces()
        assertNotEquals(blue.surfaceContainer, red.surfaceContainer)
        assertNotEquals(blue.surfaceContainerHighest, red.surfaceContainerHighest)
        assertTrue(CustomThemeRules.contrast(blue.onSecondaryContainer, blue.secondaryContainer) >= 4.5f)
        val black = blue.withAmoledBackground()
        assertEquals(Color.Black, black.background)
        assertEquals(Color.Black, black.surface)
        assertTrue(black.surfaceContainerHighest.blue > black.surfaceContainerHighest.red)
    }
}
