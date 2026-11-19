package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.*
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class NearbySenderConsentUiTest {
    @get:Rule val compose = createComposeRule()
    private val request = NearbySenderRequest("local-decision-id", "Owned sender with a deliberately long device name for layout", "192.168.1.20")
    private val state = LanTransferState(status = LanTransferStatus.RUNNING, phoneReceiver = true,
        receiveSessionId = "owned-session", pendingSenders = listOf(request))

    @Test fun lockHidesAllDecisionControlsAndCancelNeverApproves() {
        var unlocked by mutableStateOf(false)
        var current by mutableStateOf(state)
        val decisions = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { NearbySenderApprovalDialog(current, unlocked) { session, id, allow ->
            assertEquals(state.receiveSessionId, session); assertEquals(request.id, id); decisions += allow
            current = current.copy(pendingSenders = emptyList())
        } } }
        compose.onNodeWithTag("nearby_sender_allow").assertDoesNotExist()
        compose.onNodeWithTag("nearby_sender_name").assertDoesNotExist()
        compose.runOnIdle { unlocked = true }
        compose.onNodeWithTag("nearby_sender_allow").assertIsDisplayed()
        compose.onNodeWithTag("nearby_sender_deny").performClick()
        compose.runOnIdle { assertEquals(listOf(false), decisions) }
        compose.onNodeWithTag("nearby_sender_allow").assertDoesNotExist()
    }

    @Test fun backRejectsTheRequestInsteadOfSilentlyApprovingIt() {
        var current by mutableStateOf(state)
        val decisions = mutableListOf<Boolean>()
        compose.setContent { MaterialTheme { NearbySenderApprovalDialog(current, true) { _, _, allow ->
            decisions += allow; current = current.copy(pendingSenders = emptyList())
        } } }
        compose.onNodeWithTag("nearby_sender_allow").assertIsDisplayed()
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(5000) { decisions.isNotEmpty() }
        compose.runOnIdle { assertEquals(listOf(false), decisions) }
    }

    @Test fun largeTextEnglishLithuanianAndRtlKeepSeparateReadableDecisionControls() {
        var language by mutableStateOf("en")
        compose.setContent {
            val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language))) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, 1.8f),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme { NearbySenderApprovalDialog(state, true) { _, _, _ -> } }
            }
        }
        for (tag in listOf("en", "lt", "ar")) {
            compose.runOnIdle { language = tag }
            compose.onNodeWithTag("nearby_sender_allow").assertIsDisplayed()
            compose.onNodeWithTag("nearby_sender_deny").assertIsDisplayed()
            compose.onNodeWithTag("nearby_sender_address").assertTextEquals(request.address)
            val allow = compose.onNodeWithTag("nearby_sender_allow").fetchSemanticsNode().boundsInRoot
            val deny = compose.onNodeWithTag("nearby_sender_deny").fetchSemanticsNode().boundsInRoot
            assertFalse("Approval and cancel overlap in $tag", allow.overlaps(deny))
            if (tag == "en") { compose.onNodeWithText("Confirm").assertExists(); compose.onNodeWithText("Patvirtinti").assertDoesNotExist() }
            if (tag == "lt") compose.onNodeWithText("Patvirtinti").assertExists()
            capture("receiver-consent-$tag-large.png")
        }
    }

    @Test fun discoverySelectionNeedsARealPrivateCodeAndCancelDoesNotConnect() {
        var open by mutableStateOf(true)
        var connects = 0
        val device = NearbyDiscoveredDevice("AF owned", "Owned receiver", "Android", "192.168.1.20", 8080)
        compose.setContent { MaterialTheme { if (open) NearbyPrivateCodeDialog(device, { open = false }) { connects++ } } }
        compose.onNodeWithTag("nearby_private_code_connect").assertIsNotEnabled()
        compose.onNodeWithTag("nearby_private_code").performTextInput("12345678")
        compose.onNodeWithTag("nearby_private_code_connect").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, connects) }
    }

    @Test fun privateCodeEntryPreservesTheSameMaximumAsExistingQrAndManualPairing() {
        var length: Int? = null
        val device = NearbyDiscoveredDevice("AF owned", "Owned receiver", "Android", "192.168.1.20", 8080)
        compose.setContent { MaterialTheme { NearbyPrivateCodeDialog(device, {}) { length = it.code.length } } }
        compose.onNodeWithTag("nearby_private_code").performTextInput("x".repeat(LanTransferOptions.MAX_PASSWORD_LENGTH))
        compose.onNodeWithTag("nearby_private_code_connect").performClick()
        compose.runOnIdle { assertEquals(LanTransferOptions.MAX_PASSWORD_LENGTH, length) }
    }

    private fun capture(name: String) {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(uiAuditDirectory(app), name).outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
    }
}
