package com.affilemanager.app.ui.screens

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.data.TransferOptions
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.UiTranslationCatalog
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale

class TransferOptionsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun allNewControlsUseExistingTranslationsInEveryLanguagePack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        UiTranslationCatalog.initialize(context)
        for (language in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS) {
            for (label in listOf("Klausti", "Nustatymai", "Patikimai kopijuoti", "Patikimai perkelti", "Išsaugoti", "Nustatymų įrašyti nepavyko", "Simbolinė nuoroda nepalaikoma")) {
                val translated = UiTranslator.translate(label, language)
                assertTrue("$language/$label", translated.isNotBlank())
                if (language != "lt") assertNotEquals("$language retained Lithuanian", label, translated)
                if (language != "lt" && language != "en") {
                    assertNotEquals("$language fell back to English", UiTranslator.translate(label, "en"), translated)
                }
            }
        }
    }

    @Test fun copyAndMoveDialogsRemainReachableAcrossLanguagesAndShortLargeTextScreens() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val selected = mutableStateOf(Triple("en", 1f, false))
        compose.setContent {
            val (language, scale, moving) = selected.value
            val config = Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language))); fontScale = scale
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalContext provides context.createConfigurationContext(config),
                LocalConfiguration provides config,
                LocalDensity provides Density(density.density, scale),
                LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                AFFileManagerTheme {
                    key(language, scale, moving) {
                        TransferOptionsDialog(moving, TransferOptions.defaults(moving),
                            onDismiss = {}, onConfirm = { true })
                    }
                }
            }
        }
        val test = InstrumentationRegistry.getInstrumentation()
        val output = File(context.getExternalFilesDir("validation"), "transfer-layout-${System.nanoTime()}").apply { check(mkdirs()) }
        // The runner sets guest dimensions before launching the test Activity.
        // Changing wm size in this loop recreates the test-only host and removes
        // its setContent; that is a harness failure, not the shipping dialog.
        val landscape = context.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
                for (language in listOf("en", "lt", "de", "ar")) for (scale in listOf(1f, 2f)) for (moving in listOf(false, true)) {
                    compose.runOnIdle { selected.value = Triple(language, scale, moving) }
                    android.util.Log.i("AFTransferLayout", "case=$language/$scale/$landscape/$moving")
                    val ask = compose.onNodeWithTag("transfer_options_ask").performScrollTo()
                    // In compact windows the shared dialog has an outer scroller
                    // in addition to the form's inner scroller. Exercise the real
                    // nested touch path rather than stopping at inner scrollTo.
                    for (attempt in 0 until 6) {
                        if (runCatching { ask.assertIsDisplayed() }.isSuccess) break
                        compose.onNodeWithTag("transfer_options_dialog").performTouchInput { swipeUp() }
                    }
                    ask.assertIsDisplayed().assertIsOn()
                    ask.performClick().assertIsOff()
                    val confirm = compose.onNodeWithTag("transfer_options_confirm")
                    if (runCatching { confirm.assertIsDisplayed() }.isFailure) confirm.performScrollTo()
                    confirm.assertIsDisplayed()
                    val unclipped = confirm.getUnclippedBoundsInRoot()
                    val clipped = confirm.fetchSemanticsNode().boundsInRoot
                    assertEquals("Footer clipped for $language/$scale/$landscape/$moving",
                        unclipped.height.value * context.resources.displayMetrics.density, clipped.height, 2f)
                    val title = UiTranslator.translate(if (moving) "Patikimai perkelti" else "Patikimai kopijuoti", language)
                    val titleNode = compose.onNodeWithText(title, useUnmergedTree = true)
                    if (runCatching { titleNode.assertIsDisplayed() }.isFailure) titleNode.performScrollTo()
                    titleNode.assertIsDisplayed()
                    if (scale == 2f) {
                        val bitmap = requireNotNull(test.uiAutomation.takeScreenshot())
                        try { File(output, "$language-$landscape-$moving.png").outputStream().use {
                            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
                        } } finally { bitmap.recycle() }
                    }
                }
    }

    @Test fun overwritingPoliciesKeepConfirmationVisibleAndEnabledOnlyForSafeChoices() {
        compose.setContent {
            AFFileManagerTheme {
                TransferOptionsDialog(true, TransferOptions.defaults(true).copy(askBeforeTransfer = false), onDismiss = {}, onConfirm = { true })
            }
        }
        compose.onNodeWithTag("transfer_options_ask").performScrollTo().assertIsOff().assertIsEnabled()
        compose.onNodeWithText("Replace existing").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_options_ask").performScrollTo().assertIsOn().assertIsNotEnabled()
        compose.onNodeWithText("Merge folders").performScrollTo().performClick()
        compose.onNodeWithTag("transfer_options_ask").performScrollTo().assertIsOn().assertIsNotEnabled()
    }

    @Test fun failedSaveKeepsTheDraftShowsAnErrorAndAllowsOneExplicitRetry() {
        val allowSave = mutableStateOf(false)
        var calls = 0
        compose.setContent {
            AFFileManagerTheme {
                TransferOptionsDialog(false, TransferOptions.defaults(false), onDismiss = {}, onConfirm = {
                    calls++
                    allowSave.value
                })
            }
        }
        compose.onNodeWithTag("transfer_options_ask").performScrollTo().performClick().assertIsOff()
        compose.onNodeWithTag("transfer_options_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("transfer_options_error").assertIsDisplayed()
        compose.onNodeWithTag("transfer_options_ask").performScrollTo().assertIsOff()
        compose.runOnIdle { assertEquals(1, calls); allowSave.value = true }
        compose.onNodeWithTag("transfer_options_confirm").assertIsDisplayed().performClick()
        compose.onNodeWithTag("transfer_options_error").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, calls) }
    }
}
