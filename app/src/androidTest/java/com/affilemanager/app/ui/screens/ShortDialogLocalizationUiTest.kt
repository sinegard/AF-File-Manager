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
import com.affilemanager.app.data.HomeCustomization
import com.affilemanager.app.ui.localization.UiTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ShortDialogLocalizationUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun diagnosticsTitlesKeepEveryGlyphAcrossLongAndRtlLocales() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val selected = mutableStateOf("de" to 1.5f)
        compose.setContent {
            val (language, scale) = selected.value
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language))); fontScale = scale
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                androidx.compose.ui.platform.LocalContext provides context.createConfigurationContext(configuration),
                LocalConfiguration provides configuration, LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { com.affilemanager.app.ui.theme.AFFileManagerTheme { TransferDiagnosticsDialog({}) } }
        }
        for (language in listOf("de", "en", "lt", "ar")) for (scale in listOf(1.5f, 2f)) {
            compose.runOnIdle { selected.value = language to scale }
            val title = compose.onNodeWithText(UiTranslator.translate("Perdavimo diagnostika", language), useUnmergedTree = true)
                .assertIsDisplayed().fetchSemanticsNode()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(title.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts))
            assertTrue(layouts.isNotEmpty())
            val test = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
            try { java.io.File(context.getExternalFilesDir("validation"), "diagnostics-title-$language-$scale.png")
                .outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
            for (layout in layouts) {
                val widths = (0 until layout.lineCount).map { layout.getLineRight(it) - layout.getLineLeft(it) }
                assertTrue("$language/$scale title clips glyphs: size=${layout.size}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, widths=$widths",
                    !layout.multiParagraph.didExceedMaxLines && layout.multiParagraph.height <= layout.size.height + 1f &&
                        (0 until layout.lineCount).all { !layout.isLineEllipsized(it) && widths[it] <= layout.size.width + 1f })
            }
        }
    }

    @Test fun addShortcutFieldsUseTheWholeDialogAndActionsFitAcrossFourLanguages() {
        val language = mutableStateOf("en")
        compose.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale(language.value))) }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, 1.25f),
                LocalLayoutDirection provides if (language.value == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) { MaterialTheme {
                HomeCustomizationDialog(HomeCustomization(), emptyList(), "/storage/emulated/0", {},
                    { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> },
                    { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { _, _, _ -> true })
            } }
        }
        for (locale in listOf("en", "lt", "de", "ar")) {
            compose.runOnIdle { language.value = locale }
            fun translated(source: String) = UiTranslator.translate(source, locale)
            try {
                compose.waitUntil(5_000) {
                    runCatching { compose.onNodeWithText(translated("Pridėti failo ar aplanko nuorodą")).assertIsDisplayed(); true }.getOrDefault(false)
                }
            } catch (error: Throwable) {
                val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val artifact = java.io.File(instrumentation.targetContext.getExternalFilesDir("validation"), "short-dialog-$locale.png")
                instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                    artifact.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
                val button = compose.onNodeWithText(translated("Pridėti failo ar aplanko nuorodą")).fetchSemanticsNode().boundsInRoot
                val roots = compose.onAllNodes(isRoot()).fetchSemanticsNodes().map { it.boundsInRoot }
                throw AssertionError("locale=$locale, button=$button, roots=$roots", error)
            }
            compose.onNodeWithText(translated("Pridėti failo ar aplanko nuorodą")).assertIsDisplayed().performClick()
            val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
            assertEquals(2, fields.size)
            assertEquals(fields[0].boundsInRoot.width, fields[1].boundsInRoot.width, 1f)
            val addNode = compose.onNodeWithText(translated("Pridėti")).assertIsDisplayed().fetchSemanticsNode()
            val add = addNode.boundsInRoot
            val cancel = compose.onNodeWithText(translated("Atšaukti")).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val dialog = generateSequence(addNode) { it.parent }.last().boundsInRoot
            assertTrue(fields[0].boundsInRoot.width >= add.width + cancel.width)
            assertTrue(add.bottom <= dialog.bottom)
            assertTrue(cancel.bottom <= dialog.bottom)
            val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            val snapshot = java.io.File(instrumentation.targetContext.getExternalFilesDir("validation"), "short-dialog-passed-$locale.png")
            instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
                snapshot.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithText(translated("Atšaukti")).performClick()
        }
    }
}
