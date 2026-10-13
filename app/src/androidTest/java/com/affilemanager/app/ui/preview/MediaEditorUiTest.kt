package com.affilemanager.app.ui.preview

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.*
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.editing.*
import com.affilemanager.app.media.editing.*
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import com.affilemanager.app.ui.theme.AppearanceSettings
import com.affilemanager.app.ui.theme.AppThemeMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class MediaEditorUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun englishEditor() = editor("en", 1f)
    @Test fun lithuanianLargeTextEditor() = editor("lt", 1.5f)
    @Test fun germanLargeTextEditor() = editor("de", 1.5f)
    @Test fun arabicLargeTextEditor() = editor("ar", 1.5f)
    @Test fun englishDarkLargeTextEditor() = editor("en", 1.5f, true)
    @Test fun exportFailureScrollsIntoViewAndLeavesTheOriginalUnchanged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-error-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = root.resolve("original.wav").also { MediaTestFixtures.wav(it) }
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
        val session = store.prepareFromFile("fixture", file.name, "audio/wav", file, EditOrigin.Local(file.path, true), file.lastModified(), false)
        val source = PreviewSource.Working(PreviewSource.Local(FileEntry(file.path, file.name, EntryKind.AUDIO, file.length(), file.lastModified(), false, true, true)), session)
        val exports = File(context.cacheDir, "media-exports")
        val existing = exports.list()?.toSet().orEmpty()
        try {
            compose.setContent { AFFileManagerTheme(AppearanceSettings(themeMode = AppThemeMode.LIGHT)) {
                MediaEditorDialog(source, MediaEditorMode.TRIM, {}, { error("Changed source must never be handed off") })
            } }
            compose.waitUntil(10_000) { compose.onNodeWithTag("media_edit_export").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
            compose.onNodeWithText(UiTranslator.translate("Metaduomenys", "en")).performScrollTo().performClick()
            compose.onNodeWithText(UiTranslator.translate("Pasirinkite viršelį iš naujo. Ne visi pradinio failo metaduomenys perkeliami automatiškai.", "en")).performScrollTo()
            // Mutate only this test's private working copy, after its info was read.
            session.workingFile.appendBytes(byteArrayOf(0))
            compose.onNodeWithTag("media_edit_export").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText(UiTranslator.translate("Šaltinis pasikeitė", "en")).fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
            compose.onNodeWithText(UiTranslator.translate("Šaltinis pasikeitė", "en")).assertIsDisplayed()
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
            assertEquals(existing, exports.list()?.toSet().orEmpty())
            screenshot("export-error")
        } finally { store.discard(session); root.deleteRecursively() }
    }
    @Test fun allShippedLanguagesKeepVideoMetadataAndExportControlsWithinTheirLayout() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-languages-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = root.resolve("video.mp4").also { MediaTestFixtures.video(it) }
        val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
        val session = store.prepareFromFile("video", file.name, "video/mp4", file, EditOrigin.Local(file.path, true), file.lastModified(), false)
        val source = PreviewSource.Working(PreviewSource.Local(FileEntry(file.path, file.name, EntryKind.VIDEO, file.length(), file.lastModified(), false, true, true)), session)
        val language = mutableStateOf("en")
        val scale = mutableFloatStateOf(1f)
        try {
            compose.setContent {
                val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(language.value)); fontScale = scale.floatValue }
                val localized = context.createConfigurationContext(config)
                val density = LocalDensity.current
                val registry = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    LocalActivityResultRegistryOwner provides registry, LocalDensity provides Density(density.density, scale.floatValue),
                    LocalLayoutDirection provides if (android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(language.value)) == android.view.View.LAYOUT_DIRECTION_RTL) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    // AppCompat recreates the activity when the app language changes.
                    // Recreate the native preview/dialog too, rather than retaining a
                    // TextureView from an LTR configuration inside an RTL window.
                    key(language.value, scale.floatValue) {
                        AFFileManagerTheme(AppearanceSettings(themeMode = AppThemeMode.LIGHT)) {
                            MediaEditorDialog(source, MediaEditorMode.COMPRESS, {}, { error("No export requested") })
                        }
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media_fps_original").fetchSemanticsNodes().isNotEmpty() }
            for (tag in AppLanguageManager.SUPPORTED_LANGUAGE_TAGS) for (font in listOf(1f, 1.5f, 2f)) {
                System.out.println("AF_MEDIA_LAYOUT_BEGIN $tag/$font")
                compose.runOnIdle { language.value = tag; scale.floatValue = font }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("media_fps_original").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(UiTranslator.translate("Metaduomenys", tag)).performScrollTo().performClick()
                compose.onNodeWithTag("media_edit_export").assertIsDisplayed()
                compose.onNodeWithText(UiTranslator.translate("Paruošti ir išsaugoti kaip", tag)).assertExists()
                compose.onNodeWithText(UiTranslator.translate("Kirpimo intervalas", tag)).assertExists()
                compose.onNodeWithText(UiTranslator.translate("Albumo atlikėjas", tag)).assertExists()
                compose.onNodeWithText(UiTranslator.translate("Dainos tekstas", tag)).assertExists()
                assertNoClippedText("$tag/$font")
                System.out.println("AF_MEDIA_LAYOUT_OK $tag/$font")
            }
        } finally { store.discard(session); root.deleteRecursively() }
    }
    @Test fun videoAndGifRetainIndependentVisibleFrameRateAndSizeChoices() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-formats-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = root.resolve("video.mp4").also { MediaTestFixtures.video(it) }
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
        val session = store.prepareFromFile("video", file.name, "video/mp4", file, EditOrigin.Local(file.path, true), file.lastModified(), false)
        val source = PreviewSource.Working(PreviewSource.Local(FileEntry(file.path, file.name, EntryKind.VIDEO, file.length(), file.lastModified(), false, true, true)), session)
        val shown = mutableStateOf(true)
        try {
            compose.setContent { AFFileManagerTheme(AppearanceSettings(themeMode = AppThemeMode.LIGHT)) {
                if (shown.value) MediaEditorDialog(source, MediaEditorMode.COMPRESS, { shown.value = false }, { error("No export requested") })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media_fps_original").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media_fps_24").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("media_height_360").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("media_format_GIF").performScrollTo().performClick()
            compose.onNodeWithTag("media_fps_5").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("media_height_original").performScrollTo().performClick().assertIsSelected()
            compose.onNodeWithTag("media_format_MP4").performScrollTo().performClick()
            compose.onNodeWithTag("media_fps_24").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("media_height_360").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("media_format_M4A").performScrollTo().performClick()
            compose.onNodeWithTag("media_fps_24").assertDoesNotExist()
            compose.onNodeWithTag("media_format_WAV").performScrollTo().performClick()
            compose.onNodeWithTag("media_format_GIF").performScrollTo().performClick()
            compose.onNodeWithTag("media_fps_5").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("media_height_original").performScrollTo().assertIsSelected()
            compose.onNodeWithTag("media_edit_cancel").performClick()
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { store.discard(session); root.deleteRecursively() }
    }
    private fun editor(language: String, fontScale: Float, dark: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = root.resolve("original audio with a long name.wav").also { MediaTestFixtures.wav(it) }
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
        val session = store.prepareFromFile("fixture", file.name, "audio/wav", file, EditOrigin.Local(file.path, true), file.lastModified(), false)
        val source = PreviewSource.Working(PreviewSource.Local(FileEntry(file.path, file.name, EntryKind.AUDIO, file.length(), file.lastModified(), false, true, true)), session)
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))); this.fontScale = fontScale }
        val localized = context.createConfigurationContext(config)
        val shown = mutableStateOf(true)
        var exported: MediaExportFile? = null
        try {
            compose.setContent {
                val density = LocalDensity.current
                val registry = requireNotNull(LocalActivityResultRegistryOwner.current)
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    LocalActivityResultRegistryOwner provides registry,
                    LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                    AFFileManagerTheme(AppearanceSettings(themeMode = if (dark) AppThemeMode.DARK else AppThemeMode.LIGHT)) {
                        if (shown.value) MediaEditorDialog(source, MediaEditorMode.TRIM, { shown.value = false }, { exported = it; true })
                    }
                }
            }
            compose.waitUntil(10_000) { compose.onNodeWithTag("media_edit_export").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Disabled) == null }
            compose.onNodeWithTag("media_editor").assertIsDisplayed()
            compose.onNodeWithTag("media_trim_range").performTouchInput {
                down(Offset(width * .1f, height / 2f)); moveTo(Offset(width * .2f, height / 2f), 200); up()
            }
            compose.onNodeWithTag("media_output_name").performScrollTo().performTextReplacement("my result")
            compose.onNodeWithTag("media_format_M4A").performScrollTo().performClick()
            compose.onNodeWithText(UiTranslator.translate("Metaduomenys", language)).performScrollTo().performClick()
            compose.waitUntil(5_000) { !context.getSystemService(android.view.inputmethod.InputMethodManager::class.java).isAcceptingText }
            compose.onNodeWithText(UiTranslator.translate("Albumo atlikėjas", language)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription(UiTranslator.translate("Uždaryti", language)).assertIsDisplayed()
            assertNoClippedText()
            screenshot(language + if (dark) "-dark" else "")
            compose.onNodeWithTag("media_edit_export").assertIsDisplayed().performClick()
            compose.waitUntil(60_000) { exported != null || !shown.value }
            val result = requireNotNull(exported)
            assertEquals("my result.m4a", result.displayName)
            assertTrue(result.file.isFile && result.durationMs > 1000)
            compose.onNodeWithTag("media_editor").assertDoesNotExist()
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { exported?.file?.delete(); store.discard(session); root.deleteRecursively() }
    }
    @Test fun cropHandleResizeMoveAndPointerJitterUseOneConsistentDraft() {
        val region = mutableStateOf(MediaCrop(.1f, .1f, .8f, .8f))
        compose.setContent { MaterialTheme { Box(Modifier.size(300.dp, 240.dp)) { MediaCropOverlay(region.value, { region.value = it }, Modifier.matchParentSize()) } } }
        compose.onNodeWithTag("media_crop_canvas").performTouchInput {
            down(Offset(width * .9f, height * .9f))
            repeat(10) { moveBy(Offset(-width * .02f, -height * .02f)) }; up()
        }
        compose.runOnIdle { assertEquals(.6f, region.value.width, .02f); assertEquals(.6f, region.value.height, .02f) }
        compose.onNodeWithTag("media_crop_canvas").performTouchInput { down(center); moveBy(Offset(width * .1f, height * .1f)); up() }
        compose.runOnIdle { assertEquals(.2f, region.value.x, .02f); assertEquals(.2f, region.value.y, .02f) }
    }
    @Test fun realVideoPreviewCropAndCancelPreserveTheOriginal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "media-video-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val file = root.resolve("video.mp4").also { MediaTestFixtures.video(it, false) }
        val before = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
        val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
        val session = store.prepareFromFile("video", file.name, "video/mp4", file, EditOrigin.Local(file.path, true), file.lastModified(), false)
        val source = PreviewSource.Working(PreviewSource.Local(FileEntry(file.path, file.name, EntryKind.VIDEO, file.length(), file.lastModified(), false, true, true)), session)
        val shown = mutableStateOf(true)
        try {
            compose.setContent { AFFileManagerTheme(AppearanceSettings(themeMode = AppThemeMode.LIGHT)) {
                if (shown.value) MediaEditorDialog(source, MediaEditorMode.COMPRESS, { shown.value = false }, { error("No export requested") })
            } }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("media_edit_play").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("media_edit_play").performScrollTo().performClick()
            compose.waitUntil(10_000) { screenHasVideo() }
            compose.onNodeWithTag("media_crop_enabled").performScrollTo().performClick()
            compose.onNodeWithTag("media_crop_canvas").performScrollTo().performTouchInput {
                down(Offset(width - 4f, height - 4f)); moveBy(Offset(-width * .2f, -height * .2f)); up()
            }
            screenshot("video-crop")
            compose.onNodeWithTag("media_edit_cancel").performClick()
            compose.onNodeWithTag("media_editor").assertDoesNotExist()
            assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(file.readBytes()))
        } finally { store.discard(session); root.deleteRecursively() }
    }
    private fun screenHasVideo(): Boolean {
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            var red = 0; var green = 0
            for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                val pixel = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) < 80) red++
                if (android.graphics.Color.green(pixel) > 150 && android.graphics.Color.red(pixel) < 80) green++
            }
            return red > 500 && green > 500
        } finally { bitmap.recycle() }
    }
    private fun assertNoClippedText(case: String = "") {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.forEach { text ->
                assertFalse("$case Clipped: ${text.layoutInput.text}", text.multiParagraph.didExceedMaxLines || text.multiParagraph.height > text.size.height + 1f ||
                    (0 until text.lineCount).any { line -> text.isLineEllipsized(line) || text.getLineRight(line) - text.getLineLeft(line) > text.size.width + 1f })
            }
        }
    }
    private fun screenshot(language: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.getExternalFilesDir("validation"), "media-editor-$language.png")
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try { file.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) } } finally { bitmap.recycle() }
    }
}
