package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.NearbyGroupController
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class NearbyControlPlacementTest {
    @get:Rule val compose = createComposeRule()
    @Test fun bothFormsKeepConcurrencyBelowDurationWithoutChangingEitherMeaning() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("placement", it) }
        val language = mutableStateOf("en")
        val duration = mutableStateOf(15)
        val original = vm.shareScreenPreferences.value
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val service = automation.serviceInfo
        service.flags = service.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = service
        try {
            NearbyGroupController.leave()
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language.value))) }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, 1.5f),
                    LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme { NearbyPhoneTransferCard(vm, app.cacheDir.path, LanTransferState(),
                        receiverName = "Phone", onReceiverNameChange = {}, durationMinutes = duration.value,
                        onDurationMinutesChange = { duration.value = it }) }
                }
            }
            for ((open, durationTag) in listOf("nearby_group_button" to "nearby_group_duration", "nearby_main_receive" to "nearby_receive_duration")) {
                compose.runOnIdle { language.value = "en" }
                compose.onNodeWithTag(open).assertIsDisplayed().performClick()
                for (locale in listOf("en", "lt", "de", "ar")) {
                    compose.runOnIdle {
                        language.value = locale; duration.value = 15
                        vm.updateShareScreenPreferences { it.copy(nearbyConcurrentUploads = 1) }
                    }
                    compose.waitForIdle()
                    val slider = compose.onNodeWithTag("nearby_receive_concurrency")
                    slider.performScrollTo().assertIsDisplayed().assertIsEnabled()
                    val timer = compose.onNodeWithTag(durationTag)
                    assertTrue("$open/$locale order", timer.getUnclippedBoundsInRoot().bottom <=
                        compose.onNodeWithTag("nearby_receive_concurrency_control").getUnclippedBoundsInRoot().top)
                    slider.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(3f)) }
                    compose.waitUntil(5_000) { vm.shareScreenPreferences.value.nearbyConcurrentUploads == 3 }
                    assertEquals(15, duration.value)
                    capture(app, "$open-$locale-concurrency.png")
                    timer.performScrollTo().assertIsDisplayed().performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(2f)) }
                    assertEquals(10, duration.value)
                    assertEquals(3, vm.shareScreenPreferences.value.nearbyConcurrentUploads)
                    capture(app, "$open-$locale.png")
                }
                val keyboardVisible = automation.windows.any {
                    it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD && it.root?.isVisibleToUser == true
                }
                assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                automation.waitForIdle(200, 3000)
                if (keyboardVisible && compose.onAllNodesWithTag(durationTag).fetchSemanticsNodes().isNotEmpty()) {
                    // The first Back dismisses the observed IME; the next closes the dialog.
                    assertTrue(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
                }
                compose.waitUntil(5_000) { compose.onAllNodesWithTag(durationTag).fetchSemanticsNodes().isEmpty() }
            }
        } finally {
            compose.runOnIdle { vm.updateShareScreenPreferences { original } }
            compose.waitUntil(5_000) {
                app.graph.uiPreferences.loadShare(app.cacheDir.path, "Phone").nearbyConcurrentUploads == original.nearbyConcurrentUploads
            }
            compose.runOnIdle { store.clear() }
            NearbyGroupController.leave()
        }
    }

    private fun capture(app: AFFileManagerApplication, name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.waitForIdle(200, 3000)
        val bitmap = requireNotNull(automation.takeScreenshot())
        try { File(uiAuditDirectory(app), name).outputStream().use {
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { bitmap.recycle() }
    }
}
