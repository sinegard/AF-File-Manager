package com.affilemanager.app.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Info
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AppearanceRolesUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun transparentPopupTextFollowsTheVisiblePageAndDialogDim() {
        val background = mutableStateOf(Color.Black)
        val transparency = mutableStateOf(100)
        val kind = mutableStateOf(0)
        var windowDim = 0f
        compose.setContent {
            val palette = CustomThemeColors(background = if (background.value == Color.Black) 0xff000000.toInt() else 0xffffffff.toInt(),
                popup = if (background.value == Color.Black) 0xffffffff.toInt() else 0xff000000.toInt())
            AFFileManagerTheme(AppearanceSettings(colorPalette = AppColorPalette.CUSTOM, customColors = palette,
                cardTransparency = transparency.value, transparentMenus = true)) {
                AppearancePage { Text("page") }
                val label: @androidx.compose.runtime.Composable () -> Unit = {
                    val attributes = (LocalView.current.parent as? DialogWindowProvider)?.window?.attributes
                    val dim = if (attributes != null && attributes.flags and android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
                        attributes.dimAmount else 0f
                    SideEffect { windowDim = dim }
                    Text("popup", Modifier.testTag("transparent_popup_text"))
                }
                when (kind.value) {
                    0 -> AfAlertDialog({}, {}, text = label)
                    1 -> com.affilemanager.app.ui.components.AfModalDialog("Popup", androidx.compose.material.icons.Icons.Rounded.Info,
                        {}, actions = {}) { label() }
                    else -> AfDropdownMenu(true, {}) { label() }
                }
            }
        }
        for (mode in 0..2) for (page in listOf(Color.Black, Color.White)) for (percent in listOf(100, 40, 0)) {
            compose.runOnIdle { kind.value = mode; background.value = page; transparency.value = percent }
            compose.waitForIdle()
            val backdrop = Color.Black.copy(alpha = windowDim).compositeOver(page)
            val popup = (if (page == Color.Black) Color.White else Color.Black).copy(alpha = 1f - percent / 100f)
            assertReadable("transparent_popup_text", popup.compositeOver(backdrop))
        }
    }

    @Test fun textFollowsItsOwnCardButtonAndDialogEvenWithOppositePageColors() {
        val selected = mutableStateOf(CustomThemeColors(background = 0xff000000.toInt(), surface = 0xffffffff.toInt(),
            popup = 0xff000000.toInt(), controls = 0xffffffff.toInt()))
        val popup = mutableStateOf(false)
        compose.setContent {
            AFFileManagerTheme(AppearanceSettings(colorPalette = AppColorPalette.CUSTOM, customColors = selected.value)) {
                AppearancePage {
                    Column {
                        Text("page", Modifier.testTag("page_text"))
                        AfCard { Text("card", Modifier.testTag("card_text")) }
                        AfButton({ popup.value = true }) { Text("button", Modifier.testTag("button_text")) }
                        AfFilterChip(true, {}, { Text("chip", Modifier.testTag("chip_text")) })
                    }
                }
                if (popup.value) AfAlertDialog({ popup.value = false }, {
                    AfButton({}) { Text("confirm", Modifier.testTag("popup_button_text")) }
                },
                    text = { Text("popup", Modifier.testTag("popup_text")) })
            }
        }
        repeat(2) {
            val palette = selected.value
            assertReadable("page_text", Color(palette.background))
            assertReadable("card_text", Color(palette.surface))
            assertReadable("button_text", Color(palette.controls))
            assertReadable("chip_text", Color(palette.controls))
            compose.onNodeWithText("button").performClick()
            assertReadable("popup_text", Color(palette.popup))
            assertReadable("popup_button_text", Color(palette.controls))
            compose.runOnIdle { popup.value = false; selected.value = selected.value.copy(background = 0xffffffff.toInt(),
                surface = 0xff000000.toInt(), popup = 0xffffffff.toInt(), controls = 0xff000000.toInt()) }
        }
    }

    private fun assertReadable(tag: String, fill: Color) {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().config[
            SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
        assertTrue(layouts.isNotEmpty())
        layouts.forEach { assertTrue("$tag: ${it.layoutInput.style.color} on $fill",
            CustomThemeRules.contrast(it.layoutInput.style.color, fill) >= 4.5f) }
    }
}
