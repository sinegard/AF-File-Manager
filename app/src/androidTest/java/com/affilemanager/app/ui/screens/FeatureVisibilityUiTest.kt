package com.affilemanager.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.FeatureVisibility
import com.affilemanager.app.data.OptionalFeature
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.components.DirectoryBrowserToolbar
import com.affilemanager.app.ui.components.LocalFeatureVisibility
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FeatureVisibilityUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun individualToolbarChoicesApplyLiveButKeepTheOverflowAndTitle() {
        val settings = mutableStateOf(FeatureVisibility())
        val activeSearch = mutableStateOf(false)
        compose.setContent {
            AFFileManagerTheme {
                CompositionLocalProvider(LocalFeatureVisibility provides settings.value) {
                    DirectoryBrowserToolbar("Folder", "/path", true, true, true, activeSearch.value, false, "test",
                        {}, {}, {}, {}, {}, {}, actions = {
                            IconButton({}, Modifier.testTag("overflow")) { Text("...") }
                        })
                }
            }
        }
        compose.onNodeWithTag("directory_search_test").assertIsDisplayed()
        compose.runOnIdle { settings.value = FeatureVisibility(setOf(OptionalFeature.TOOLBAR_SEARCH, OptionalFeature.TOOLBAR_LAYOUT, OptionalFeature.TOOLBAR_FORWARD)) }
        compose.onNodeWithTag("directory_search_test").assertDoesNotExist()
        compose.onNodeWithTag("directory_layout_test").assertDoesNotExist()
        compose.onNodeWithContentDescription("Forward").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsDisplayed()
        compose.onNodeWithTag("overflow").assertIsDisplayed()
        compose.onNodeWithText("Folder").assertIsDisplayed()
        compose.runOnIdle { activeSearch.value = true }
        compose.onNodeWithTag("directory_search_test").assertIsDisplayed()
        compose.runOnIdle { settings.value = FeatureVisibility() }
        compose.onNodeWithTag("directory_layout_test").assertIsDisplayed()
        compose.onNodeWithContentDescription("Forward").assertIsDisplayed()
    }

    @Test fun settingsStartCollapsedAndKeepTheRequestedSectionOrder() {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val store = ViewModelStore()
        val vm = MainViewModel(app).also { store.put("settings-test", it) }
        try {
            compose.setContent { AFFileManagerTheme { ToolsScreen(vm, PaddingValues(), {}, {}) } }
            val appearance = compose.onNodeWithTag("settings_section_appearance").fetchSemanticsNode().boundsInRoot.top
            val actions = compose.onNodeWithTag("settings_section_actions").fetchSemanticsNode().boundsInRoot.top
            val background = compose.onNodeWithTag("settings_section_background").fetchSemanticsNode().boundsInRoot.top
            val privacy = compose.onNodeWithTag("settings_section_privacy").fetchSemanticsNode().boundsInRoot.top
            assertTrue(appearance < actions && actions < background && background < privacy)
            compose.onNodeWithTag("change_language").assertDoesNotExist()
            compose.onNodeWithTag("settings_section_appearance").performClick()
            compose.onNodeWithTag("change_language").assertIsDisplayed()
            compose.onNodeWithTag("tools_list").performScrollToNode(hasTestTag("feature_visibility_settings"))
            compose.onNodeWithTag("feature_network").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("tools_list").performScrollToIndex(0)
            compose.onNodeWithTag("settings_section_appearance").performClick()
            compose.onNodeWithTag("feature_visibility_settings").assertDoesNotExist()
        } finally { compose.runOnUiThread { store.clear() } }
    }
}
