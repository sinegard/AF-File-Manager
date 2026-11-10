package com.affilemanager.app.ui.screens

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.LanTransferController
import com.affilemanager.app.transfer.ManufacturerSharingActions
import com.affilemanager.app.transfer.ManufacturerSharingTarget
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class NearbyManufacturerSharingUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun receiveFormShowsVendorChoicesAndARealSettingsFallbackWithoutStartingTransfer() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        val honor = ManufacturerSharingTarget.HONOR
        assertTrue(honor.packages.all { app.packageManager.getLaunchIntentForPackage(it) == null })
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("external-sharing", it) }
        val initial = LanTransferController.state.value
        val language = mutableStateOf("en")
        try {
            compose.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.forLanguageTag(language.value))) }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides config, LocalDensity provides Density(density.density, 1.5f),
                    LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme { NearbyPhoneTransferCard(vm, app.cacheDir.path, initial,
                        receiverName = "Fixture", onReceiverNameChange = {}, durationMinutes = 0, onDurationMinutesChange = {}) }
                }
            }
            compose.onNodeWithTag("nearby_main_receive").assertIsDisplayed().performClick()
            for (locale in listOf("en", "lt", "de", "ar")) {
                compose.runOnIdle { language.value = locale }
                val entry = compose.onNodeWithTag("nearby_manufacturer_sharing").performScrollTo().assertIsDisplayed()
                entry.performTouchInput { click() }
                ManufacturerSharingTarget.entries.forEach { target ->
                    compose.onNodeWithTag("nearby_manufacturer_${target.id}").assertIsDisplayed()
                }
                capture(app, "manufacturer-menu-$locale.png")
                compose.onNodeWithTag("nearby_manufacturer_${honor.id}").performTouchInput { click() }
                val error = compose.onNodeWithTag("nearby_manufacturer_unavailable").performScrollTo().assertIsDisplayed()
                error.assertTextEquals("${honor.label}: ${UiTranslator.translate("Programos atidaryti nepavyko", locale)}")
                assertEquals(initial, LanTransferController.state.value)
                compose.onNodeWithTag("nearby_receive_duration").assertExists()
                capture(app, "manufacturer-unavailable-$locale.png")
            }
            compose.onNodeWithTag("nearby_manufacturer_settings").performScrollTo().performTouchInput { click() }
            waitForSettings()
            assertEquals(initial, LanTransferController.state.value)
            pressBack()
            compose.onNodeWithTag("nearby_receive_duration").assertExists()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }

    @Test fun launchHelperStartsARealExportedAppWithoutAnyFileGrants() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val dispatched = AtomicReference<Intent?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): Instrumentation.ActivityResult? {
                if (intent?.component?.packageName == "com.android.settings") dispatched.set(intent)
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            assertFalse(ManufacturerSharingActions.openInstalledPackage(app, "com.affilemanager.test.uninstalled"))
            assertTrue(ManufacturerSharingActions.openInstalledPackage(app, "com.android.settings"))
            waitForSettings()
            val intent = requireNotNull(dispatched.get())
            assertNull(intent.data)
            assertNull(intent.clipData)
            assertEquals(0, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            pressBack()
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    private fun waitForSettings() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (automation.rootInActiveWindow?.packageName?.toString() != "com.android.settings" &&
            SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50)
        assertEquals("com.android.settings", automation.rootInActiveWindow?.packageName?.toString())
    }

    private fun pressBack() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
        automation.waitForIdle(200, 4000)
    }

    private fun capture(app: AFFileManagerApplication, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val screenshot = requireNotNull(automation.takeScreenshot())
        try { File(uiAuditDirectory(app), name).outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { screenshot.recycle() }
    }
}
