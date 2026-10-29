package com.affilemanager.app.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class SharedToolbarLayoutSafetyTest {
    @get:Rule val compose = createComposeRule()

    @Test fun selectingDoesNotMoveFileRowsUnderTheHeldPointer() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chosen = mutableStateOf(Triple("en", 1f, 320))
        val selected = mutableStateOf(false)
        compose.setContent {
            val (language, font, width) = chosen.value
            val config = Configuration(context.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(language)); fontScale = font
            }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context.createConfigurationContext(config),
                LocalConfiguration provides config, LocalDensity provides Density(density.density, font),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                key(language, font, width) {
                    AFFileManagerTheme {
                        Column(Modifier.width(width.dp)) {
                            StableDirectoryHeader(selected.value) {
                                if (selected.value) SelectionHeader(3, false, { selected.value = false }, {})
                                else DirectoryBrowserToolbar("Folder", "/folder", true, true, true, false, false,
                                    "stable", {}, {}, {}, {}, {}, {}) { DirectoryActionsButton({}) }
                            }
                            Text("File row", Modifier.testTag("stable-file-row"))
                        }
                    }
                }
            }
        }
        for (language in listOf("en", "ar")) for (font in listOf(1f, 2f)) for (width in listOf(220, 320)) {
            compose.runOnIdle { selected.value = false; chosen.value = Triple(language, font, width) }
            val before = compose.onNodeWithTag("stable-file-row").fetchSemanticsNode().boundsInRoot.top
            compose.runOnIdle { selected.value = true }
            val during = compose.onNodeWithTag("stable-file-row").fetchSemanticsNode().boundsInRoot.top
            assertEquals("$language/$font/$width selection moved rows", before, during, 1f)
            compose.runOnIdle { selected.value = false }
            val after = compose.onNodeWithTag("stable-file-row").fetchSemanticsNode().boundsInRoot.top
            assertEquals("$language/$font/$width closing selection moved rows", before, after, 1f)
        }
    }

    @Test fun narrowAndLargeTextToolbarsKeepTheTitleAndEveryActionReadable() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chosen = mutableStateOf(Triple("en", 1f, 320))
        compose.setContent {
            val (language, font, width) = chosen.value
            val locale = Locale.forLanguageTag(language)
            val config = Configuration(context.resources.configuration).apply { setLocale(locale); fontScale = font }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides context.createConfigurationContext(config), LocalConfiguration provides config,
                LocalDensity provides Density(density.density, font),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                key(language, font, width) {
                    AFFileManagerTheme {
                        Box(Modifier.width(width.dp)) {
                            DirectoryBrowserToolbar("Folder", "/folder", true, true, true, false, false, "layout-safety",
                                {}, {}, {}, {}, {}, {}, onOpenPath = {}) {
                                DirectoryActionsButton({}, Modifier.testTag("toolbar-more"))
                            }
                        }
                    }
                }
            }
        }
        for (language in listOf("en", "lt", "de", "ar")) for (font in listOf(1f, 2f)) for (width in listOf(220, 320)) {
            compose.runOnIdle { chosen.value = Triple(language, font, width) }
            val title = compose.onNodeWithText("Folder", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode()
            val layouts = mutableListOf<TextLayoutResult>()
            title.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
            assertTrue(layouts.isNotEmpty())
            layouts.forEach { layout ->
                assertFalse("$language/$font/$width lost the folder title", layout.multiParagraph.didExceedMaxLines || (0 until layout.lineCount).any(layout::isLineEllipsized))
            }
            val bounds = mutableListOf<androidx.compose.ui.geometry.Rect>()
            for (label in listOf("Atgal", "Pirmyn", "Aukštyn", "Greita paieška šiame aplanke", "Rodyti tinklelį")) {
                val node = compose.onNodeWithContentDescription(UiTranslator.translate(label, language)).assertIsDisplayed().fetchSemanticsNode()
                assertTrue("$language/$font/$width collapsed $label / ${node.boundsInRoot} / density=${context.resources.displayMetrics.density}", node.boundsInRoot.width >= 47f * context.resources.displayMetrics.density)
                bounds += node.boundsInRoot
            }
            val more = compose.onNodeWithTag("toolbar-more").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$language/$font/$width collapsed More / $more", more.width >= 47f * context.resources.displayMetrics.density)
            bounds += more
            for (i in bounds.indices) for (j in 0 until i) assertFalse("Toolbar controls overlap", bounds[i].overlaps(bounds[j]))
        }
    }

    @Test fun sharedStorageKindsKeepTheSameClicksLongPressAndAddressAction() {
        val identity = mutableStateOf("local_LEFT")
        val events = mutableListOf<String>()
        compose.setContent {
            key(identity.value) {
                AFFileManagerTheme {
                    Box(Modifier.width(320.dp)) {
                        DirectoryBrowserToolbar("Folder", "/folder", true, true, true, false, false, identity.value,
                            { events += "back" }, { events += "forward" }, { events += "up" }, { events += "search" },
                            { events += "layout" }, { events += "settings" }, onOpenPath = { events += "open:$it" }) {
                            DirectoryActionsButton({ events += "more" }, Modifier.testTag("toolbar-more"))
                        }
                    }
                }
            }
        }
        for (source in listOf("local_LEFT", "remote", "saf", "archive", "trash", "category", "advanced")) {
            compose.runOnIdle { identity.value = source; events.clear() }
            for (label in listOf("Back", "Forward", "Up", "Quick search in this folder")) compose.onNodeWithContentDescription(label).performClick()
            compose.onNodeWithTag("directory_layout_$source").performTouchInput { longClick() }
            compose.runOnIdle { assertEquals(listOf("back", "forward", "up", "search", "settings"), events) }
            compose.onNodeWithTag("directory_layout_$source").performClick()
            compose.onNodeWithTag("toolbar-more").performClick()
            compose.onNodeWithTag("directory_address_$source").performClick()
            compose.onNodeWithTag("directory_address_input").performTextReplacement("/chosen-folder")
            compose.onNodeWithTag("directory_address_open").performClick()
            compose.runOnIdle { assertEquals(listOf("back", "forward", "up", "search", "settings", "layout", "more", "open:/chosen-folder"), events) }
        }
    }
}
