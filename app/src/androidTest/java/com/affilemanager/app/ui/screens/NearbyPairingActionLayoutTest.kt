package com.affilemanager.app.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.ui.localization.UiTranslator
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class NearbyPairingActionLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun translatedMainActionsKeepWholeLabelsAndTheirOriginalEnabledStates() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selected = mutableStateOf("en" to 1.5f)
        val sendingEnabled = mutableStateOf(true)
        var sends = 0
        var receives = 0
        compose.setContent {
            val (language, scale) = selected.value
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language))); fontScale = scale
            }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                        Box(Modifier.width(284.dp).testTag("main_action_bounds")) {
                            NearbyMainActionButtons(sendingEnabled.value, { sends++ }, { receives++ })
                        }
                    }
                }
            }
        }
        for (language in listOf("en", "lt", "de", "ar")) for (scale in listOf(1.5f, 2f)) {
            compose.runOnIdle { selected.value = language to scale; sendingEnabled.value = true }
            val outer = compose.onNodeWithTag("main_action_bounds").fetchSemanticsNode().boundsInRoot
            val send = compose.onNodeWithTag("nearby_main_send").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            val receive = compose.onNodeWithTag("nearby_main_receive").assertIsDisplayed().assertIsEnabled().fetchSemanticsNode().boundsInRoot
            assertFalse("$language/$scale main actions overlap", send.overlaps(receive))
            for (action in listOf(send, receive)) assertTrue("$language/$scale action leaves its viewport",
                action.left >= outer.left - 1f && action.right <= outer.right + 1f)
            for (source in listOf("Siųsti", "Gauti")) {
                val text = compose.onNodeWithText(UiTranslator.translate(source, language), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertTrue(layouts.isNotEmpty())
                for (layout in layouts) {
                    assertFalse("$language/$scale main label is clipped", layout.multiParagraph.didExceedMaxLines ||
                        layout.multiParagraph.height > layout.size.height + 1f ||
                        (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) ||
                            layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f })
                    assertTrue("$language/$scale main label is squeezed", layout.lineCount <= 2)
                }
            }
            compose.onNodeWithTag("nearby_main_send").performClick()
            compose.onNodeWithTag("nearby_main_receive").performClick()
            compose.runOnIdle { sendingEnabled.value = false }
            compose.onNodeWithTag("nearby_main_send").assertIsNotEnabled()
            compose.onNodeWithTag("nearby_main_receive").assertIsEnabled()
            if (scale == 2f) {
                val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                try { java.io.File(context.getExternalFilesDir("validation"), "sharing-actions-$language.png")
                    .outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
        }
        assertEquals(8, sends)
        assertEquals(8, receives)
    }

    @Test fun translatedPairingActionsKeepWholeButtonsAndReadableTextAtLargeFonts() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selected = mutableStateOf("en" to 1.5f)
        var scans = 0
        var discoveries = 0
        compose.setContent {
            val (language, scale) = selected.value
            val configuration = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language))); fontScale = scale
            }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                MaterialTheme {
                    Box(Modifier.fillMaxSize().systemBarsPadding().padding(16.dp)) {
                    Box(Modifier.width(284.dp).testTag("pairing_action_bounds")) {
                        NearbyPairingActionButtons({ scans++ }, { discoveries++ })
                    }
                    }
                }
            }
        }
        for (language in listOf("en", "lt", "de", "ar")) for (scale in listOf(1.5f, 2f)) {
            compose.runOnIdle { selected.value = language to scale }
            val outer = compose.onNodeWithTag("pairing_action_bounds").fetchSemanticsNode().boundsInRoot
            val scan = compose.onNodeWithTag("nearby_scan_qr").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            val find = compose.onNodeWithTag("nearby_find_devices").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            for (action in listOf(scan, find)) {
                assertTrue("$language/$scale action is inside its viewport", action.left >= outer.left - 1f && action.right <= outer.right + 1f)
            }
            assertFalse("$language/$scale buttons overlap", scan.overlaps(find))
            for (source in listOf("Nuskaityti QR kodą", "Rasti artimus AF įrenginius")) {
                val text = compose.onNodeWithText(UiTranslator.translate(source, language), useUnmergedTree = true)
                    .assertIsDisplayed().fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(text.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts) == true)
                assertTrue(layouts.isNotEmpty())
                for (layout in layouts) {
                    val clipped = layout.multiParagraph.didExceedMaxLines || layout.multiParagraph.height > layout.size.height + 1f ||
                        (0 until layout.lineCount).any { line -> layout.isLineEllipsized(line) ||
                            layout.getLineRight(line) - layout.getLineLeft(line) > layout.size.width + 1f }
                    if (clipped) {
                        val snapshot = java.io.File(context.getExternalFilesDir("validation"), "pairing-overflow-$language.png")
                        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                        try { snapshot.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                        finally { bitmap.recycle() }
                    }
                    assertFalse("$language/$scale text is clipped: ${layout.layoutInput.text}, size=${layout.size}, " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, lines=${layout.lineCount}, " +
                        "maxLines=${layout.multiParagraph.didExceedMaxLines}", clipped)
                    assertTrue("$language/$scale text is needlessly squeezed", layout.lineCount <= 4)
                    for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                }
            }
            compose.onNodeWithTag("nearby_scan_qr").performClick()
            compose.onNodeWithTag("nearby_find_devices").performClick()
            if (scale == 2f) {
                val test = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
                val snapshot = java.io.File(context.getExternalFilesDir("validation"), "pairing-actions-$language.png")
                val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
                try { snapshot.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
            }
        }
        assertEquals(8, scans)
        assertEquals(8, discoveries)
    }
}
