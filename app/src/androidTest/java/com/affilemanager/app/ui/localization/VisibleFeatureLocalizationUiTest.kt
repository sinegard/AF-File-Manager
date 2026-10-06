package com.affilemanager.app.ui.localization

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.MainActivity
import com.affilemanager.app.data.OptionalFeature
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Exercises real per-app locale changes, saved feature choices and the production settings UI. */
class VisibleFeatureLocalizationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun localeSwitchRefreshesEveryFeatureLabelWithoutClippingOrResettingChoices() {
        val initial = ViewModelProvider(compose.activity)[MainViewModel::class.java].featureVisibility.value
        try {
            compose.runOnUiThread {
                ViewModelProvider(compose.activity)[MainViewModel::class.java].setFeatureVisible(OptionalFeature.TERMINAL, false)
            }
            for (language in listOf("en", "lt", "de", "ar", "en")) {
                compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, language) }
                compose.waitUntil(10_000) {
                    AppLanguageManager.normalizeLanguageTag(compose.activity.resources.configuration.locales[0].language) == language &&
                        compose.onAllNodesWithTag("nav_tools").fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("nav_tools").performClick()
                if (compose.onAllNodesWithTag("feature_visibility_settings").fetchSemanticsNodes().isEmpty()) {
                    compose.onNodeWithTag("settings_section_appearance").performClick()
                }
                compose.onNodeWithTag("tools_list").performScrollToNode(hasTestTag("feature_visibility_settings"))
                val inFeatures = hasAnyAncestor(hasTestTag("feature_visibility_settings"))
                OptionalFeature.entries.forEach { feature ->
                    val translated = UiTranslator.translate(feature.label, language)
                    compose.onNode(inFeatures and hasText(translated), useUnmergedTree = true).assertExists()
                }
                compose.onNodeWithTag("feature_terminal").performScrollTo().assertIsOff()
                compose.onAllNodes(inFeatures and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                    layouts.forEach { layout ->
                        assertFalse("$language truncated ${layout.layoutInput.text}", layout.multiParagraph.didExceedMaxLines)
                        assertTrue("$language clipped ${layout.layoutInput.text}",
                            layout.multiParagraph.height <= node.size.height + 1f)
                    }
                }
                capture(language, "terminal")
                compose.onNodeWithTag("feature_nearby_share").performScrollTo().assertIsDisplayed()
                capture(language, "nearby")
            }
        } finally {
            compose.runOnUiThread {
                val model = ViewModelProvider(compose.activity)[MainViewModel::class.java]
                OptionalFeature.entries.forEach { model.setFeatureVisible(it, initial.isVisible(it)) }
                AppLanguageManager.setLanguage(compose.activity, "en")
            }
        }
    }

    private fun capture(language: String, section: String) {
        val context = compose.activity
        val root = File(requireNotNull(context.getExternalFilesDir("validation")), "feature-locales").apply { mkdirs() }
        val scale = context.resources.configuration.fontScale
        compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().let { bitmap ->
            File(root, "$language-$scale-$section.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
