package com.affilemanager.app.ui.preview

import android.app.Instrumentation
import android.content.Intent
import android.provider.Settings
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.mutableStateOf
import com.affilemanager.app.apk.ApkInstallMetadataReader
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AppearanceSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference

class AppInformationSystemNavigationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun systemInfoOpensTheChosenNonAfPackageAndReturnsToTheOriginalOpenAction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val selectedPackage = "com.android.settings"
        val apk = File(context.packageManager.getApplicationInfo(selectedPackage, 0).sourceDir)
        val seen = AtomicReference<String?>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent?): Instrumentation.ActivityResult? {
                if (intent?.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) seen.set(intent.dataString)
                return null
            }
        }
        var opened = false
        val shown = mutableStateOf(true)
        instrumentation.addMonitor(monitor)
        try {
            compose.setContent { AFFileManagerTheme(AppearanceSettings()) {
                if (shown.value) ApkInstallConfirmationDialog("selected-settings", { ApkInstallMetadataReader.standalone(context, apk) },
                    { shown.value = false }, { opened = true }, installedApp = true)
            } }
            compose.waitUntil(15000) {
                compose.onNodeWithTag("system_app_information").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not()
            }
            compose.onNodeWithTag("system_app_information").assertIsDisplayed().performTouchInput { click() }
            val automation = instrumentation.uiAutomation
            val deadline = android.os.SystemClock.elapsedRealtime() + 10000
            while (automation.rootInActiveWindow?.packageName?.toString() != selectedPackage && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(50)
            assertEquals("The Android app-info activity was not opened", selectedPackage, automation.rootInActiveWindow?.packageName?.toString())
            assertEquals("package:$selectedPackage", seen.get())
            assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            compose.onNodeWithTag("installed_app_information").assertIsDisplayed()
            compose.onNodeWithTag("open_installed_app").assertIsDisplayed().performTouchInput { click() }
            assertTrue("The original Open action was lost", opened)
        } finally { instrumentation.removeMonitor(monitor) }
    }
}
