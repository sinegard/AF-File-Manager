package com.affilemanager.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.NetworkSettingsTarget
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NetworkSettingsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allThreeActionsOpenSystemControlsAndReturnWithoutChangingTheSession() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val accessibility = automation.serviceInfo
        accessibility.flags = accessibility.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = accessibility
        val originalSession = LanTransferController.state.value
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) { NearbyNetworkSettingsButton() }
        } }
        for (target in NetworkSettingsTarget.entries) {
            compose.onNodeWithTag("nearby_network_settings").performClick()
            compose.onNodeWithTag("nearby_network_${target.name}").performClick()
            compose.waitUntil(10_000) { automation.windows.any { it.root?.packageName?.toString() == "com.android.settings" } }
            automation.waitForIdle(500, 5000)
            val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(uiAuditDirectory(app), "network-${target.name}.png").outputStream().use {
                check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
            } } finally { screenshot.recycle() }
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("nearby_network_settings").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("nearby_network_settings_error").assertDoesNotExist()
            assertEquals(originalSession, LanTransferController.state.value)
        }
    }
}
