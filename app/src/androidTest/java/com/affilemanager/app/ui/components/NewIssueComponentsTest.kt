package com.affilemanager.app.ui.components

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.LocaleList
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.model.*
import com.affilemanager.app.operations.*
import com.affilemanager.app.ui.ArchiveUnlockUiState
import com.affilemanager.app.ui.PreviewTarget
import com.affilemanager.app.ui.localization.UiTranslator
import com.affilemanager.app.ui.preview.ApkInstallConfirmationDialog
import com.affilemanager.app.apk.ApkInstallMetadataReader
import com.affilemanager.app.ui.screens.LocalUploadDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.Locale
import java.util.UUID
import java.security.MessageDigest

class NewIssueComponentsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun english() = exercise("en", 1f)
    @Test fun lithuanianLargeText() = exercise("lt", 1.4f)
    @Test fun germanLargeText() = exercise("de", 1.4f)
    @Test fun arabicLargeText() = exercise("ar", 1.4f)

    private fun exercise(language: String, fontScale: Float) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = File(context.cacheDir, "issue-components-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("long photo name that stays in the same selection.png")
        val bitmap = Bitmap.createBitmap(320, 180, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) }
        source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
        val original = digest(source)
        val entry = FileEntry(source.path, source.name, EntryKind.IMAGE, source.length(), source.lastModified(), false, true, true)
        val config = Configuration(context.resources.configuration).apply { setLocales(LocaleList(Locale.forLanguageTag(language))); this.fontScale = fontScale }
        val localized = context.createConfigurationContext(config)
        val screen = mutableIntStateOf(0)
        var unlocked: CharArray? = null
        var chosen = emptyList<String>()
        var cropped: Uri? = null
        var previewed = false
        var hiddenLoaded = false
        var opened = false
        try {
            compose.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, fontScale),
                    LocalLayoutDirection provides if (language == "ar") LayoutDirection.Rtl else LayoutDirection.Ltr) { MaterialTheme {
                when (screen.intValue) {
                    0 -> OperationResultDialog(OperationSnapshot("failed", "Archyvai", OperationStatus.FAILED, message = "Failas per didelis"), { screen.intValue = 1 })
                    1 -> OperationProgressDialog(OperationSnapshot("index", "Failai", OperationStatus.RUNNING, totalBytes = 10, completedBytes = 10,
                        stage = OperationStage.PUBLISHING_INDEX), {}, { screen.intValue = 2 }, {}, {})
                    2 -> ArchiveUnlockDialog(ArchiveUnlockUiState(PreviewTarget.Archive(entry.copy(kind = EntryKind.ARCHIVE), emptyList())),
                        { password -> unlocked = password.copyOf(); password.fill('\u0000'); screen.intValue = 3 }, {})
                    3 -> LocalUploadDialog(root.path, "", listOf(entry), emptySet(), { Result.success(listOf(entry)) }, {},
                        { chosen = it; screen.intValue = 4 }, loadWithHidden = { _, hidden -> hiddenLoaded = hidden; Result.success(listOf(entry)) })
                    4 -> ImageCropDialog(FileProvider.getUriForFile(context, "${context.packageName}.files", source), "group", 16f / 9f,
                        { screen.intValue = 5 }, { cropped = it; screen.intValue = 5 })
                    5 -> ApkInstallConfirmationDialog("installed", { ApkInstallMetadataReader.standalone(context, File(context.applicationInfo.sourceDir)) }, {}, { opened = true }, installedApp = true)
                }
            } } }
            compose.onNodeWithTag("operation_result_dialog").assertIsDisplayed()
            screenshot(language, "error")
            compose.onNodeWithText(UiTranslator.translate("Uždaryti", language)).performClick()
            compose.onNodeWithTag("operation_stage").assertTextEquals(UiTranslator.translate("Atnaujinamas failų indeksas", language))
            if (language !in setOf("en", "lt")) assertNotEquals("Updating the file index", UiTranslator.translate("Atnaujinamas failų indeksas", language))
            screenshot(language, "index")
            compose.onNodeWithTag("hide_operation").performClick()
            compose.onNodeWithTag("archive_unlock_password").performTextInput("fixture password")
            screenshot(language, "archive")
            compose.onNodeWithTag("archive_unlock_confirm").performClick()
            assertArrayEquals("fixture password".toCharArray(), unlocked)
            unlocked?.fill('\u0000')
            val row = "local_upload_entry_${entry.absolutePath}"
            compose.waitUntil(5000) { compose.onAllNodesWithTag(row).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(row).performTouchInput { longClick() }
            compose.onNodeWithTag("local_upload_selection_bar").assertIsDisplayed()
            compose.onNodeWithTag("picker_preview_${entry.absolutePath}").performClick()
            compose.onNodeWithText(UiTranslator.translate("Uždaryti", language)).assertIsDisplayed().performClick()
            previewed = true
            compose.onNodeWithTag("local_upload_selection_bar").assertIsDisplayed()
            compose.onNodeWithTag("local_upload_more").performClick()
            compose.onNodeWithTag("local_upload_hidden").performClick()
            compose.waitUntil(5000) { hiddenLoaded }
            screenshot(language, "picker")
            compose.onNodeWithTag("local_upload_confirm").performClick()
            assertEquals(listOf(entry.absolutePath), chosen)
            compose.waitUntil(5000) { compose.onAllNodesWithTag("image_crop_canvas").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("image_crop_canvas").performTouchInput {
                val imageHeight = width * 180f / 320f
                val top = (height - imageHeight) / 2
                down(androidx.compose.ui.geometry.Offset(width * .925f, top + imageHeight * .925f))
                moveBy(androidx.compose.ui.geometry.Offset(-width * .2f, -imageHeight * .2f)); up()
                down(center); moveBy(androidx.compose.ui.geometry.Offset(28f, 12f)); up()
            }
            screenshot(language, "crop")
            compose.onNodeWithTag("image_crop_confirm").performClick()
            compose.waitUntil(5000) { cropped != null }
            compose.onNodeWithTag("installed_app_information").assertIsDisplayed()
            compose.onNodeWithText(UiTranslator.translate("Minimali Android API", language)).assertExists()
            if (language != "lt") compose.onAllNodesWithText("Minimali Android API").assertCountEquals(0)
            compose.waitUntil(10000) { compose.onNodeWithTag("open_installed_app").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
            screenshot(language, "app")
            // A semantics click can invoke an action even underneath the system
            // navigation bar. Use its real rendered touch target instead.
            compose.onNodeWithTag("open_installed_app").performTouchInput { click() }
            compose.waitUntil(5_000) { opened }
            compose.onNodeWithText(UiTranslator.translate("APK nenurodo patikimos maksimalios Android versijos; rodoma kūrėjo pasirinkta tikslinė API.", language))
                .performScrollTo().assertIsDisplayed()
            assertTrue(previewed)
            assertArrayEquals(original, digest(source))
            context.contentResolver.openInputStream(requireNotNull(cropped)).use {
                val saved = android.graphics.BitmapFactory.decodeStream(requireNotNull(it))
                assertNotNull(saved)
                assertTrue("The resize corner did not shrink the crop", saved.width < (320 * .8f).toInt())
                assertEquals(16f / 9f, saved.width.toFloat() / saved.height, .03f)
                saved.recycle()
            }
        } finally { unlocked?.fill('\u0000'); root.deleteRecursively() }
    }
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun screenshot(language: String, stage: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val target = File(context.getExternalFilesDir("validation"), "issues-246-252-$language-$stage.png")
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        target.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        image.recycle()
    }
}
