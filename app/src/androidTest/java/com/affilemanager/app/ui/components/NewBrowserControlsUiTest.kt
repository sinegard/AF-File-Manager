package com.affilemanager.app.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.data.*
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class NewBrowserControlsUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun remoteMenuAndSelectionUseTheSameActionVisibilityWithoutDuplicatePopups() {
        val entry = com.affilemanager.app.network.RemoteEntry("remote.txt", "/remote.txt", false, 5L, null)
        val state = mutableStateOf(com.affilemanager.app.ui.NetworkUiState(entries = listOf(entry)))
        val visibility = mutableStateOf(FeatureVisibility(setOf(OptionalFeature.FILE_COPY, OptionalFeature.FILE_INFO, OptionalFeature.FILE_DELETE, OptionalFeature.FILE_RENAME)))
        var copies = 0
        compose.setContent { AFFileManagerTheme {
            CompositionLocalProvider(LocalFeatureVisibility provides visibility.value) {
                com.affilemanager.app.ui.screens.RemoteBrowser(state.value, "/local",
                    onBack = {}, onForward = {}, onUp = {}, onRefresh = {}, onOpen = {}, onDownload = {},
                    onToggleSelection = { path -> state.value = state.value.copy(selectedPaths = setOf(path)) },
                    onClearSelection = { state.value = state.value.copy(selectedPaths = emptySet()) }, onSelectAll = {},
                    onDownloadSelected = {}, onCopySelected = { copies++ }, localClipboardCount = 0,
                    onPasteLocalClipboard = {}, onChooseUpload = {}, onCreateFolder = {}, onRename = {}, onDelete = {},
                    onSync = {}, onToggleHidden = {}, onToggleGrid = {}, onSort = {}, onDisconnect = {})
            }
        } }
        compose.onNodeWithContentDescription("File actions: remote.txt").performClick()
        compose.onNodeWithText("Copy to phone").assertDoesNotExist()
        compose.onNodeWithText("Info").assertDoesNotExist()
        compose.onNodeWithText("Rename").assertDoesNotExist()
        compose.onNodeWithText("Delete").assertDoesNotExist()
        compose.onNodeWithText("Select").performClick()
        compose.onNodeWithContentDescription("File actions: remote.txt").assertDoesNotExist()
        compose.onNodeWithContentDescription("Copy").assertDoesNotExist()
        compose.onNodeWithContentDescription("Delete").assertDoesNotExist()
        compose.runOnIdle { visibility.value = FeatureVisibility() }
        compose.onNodeWithContentDescription("Copy").performClick()
        compose.runOnIdle { assertEquals(1, copies) }
    }

    @Test fun itemMenusAndSelectionButtonsUseTheSameLiveVisibilityAndCallbacks() {
        val visibility = mutableStateOf(FeatureVisibility())
        var calls = 0
        compose.setContent { AFFileManagerTheme {
            CompositionLocalProvider(LocalFeatureVisibility provides visibility.value) {
                Column { BrowserAction.entries.forEach { action ->
                    BrowserActionIconButton(action, { calls++ }, Modifier.testTag("button_${action.name}")) { Text(action.name) }
                    BrowserActionMenuItem(action, { Text(action.name) }, { calls++ }, Modifier.testTag("menu_${action.name}"))
                } }
            }
        } }
        BrowserAction.entries.forEach { action ->
            compose.runOnIdle { visibility.value = FeatureVisibility(setOf(action.feature)) }
            compose.onNodeWithTag("button_${action.name}").assertDoesNotExist()
            compose.onNodeWithTag("menu_${action.name}").assertDoesNotExist()
        }
        compose.runOnIdle { visibility.value = FeatureVisibility() }
        compose.onNodeWithTag("button_COPY").performClick()
        compose.onNodeWithTag("menu_COPY").performClick()
        compose.runOnIdle { assertEquals(2, calls) }
    }

    @Test fun addressIsEditableAndInvalidMultilineInputCannotNavigate() {
        var opened: String? = null
        var providerChosen = false
        compose.setContent { AFFileManagerTheme {
            DirectoryBrowserToolbar("Folder", "/a/b", true, true, true, false, false, "editable",
                {}, {}, {}, {}, {}, {}, onOpenPath = { opened = it }, onChooseProvider = { providerChosen = true }, actions = {})
        } }
        compose.onNodeWithTag("directory_address_editable").performClick()
        compose.onNodeWithTag("directory_address_input").performTextReplacement("/a\nb")
        compose.onNodeWithTag("directory_address_open").assertIsNotEnabled()
        compose.onNodeWithTag("directory_address_input").performTextReplacement("/folder with spaces")
        compose.onNodeWithTag("directory_address_open").performClick()
        compose.runOnIdle { assertEquals("/folder with spaces", opened) }
        compose.onNodeWithTag("directory_address_editable").performClick()
        compose.onNodeWithTag("directory_choose_provider").performClick()
        compose.runOnIdle { assertTrue(providerChosen) }
    }

    @Test fun independentGridChoicesRemainReachableInEveryLanguageAndFontScale() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        compose.runOnUiThread {
            if (android.os.Build.VERSION.SDK_INT >= 28) context.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip()
        }
        val language = mutableStateOf("en")
        val font = mutableFloatStateOf(1f)
        var applied: DirectoryDisplaySettings? = null
        compose.setContent {
            val locale = Locale.forLanguageTag(language.value)
            val config = Configuration(context.resources.configuration).apply { setLocale(locale); fontScale = font.floatValue }
            val localized = context.createConfigurationContext(config)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                LocalDensity provides Density(density.density, font.floatValue),
                LocalLayoutDirection provides if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                key(language.value, font.floatValue) { AFFileManagerTheme {
                    DirectoryDisplaySettingsDialog(DirectoryDisplaySettings(layoutMode = DirectoryLayoutMode.GRID),
                        thumbnailsAvailable = true, onDismiss = {}, onApply = { applied = it })
                } }
            }
        }
        AppLanguageManager.SUPPORTED_LANGUAGE_TAGS.forEach { tag ->
            listOf(1f, 1.5f, 2f).forEach { size ->
                compose.runOnIdle { applied = null; language.value = tag; font.floatValue = size }
                compose.onNodeWithTag("display_grid_columns_3").performScrollTo().performClick()
                compose.onNodeWithTag("display_landscape_columns_7").performScrollTo().performClick()
                compose.waitForIdle()
                if (tag in setOf("en", "lt", "de", "ar") && size == 1.5f) {
                    com.affilemanager.app.BrowserIssuesRuntimeVerifier.tapStaticLabel(
                        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
                        com.affilemanager.app.ui.localization.UiTranslator.translate("Rodinio nustatymai", tag))
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250, 5000)
                    androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
                        val file = java.io.File(context.getExternalFilesDir("validation"), "issue266-$tag-large-unobscured.png")
                        file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                        bitmap.recycle()
                    }
                }
                compose.onNodeWithTag("display_apply").assertIsDisplayed().performClick()
                compose.runOnIdle {
                    assertEquals("$tag/$size portrait", 3, applied?.gridColumns)
                    assertEquals("$tag/$size landscape", 7, applied?.gridColumnsLandscape)
                }
            }
        }
    }
}
