package com.affilemanager.app.ui.components

import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.UiTranslationCatalog
import com.affilemanager.app.ui.localization.UiTranslator
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class DirectoryEntryFilterDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun everyLanguageWrapsFilterChoicesWithoutReturningToAOneItemPerRowList() {
        initializeCatalog()
        val language = mutableStateOf(AppLanguageManager.ENGLISH)
        val fontScale = mutableFloatStateOf(1f)
        val widthDp = mutableIntStateOf(320)
        compose.setContent {
            val requestedLanguage = language.value
            val requestedFontScale = fontScale.floatValue
            val requestedWidth = widthDp.intValue
            key(requestedLanguage, requestedFontScale, requestedWidth) {
                val locale = Locale.forLanguageTag(requestedLanguage)
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density.density, requestedFontScale),
                    LocalLayoutDirection provides if (
                        android.text.TextUtils.getLayoutDirectionFromLocale(locale) == View.LAYOUT_DIRECTION_RTL
                    ) LayoutDirection.Rtl else LayoutDirection.Ltr,
                ) {
                    MaterialTheme {
                        Box(modifier = androidx.compose.ui.Modifier.width(requestedWidth.dp)) {
                            DirectoryEntryFilterChoices(
                                selected = emptySet(),
                                onSelectedChange = {},
                                label = { option -> Text(UiTranslator.translate(option.label, requestedLanguage)) },
                            )
                        }
                    }
                }
            }
        }

        AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.forEach { tag ->
            listOf(320, 720).forEach { width ->
                listOf(1f, 1.5f).forEach { scale ->
                    compose.runOnIdle {
                        language.value = tag
                        fontScale.floatValue = scale
                        widthDp.intValue = width
                    }
                    compose.waitForIdle()

                when (tag) {
                    AppLanguageManager.ENGLISH -> {
                        compose.onNodeWithText("All files").assertIsDisplayed()
                        compose.onNodeWithText("Visi failai").assertDoesNotExist()
                    }
                    AppLanguageManager.LITHUANIAN -> {
                        compose.onNodeWithText("Visi failai").assertIsDisplayed()
                        compose.onNodeWithText("All files").assertDoesNotExist()
                    }
                }

                val chipBounds = DirectoryEntryFilter.entries.map { option ->
                    compose.onNodeWithTag("directory_filter_${option.name.lowercase()}")
                        .fetchSemanticsNode().boundsInRoot
                }
                val rows = chipBounds.map { it.top.roundToInt() }.distinct()
                    assertTrue("$tag/$width/$scale filter remained a vertical list: $rows", rows.size < DirectoryEntryFilter.entries.size)
                    assertTrue("$tag/$width/$scale filter choices overlap", chipBounds.zipWithNext().none { (first, second) ->
                        first.overlaps(second)
                    })

                compose.onAllNodes(
                    SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().forEach { node ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    layouts.forEach { layout ->
                        assertTrue(
                            "$tag/$width/$scale clipped filter text: ${layout.layoutInput.text}",
                            !layout.multiParagraph.didExceedMaxLines &&
                                layout.multiParagraph.height <= node.boundsInRoot.height + 1f,
                        )
                    }
                }
            }
        }
    }
    }

    private fun initializeCatalog() {
        UiTranslationCatalog.initialize(ApplicationProvider.getApplicationContext<AFFileManagerApplication>())
    }
}
