package com.affilemanager.app

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.core.graphics.createBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.ui.MainViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class FilePreviewLifecycleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun pngAndPdfCanBeOpenedAndClosedRepeatedlyWithoutRecycledBitmapCrash() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("preview-lifecycle")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        try {
            val image = File(fixtureRoot, "preview-lifecycle.png").also(::createImage)
            val pdf = File(fixtureRoot, "preview-lifecycle.pdf").also(::createPdf)
            val validationRoot = requireNotNull(application.getExternalFilesDir("validation"))
            val repository = LocalFileRepository(application)
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

            repeat(3) { iteration ->
                openAndClose(
                    viewModel,
                    repository.toEntry(image),
                    image.name,
                    File(validationRoot, "preview-png.png").takeIf { iteration == 0 },
                )
                openAndClose(
                    viewModel,
                    repository.toEntry(pdf),
                    "PDF page 1",
                    File(validationRoot, "preview-pdf.png").takeIf { iteration == 0 },
                )
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun imageZoomControlsAndContinuousPdfPagesAreInteractive() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("preview-interaction")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        try {
            val image = File(fixtureRoot, "zoom.png").also(::createImage)
            val pdf = File(fixtureRoot, "continuous.pdf").also(::createPdf)
            val validationRoot = requireNotNull(application.getExternalFilesDir("validation"))
            pdf.copyTo(File(validationRoot, "continuous-test.pdf"), overwrite = true)
            val repository = LocalFileRepository(application)
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

            compose.runOnUiThread { viewModel.open(repository.toEntry(image)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithContentDescription(image.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Open with another app").assertIsDisplayed()
            compose.onNodeWithContentDescription("Zoom in").performClick()
            compose.onNodeWithText("125 %").fetchSemanticsNode()
            captureRoot(File(validationRoot, "preview-image-zoomed.png"))
            compose.onNodeWithText("Reset").performClick()
            compose.onNodeWithText("100 %").fetchSemanticsNode()
            compose.runOnUiThread { viewModel.closePreview() }

            compose.runOnUiThread { viewModel.open(repository.toEntry(pdf)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithContentDescription("PDF page 1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(compose.onAllNodesWithText("Ryškus PDF", substring = true).fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("1 / 3").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("pdf-continuous-pages").performScrollToIndex(2)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithContentDescription("PDF page 3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("PDF page 3", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithContentDescription("Zoom in").performClick()
            compose.onNodeWithText("125 %").fetchSemanticsNode()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithContentDescription("PDF page 3", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.waitForIdle()
            captureRoot(File(validationRoot, "preview-pdf-continuous-page3.png"))
            compose.runOnUiThread { viewModel.closePreview() }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun archivePreviewNavigatesFoldersWithoutShowingDescendantPathsAtRoot() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("archive-navigation")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        try {
            val archive = File(fixtureRoot, "archyvo-medis.zip").also(::createArchive)
            val validationRoot = requireNotNull(application.getExternalFilesDir("validation"))
            archive.copyTo(File(validationRoot, "archive-navigation-test.zip"), overwrite = true)
            val repository = LocalFileRepository(application)
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

            compose.runOnUiThread { viewModel.open(repository.toEntry(archive)) }
            compose.onNodeWithText("Archive root").assertIsDisplayed()
            compose.onNodeWithText("Aplankas").assertIsDisplayed()
            compose.onNodeWithText("šaknis.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("viduje.txt").fetchSemanticsNodes().isEmpty())
            assertTrue(compose.onAllNodesWithText("Aplankas/viduje.txt").fetchSemanticsNodes().isEmpty())
            compose.waitForIdle()
            captureRoot(File(validationRoot, "preview-archive-root.png"))

            compose.onNodeWithText("Aplankas").performClick()
            compose.onNodeWithText("viduje.txt").assertIsDisplayed()
            compose.onNodeWithText("Giliau").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("gilus.txt").fetchSemanticsNodes().isEmpty())
            compose.waitForIdle()
            captureRoot(File(validationRoot, "preview-archive-folder.png"))

            compose.onNodeWithText("Giliau").performClick()
            compose.onNodeWithText("gilus.txt").assertIsDisplayed()
            compose.onNodeWithContentDescription("Return to the previous archive folder").performClick()
            compose.onNodeWithText("viduje.txt").assertIsDisplayed()
            assertTrue(compose.onAllNodesWithText("gilus.txt").fetchSemanticsNodes().isEmpty())
            compose.runOnUiThread { viewModel.closePreview() }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun actionViewContentUriOpensInternallyAndManifestAdvertisesFileTypes() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixture = File(requireNotNull(application.getExternalFilesDir("incoming-view")), "incoming.png")
        try {
            fixture.parentFile?.mkdirs()
            createImage(fixture)
            val uri = FileProvider.getUriForFile(application, "${application.packageName}.files", fixture)
            val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            @Suppress("DEPRECATION")
            val handlers = application.packageManager.queryIntentActivities(viewIntent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(handlers.any { it.activityInfo.packageName == application.packageName })

            val editIntent = Intent(Intent.ACTION_EDIT).apply {
                setDataAndType(uri, "text/plain")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            @Suppress("DEPRECATION")
            val editHandlers = application.packageManager.queryIntentActivities(editIntent, PackageManager.MATCH_DEFAULT_ONLY)
            assertTrue(editHandlers.any { it.activityInfo.packageName == application.packageName })

            compose.runOnUiThread { compose.activity.onNewIntent(viewIntent) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithContentDescription(fixture.name, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("image/png", substring = true).fetchSemanticsNode()
            compose.runOnUiThread { ViewModelProvider(compose.activity)[MainViewModel::class.java].closePreview() }
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun malformedPngShowsAnErrorInsteadOfCrashingTheProcess() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixture = File(requireNotNull(application.getExternalFilesDir("preview-lifecycle")), "broken-${System.nanoTime()}.png")
        try {
            fixture.parentFile?.mkdirs()
            fixture.writeText("tai nėra PNG")
            val entry = LocalFileRepository(application).toEntry(fixture)
            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]

            compose.runOnUiThread { viewModel.open(entry) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Could not create the file preview").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitForIdle()
        } finally {
            fixture.delete()
        }
    }

    @Test
    fun textEditorUsesWorkingCopyAndRequiresExplicitConflictResolution() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("edit-session")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        val source = File(fixtureRoot, "editable.txt").apply { writeText("original") }
        val validationRoot = requireNotNull(application.getExternalFilesDir("validation"))
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.open(LocalFileRepository(application).toEntry(source)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("full-text-editor", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            replaceEditorText("edited in AF")
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.fileEditState.value.text == "edited in AF" }
            compose.runOnIdle { assertEquals("original", source.readText()) }
            compose.waitForIdle()
            captureRoot(File(validationRoot, "preview-text-editor.png"))

            source.writeText("changed by another app")
            compose.onNodeWithTag("save-edit-original").assertIsEnabled().performClick()
            compose.onNodeWithText("The original file changed").assertIsDisplayed()
            compose.runOnIdle { assertEquals("changed by another app", source.readText()) }
            captureRoot(File(validationRoot, "preview-edit-conflict.png"))
            compose.onNodeWithTag("overwrite-edit-conflict").performClick()
            compose.waitUntil(timeoutMillis = 10_000) { source.readText() == "edited in AF" }
            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("file-preview-dialog", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun textEditorSaveAsRebasesSubsequentSavesToTheChosenPhoneFile() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("edit-save-as")), "current")
        fixtureRoot.deleteRecursively()
        val sourceDirectory = File(fixtureRoot, "source").apply { mkdirs() }
        val destinationDirectory = File(fixtureRoot, "destination").apply { mkdirs() }
        val source = File(sourceDirectory, "original.txt").apply { writeText("original") }
        val destination = File(destinationDirectory, "renamed.txt")
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.open(LocalFileRepository(application).toEntry(source)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("full-text-editor", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            replaceEditorText("first saved version")
            compose.runOnUiThread { viewModel.saveFileEditAsLocal(destinationDirectory.absolutePath, destination.name) }
            compose.waitUntil(timeoutMillis = 10_000) { destination.isFile && destination.readText() == "first saved version" }
            compose.runOnIdle {
                assertEquals("original", source.readText())
                assertEquals(destination.absolutePath, (viewModel.fileEditState.value.session?.origin as? com.affilemanager.app.editing.EditOrigin.Local)?.path)
            }

            replaceEditorText("second saved version")
            compose.waitUntil(timeoutMillis = 10_000) { viewModel.fileEditState.value.text == "second saved version" }
            compose.runOnUiThread { viewModel.saveFileEdit() }
            compose.waitUntil(timeoutMillis = 10_000) { destination.readText() == "second saved version" }
            compose.runOnIdle { assertEquals("original", source.readText()) }
        } finally {
            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("file-preview-dialog", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun fullTextEditorRendersItsPrimaryControls() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("editor-smoke")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        val source = File(fixtureRoot, "sample.kt").apply { writeText("val answer = 42\n") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread { viewModel.open(LocalFileRepository(application).toEntry(source)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("full-text-editor", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Find and replace").assertIsDisplayed()
            compose.onNodeWithContentDescription("Go to line").assertIsDisplayed()
            compose.onNodeWithText("UTF-8").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("file-preview-dialog", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            compose.waitForIdle()
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun closingEditorRemovesItsPrivateWorkingCopy() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("editor-cleanup")), "current")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        val source = File(fixtureRoot, "temporary.txt").apply { writeText("temporary edit source") }
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        var workingCopy: File? = null
        try {
            compose.runOnUiThread { viewModel.open(LocalFileRepository(application).toEntry(source)) }
            compose.waitUntil(timeoutMillis = 10_000) {
                viewModel.fileEditState.value.session?.workingFile?.isFile == true
            }
            compose.runOnIdle {
                workingCopy = requireNotNull(viewModel.fileEditState.value.session).workingFile
                assertTrue(requireNotNull(workingCopy).isFile)
            }

            compose.runOnUiThread { viewModel.closePreview() }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag("file-preview-dialog", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
            }
            compose.waitUntil(timeoutMillis = 10_000) { requireNotNull(workingCopy).exists().not() }
            assertTrue(source.isFile)
            assertEquals("temporary edit source", source.readText())
        } finally {
            if (viewModel.preview.value != null) compose.runOnUiThread { viewModel.closePreview() }
            fixtureRoot.deleteRecursively()
        }
    }

    private fun openAndClose(
        viewModel: MainViewModel,
        entry: com.affilemanager.app.model.FileEntry,
        contentDescription: String,
        artifact: File?,
    ) {
        compose.runOnUiThread { viewModel.open(entry) }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(contentDescription, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
        artifact?.let { target ->
            target.parentFile?.mkdirs()
            target.outputStream().use { output ->
                assertTrue(
                    compose.onNodeWithContentDescription(contentDescription, useUnmergedTree = true)
                        .captureToImage()
                        .asAndroidBitmap()
                        .compress(Bitmap.CompressFormat.PNG, 100, output),
                )
            }
            assertTrue(target.isFile && target.length() > 0)
        }

        compose.runOnUiThread { viewModel.closePreview() }
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithContentDescription(contentDescription, useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        compose.waitForIdle()
    }

    private fun captureRoot(target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            assertTrue(
                compose.onNodeWithTag("file-preview-dialog", useUnmergedTree = true)
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        assertTrue(target.isFile && target.length() > 0)
    }

    private fun replaceEditorText(value: String) {
        compose.onNodeWithTag("full-text-editor").performSemanticsAction(SemanticsActions.SetText) { action ->
            assertTrue(action(AnnotatedString(value)))
        }
    }

    private fun createImage(file: File) {
        val bitmap = createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(Color.rgb(0, 121, 107))
            canvas.drawCircle(480f, 320f, 210f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) })
            file.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        } finally {
            bitmap.recycle()
        }
    }

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(
                    "AF File Manager PDF puslapis ${index + 1}",
                    48f,
                    120f,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 96, 100); textSize = 32f },
                )
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }

    private fun createArchive(file: File) {
        ZipOutputStream(file.outputStream().buffered()).use { output ->
            listOf(
                "šaknis.txt" to "šaknis",
                "Aplankas/viduje.txt" to "viduje",
                "Aplankas/Giliau/gilus.txt" to "gilus",
            ).forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }
}
