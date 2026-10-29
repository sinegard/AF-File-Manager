package com.affilemanager.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.AppSection
import com.affilemanager.app.ui.MainViewModel
import org.junit.Rule
import org.junit.Test

class NearbyWindowRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun receiverWindowSurvivesActivityRecreationWithoutPersistingPairingSecrets() {
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.setSection(AppSection.SHARE) }
        compose.onNodeWithTag("nearby_main_receive").performScrollTo().performClick()
        compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("nearby_receive_dialog").assertIsDisplayed()
        compose.onNodeWithTag("nearby_receive_close").performClick()
    }
}
