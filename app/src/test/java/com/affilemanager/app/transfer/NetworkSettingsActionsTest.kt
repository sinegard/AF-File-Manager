package com.affilemanager.app.transfer

import org.junit.Assert.*
import org.junit.Test

class NetworkSettingsActionsTest {
    @Test fun modernWifiUsesTheSystemPanelAndOlderPhonesUseSettings() {
        assertEquals("android.settings.WIFI_SETTINGS", NetworkSettingsActions.candidates(NetworkSettingsTarget.WIFI, 26).first())
        assertEquals("android.settings.WIFI_SETTINGS", NetworkSettingsActions.candidates(NetworkSettingsTarget.WIFI, 28).first())
        for (sdk in listOf(29, 33, 36)) {
            assertEquals("android.settings.panel.action.WIFI", NetworkSettingsActions.candidates(NetworkSettingsTarget.WIFI, sdk).first())
        }
    }

    @Test fun everyChoiceHasABoundedPublicFallbackWithoutVendorComponents() {
        for (target in NetworkSettingsTarget.entries) for (sdk in listOf(26, 36)) {
            val actions = NetworkSettingsActions.candidates(target, sdk)
            assertTrue(actions.size in 2..3)
            assertEquals(actions.size, actions.distinct().size)
            assertEquals("android.settings.WIRELESS_SETTINGS", actions.last())
            assertTrue(actions.all { it.startsWith("android.settings.") && '/' !in it })
        }
    }
}
