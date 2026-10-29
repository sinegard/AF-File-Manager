package com.affilemanager.app.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
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
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class NavigationLabelLayoutTest {
    @get:Rule val compose = createComposeRule()
    @Test fun navigationLanguagesOneAtAllTextScales() = checkBatch(0)
    @Test fun navigationLanguagesTwoAtAllTextScales() = checkBatch(1)
    @Test fun navigationLanguagesThreeAtAllTextScales() = checkBatch(2)
    @Test fun navigationLanguagesFourAtAllTextScales() = checkBatch(3)

    private fun checkBatch(batch: Int) {
        val language = mutableStateOf("en")
        val scale = mutableFloatStateOf(1f)
        val selected = mutableIntStateOf(0)
        val labels = listOf("Failai", "Analizė", "Ryšiai", "Bendrinti", "Daugiau")
        var clicks = 0
        compose.setContent {
            val locale = Locale.forLanguageTag(language.value)
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale.floatValue),
                LocalLayoutDirection provides if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL)
                    LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme {
                    NavigationBar(Modifier.width(320.dp)) {
                        AfNavigationRow(labels) { index ->
                            val label = labels[index]
                            NavigationBarItem(selected = selected.intValue == index,
                                onClick = { selected.intValue = index; clicks++ },
                                modifier = Modifier.testTag("navigation_fixture_$index"),
                                icon = { Icon(Icons.Rounded.Folder, null, Modifier.testTag("navigation_icon_$index")) },
                                label = { NavigationLabel(label) })
                        }
                    }
                }
            }
        }
        val requested = androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("afAuditLanguages")?.split(',')
            ?: AppLanguageManager.SUPPORTED_LANGUAGE_TAGS
        val languages = requested.chunked(15)[batch]
        languages.forEach { tag ->
            listOf(1f, 1.5f, 2f).forEach { fontScale ->
                compose.runOnIdle { language.value = tag; scale.floatValue = fontScale }
                compose.waitForIdle()
                labels.indices.forEach { index ->
                    val owner = compose.onNodeWithTag("navigation_fixture_$index").assertIsDisplayed()
                    val bounds = owner.fetchSemanticsNode().boundsInRoot
                    val texts = compose.onAllNodes(hasAnyAncestor(hasTestTag("navigation_fixture_$index")) and
                        SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes()
                    assertEquals("$tag/$fontScale missing label", 1, texts.size)
                    texts.forEach { node ->
                        val layouts = mutableListOf<TextLayoutResult>()
                        node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                        layouts.forEach { text ->
                            val clipped = text.multiParagraph.didExceedMaxLines ||
                                text.multiParagraph.height > node.boundsInRoot.height + 1f ||
                                (0 until text.lineCount).any { text.isLineEllipsized(it) || text.getLineRight(it) - text.getLineLeft(it) > text.size.width + 1f }
                            if (clipped) {
                                val directory = uiAuditDirectory(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
                                val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                                try { java.io.File(directory, "navigation-$tag-$fontScale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                                finally { bitmap.recycle() }
                            }
                            assertFalse("$tag/$fontScale clipped ${text.layoutInput.text}; size=${text.size}; paragraph=${text.multiParagraph.height}; node=${node.boundsInRoot}; lines=${(0 until text.lineCount).map { text.getLineLeft(it) to text.getLineRight(it) }}", clipped)
                            assertTrue("$tag/$fontScale label escaped its navigation cell", node.boundsInRoot.left >= bounds.left - 1f &&
                                node.boundsInRoot.right <= bounds.right + 1f)
                        }
                    }
                    owner.performClick().assertIsSelected()
                }
                val iconYs = labels.indices.map { compose.onNodeWithTag("navigation_icon_$it", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center.y }
                val twoRows = compose.onAllNodesWithTag("navigation_rows_2").fetchSemanticsNodes().isNotEmpty()
                val groups = if (twoRows) listOf(iconYs.take(3), iconYs.drop(3)) else listOf(iconYs)
                groups.forEach { row ->
                    if (row.max() - row.min() > 1.1f) {
                        val directory = uiAuditDirectory(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext)
                        val bitmap = compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap()
                        try { java.io.File(directory, "navigation-baseline-$tag-$fontScale.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
                        finally { bitmap.recycle() }
                    }
                    assertTrue("$tag/$fontScale icon baselines differ within a row; all=$iconYs", row.max() - row.min() <= 1.1f)
                }
                if (twoRows) assertTrue("$tag/$fontScale wrapped rows overlap", iconYs[3] > iconYs[0] + 1.1f)
            }
        }
        compose.runOnIdle { assertEquals(languages.size * 3 * labels.size, clicks) }
    }
}
