package com.affilemanager.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DirectoryDisplayRulesTest {
    @Test
    fun validListAndGridSettingsAreKeptUnchanged() {
        val settings = DirectoryDisplaySettings(
            layoutMode = DirectoryLayoutMode.GRID,
            iconScalePercent = 120,
            spacingScalePercent = 80,
            gridColumns = 6,
            showThumbnails = true,
        )

        assertEquals(settings, DirectoryDisplayRules.requireValid(settings))
    }

    @Test
    fun valuesOutsideSupportedRangesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryDisplayRules.requireValid(DirectoryDisplaySettings(iconScalePercent = 69))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryDisplayRules.requireValid(DirectoryDisplaySettings(spacingScalePercent = 141))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryDisplayRules.requireValid(DirectoryDisplaySettings(gridColumns = 9))
        }
    }

    @Test fun orientationsHaveIndependentValuesAndOldSettingsKeepTheirColumns() {
        val settings = DirectoryDisplaySettings(gridColumns = 3, gridColumnsLandscape = 7)
        DirectoryDisplayRules.requireValid(settings)
        assertEquals(3, DirectoryDisplayRules.columns(settings, landscape = false))
        assertEquals(7, DirectoryDisplayRules.columns(settings, landscape = true))
        assertEquals(5, DirectoryDisplayRules.columns(DirectoryDisplaySettings(gridColumns = 5), landscape = true))
        assertThrows(IllegalArgumentException::class.java) {
            DirectoryDisplayRules.requireValid(settings.copy(gridColumnsLandscape = 9))
        }
    }
}
