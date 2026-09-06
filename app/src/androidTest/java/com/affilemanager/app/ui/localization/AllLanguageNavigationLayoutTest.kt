package com.affilemanager.app.ui.localization

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.MainActivity
import com.affilemanager.app.data.DirectoryDisplaySettings
import com.affilemanager.app.data.DirectoryLayoutMode
import com.affilemanager.app.data.HomeDisplayArea
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.theme.AppColorPalette
import com.affilemanager.app.ui.theme.AppThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AllLanguageNavigationLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun everyShippedLanguageKeepsNavigationReadableAndClickable() {
        val sections = listOf("files", "analyze", "connections", "share", "tools")
        val captures = setOf("en", "lt", "de", "ar", "he", "hi", "zh")
        val failures = mutableListOf<String>()
        val languages = InstrumentationRegistry.getArguments().getString("afAuditLanguages")?.split(',')
            ?: AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
        val originalStorageSettings = ViewModelProvider(compose.activity)[MainViewModel::class.java].storageHomeDisplaySettings.value
        try {
            languages.forEach { language ->
                android.util.Log.i("AFLayoutAudit", "Checking navigation language=$language")
                compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, language) }
                try {
                    compose.waitUntil(10_000) {
                        AppLanguageManager.normalizeLanguageTag(compose.activity.resources.configuration.locales[0].language) == language &&
                            compose.onAllNodesWithTag("nav_files").fetchSemanticsNodes().isNotEmpty()
                    }
                } catch (timeout: ComposeTimeoutException) {
                    throw AssertionError("Requested $language, actual configuration: ${compose.activity.resources.configuration.locales.toLanguageTags()}", timeout)
                }
                compose.runOnUiThread {
                    val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                    model.setColorPalette(AppColorPalette.DEFAULT)
                    model.setThemeMode(AppThemeMode.LIGHT)
                    model.setHomeDisplaySettings(HomeDisplayArea.STORAGE,
                        DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.GRID, gridColumns = 2))
                }
                compose.waitForIdle()
                try {
                    compose.onNodeWithTag("nav_files").performClick()
                    val heading = compose.onNodeWithTag("home_storage_heading").performScrollTo().assertIsDisplayed()
                    val headingLayouts = mutableListOf<TextLayoutResult>()
                    heading.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!.invoke(headingLayouts)
                    headingLayouts.forEach {
                        assertTrue("$language storage heading squeezed into ${it.lineCount} lines", it.lineCount <= 3)
                    }
                    listOf("root_storage_location", "home_tool_trash", "home_tool_plans").forEach { tileTag ->
                        compose.onNodeWithTag(tileTag).performScrollTo().assertIsDisplayed()
                        compose.onAllNodes(
                            hasAnyAncestor(hasTestTag(tileTag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes().forEach { node ->
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                            layouts.forEach {
                                assertTrue("$language/$tileTag tile cut text vertically: ${it.layoutInput.text}; paragraph=${it.multiParagraph.height}, bounds=${node.boundsInRoot}",
                                    it.multiParagraph.height <= node.boundsInRoot.height + 1f)
                            }
                        }
                    }
                    sections.forEach { section ->
                        val tag = "nav_$section"
                        val button = compose.onNodeWithTag(tag).assertIsDisplayed()
                        val textNodes = compose.onAllNodes(
                            hasAnyAncestor(hasTestTag(tag)) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                            useUnmergedTree = true,
                        ).fetchSemanticsNodes()
                        assertTrue("$language/$section missing navigation label", textNodes.isNotEmpty())
                        textNodes.forEach { node ->
                            val layouts = mutableListOf<TextLayoutResult>()
                            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                            layouts.forEach { layout ->
                                val clipped = layout.multiParagraph.didExceedMaxLines ||
                                    layout.multiParagraph.height > node.boundsInRoot.height + 1f ||
                                    (0 until layout.lineCount).any { line ->
                                        layout.isLineEllipsized(line) ||
                                            layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f
                                    }
                                assertFalse("$language/$section clipped navigation: ${layout.layoutInput.text}; size=${layout.size}, paragraph=${layout.multiParagraph.height}, bounds=${node.boundsInRoot}; lines=${(0 until layout.lineCount).map { layout.getLineLeft(it) to layout.getLineRight(it) }}", clipped)
                            }
                        }
                        button.performClick().assertIsSelected()
                        if (section == "tools") {
                            compose.onNodeWithTag("language_setting_current").performScrollTo().assertIsDisplayed()
                            compose.onNodeWithTag("change_language").performScrollTo().assertIsDisplayed()
                            val languageTitle = mutableListOf<TextLayoutResult>()
                            compose.onNodeWithTag("language_setting_title").fetchSemanticsNode()
                                .config[SemanticsActions.GetTextLayoutResult].action!!.invoke(languageTitle)
                            languageTitle.forEach {
                                assertTrue("$language language heading squeezed into ${it.lineCount} lines", it.lineCount <= 2)
                            }
                        }
                    }
                    val icons = sections.map { compose.onNodeWithTag("nav_icon_$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center }
                    val wide = compose.activity.resources.configuration.screenWidthDp >= 900
                    val positions = icons.map { if (wide) it.x else it.y }
                    assertTrue("$language navigation icons lost their common alignment: $positions", positions.max() - positions.min() <= 1.1f)
                    compose.onNodeWithTag("nav_files").performClick().assertIsSelected()
                } catch (failure: AssertionError) {
                    failures += "$language: ${failure.message}"
                    capture(language, "failed")
                }
                if (language in captures) capture(language, "sample")
            }
            assertTrue("Navigation failures:\n${failures.joinToString("\n")}", failures.isEmpty())
        } finally {
            compose.runOnUiThread {
                ViewModelProvider(compose.activity)[MainViewModel::class.java]
                    .setHomeDisplaySettings(HomeDisplayArea.STORAGE, originalStorageSettings)
                AppLanguageManager.setLanguage(compose.activity, AppLanguageManager.ENGLISH)
            }
        }
    }

    private fun capture(language: String, suffix: String) {
        val context = compose.activity
        val metrics = context.resources.displayMetrics
        val scale = context.resources.configuration.fontScale
        val root = File(requireNotNull(context.getExternalFilesDir("validation")), "language-navigation").apply { mkdirs() }
        compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().let { bitmap ->
            File(root, "$language-${metrics.widthPixels}-$scale-$suffix.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
}
