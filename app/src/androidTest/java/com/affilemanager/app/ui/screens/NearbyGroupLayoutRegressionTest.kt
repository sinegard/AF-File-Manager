package com.affilemanager.app.ui.screens

import android.content.res.Configuration
import android.os.LocaleList
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
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.transfer.LanTransferState
import com.affilemanager.app.transfer.NearbyGroupController
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class NearbyGroupLayoutRegressionTest {
    @get:Rule val compose = createComposeRule()
    @Test fun groupReceivingButtonsAreAlignedAndReachableInLongAndRtlLanguages() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = androidx.lifecycle.ViewModelStore()
        val locale = mutableStateOf("en")
        val vm = MainViewModel(app).also { store.put("layout", it) }
        try {
            NearbyGroupController.leave()
            compose.setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale(locale.value))); screenWidthDp = 360; screenHeightDp = 780
                }
                val density = LocalDensity.current
                CompositionLocalProvider(LocalConfiguration provides configuration,
                    LocalDensity provides Density(density.density, 1.5f),
                    LocalLayoutDirection provides if (locale.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    MaterialTheme { NearbyPhoneTransferCard(viewModel = vm, receiveDirectory = app.cacheDir.path,
                        lanState = LanTransferState(), receiverName = "Phone", onReceiverNameChange = {},
                        durationMinutes = 15, onDurationMinutesChange = {}) }
                }
            }
            compose.onNodeWithTag("nearby_group_button").performClick()
            for (language in listOf("en", "lt", "de", "ar")) {
                compose.runOnIdle { locale.value = language }
                compose.onNodeWithTag("nearby_group_host").performScrollTo().assertIsDisplayed()
                val network = compose.onNodeWithTag("nearby_group_host").fetchSemanticsNode().boundsInRoot
                val direct = compose.onNodeWithTag("nearby_group_receive_wifi_direct").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertEquals(language, network.top, direct.top, 1f)
                assertEquals(language, network.bottom, direct.bottom, 1f)
                assertEquals(language, network.width, direct.width, 1f)
                assertTrue(network.width >= 100f && network.height >= 48f)
                compose.onNodeWithTag("nearby_group_pairing_input").performScrollTo().assertIsDisplayed()
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val snapshot = java.io.File(app.getExternalFilesDir("validation"), "issue240-group-$language.png")
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    snapshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
        } finally {
            compose.runOnUiThread { store.clear() }
            NearbyGroupController.leave()
        }
    }
}
