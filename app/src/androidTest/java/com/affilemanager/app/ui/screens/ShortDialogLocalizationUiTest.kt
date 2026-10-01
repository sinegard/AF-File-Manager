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
