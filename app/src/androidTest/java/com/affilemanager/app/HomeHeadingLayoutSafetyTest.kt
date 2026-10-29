package com.affilemanager.app

import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.localization.AppLanguageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class HomeHeadingLayoutSafetyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun anEnglishHomeHeadingKeepsItsInlineButtonsWhenItsWordsFit() {
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        check(compose.activity.resources.configuration.fontScale in .99f..1.01f)
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousScale = app.graph.appearance.settings.value.interfaceScalePercent
        try {
            compose.runOnUiThread {
                vm.setInterfaceScalePercent(100)
                AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
                vm.showFilesHome()
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.locales[0].language == "en" &&
                    compose.onAllNodesWithTag("home_locations_heading").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val heading = compose.onNodeWithTag("home_locations_heading").performScrollTo().assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val actions = compose.onNodeWithTag("home_header_actions").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Words that fit should retain the existing inline action group", heading.center.y, actions.center.y, 1f)
        } finally { compose.runOnUiThread { vm.setInterfaceScalePercent(previousScale) } }
    }

    @Test fun aGermanHomeHeadingKeepsItsWordWholeWhenTheFullCardCanFitIt() {
        check(Build.MODEL.contains("sdk", ignoreCase = true))
        check(compose.activity.resources.configuration.fontScale in .99f..1.01f)
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previousScale = app.graph.appearance.settings.value.interfaceScalePercent
        try {
            compose.runOnUiThread {
                vm.setInterfaceScalePercent(100)
                AppLanguageManager.setLanguage(compose.activity, "de")
                vm.showFilesHome()
            }
            compose.waitUntil(10_000) {
                compose.activity.resources.configuration.locales[0].language == "de" &&
                    compose.onAllNodesWithTag("home_locations_heading").fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            val title = compose.onNodeWithTag("home_locations_heading").performScrollTo().assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(title.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            val text = layouts.single()
            val frame = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
            try {
                File(uiAuditDirectory(compose.activity), "home-heading-de.png").outputStream().use {
                    check(frame.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { frame.recycle() }
            assertEquals("A single translated word was split beside the header buttons: ${text.layoutInput.text}", 1, text.lineCount)
            compose.onNodeWithTag("open_system_files").assertIsDisplayed()
            compose.onNodeWithTag("home_customize_direct").assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                vm.setInterfaceScalePercent(previousScale)
                AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
            }
        }
    }
}
