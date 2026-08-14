package com.affilemanager.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.core.graphics.createBitmap
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ThumbnailGridVisualTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun generatedImagePdfAndApkRenderRealVisualsInTheGrid() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("thumbnail-fixture")), "current")
        val artifact = File(requireNotNull(application.getExternalFilesDir("validation")), "thumbnail-grid.png")
        fixtureRoot.deleteRecursively()
        require(fixtureRoot.mkdirs())
        try {
            createImage(File(fixtureRoot, "AFFileManager-photo.png"))
            createPdf(File(fixtureRoot, "AFFileManager-document.pdf"))
            File(application.applicationInfo.sourceDir).copyTo(File(fixtureRoot, "AFFileManager-demo.apk"), overwrite = true)

            val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, fixtureRoot.absolutePath)
                if (!viewModel.activePanelState().grid) viewModel.toggleGrid(PanelId.LEFT)
                if (!viewModel.activePanelState().showThumbnails) viewModel.toggleThumbnails(PanelId.LEFT)
            }

            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("AFFileManager-photo.png").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("AFFileManager-photo.png miniatiūra", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("AFFileManager-document.pdf miniatiūra", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("AFFileManager-demo.apk piktograma", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            }

            artifact.parentFile?.mkdirs()
            artifact.outputStream().use { output ->
                assertTrue(compose.onRoot(useUnmergedTree = true).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, output))
            }
            assertTrue(artifact.isFile && artifact.length() > 0)
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    @Test
    fun thumbnailChoiceDefaultsToIconsAndIsRememberedPerDirectory() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val fixtureRoot = File(requireNotNull(application.getExternalFilesDir("thumbnail-preferences")), "run-${System.nanoTime()}")
        val first = File(fixtureRoot, "pirmas")
        val second = File(fixtureRoot, "antras")
        require(first.mkdirs() && second.mkdirs())
        val viewModel = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                viewModel.activatePanel(PanelId.LEFT)
                viewModel.navigate(PanelId.LEFT, first.absolutePath)
                assertFalse(viewModel.activePanelState().showThumbnails)
                viewModel.toggleThumbnails(PanelId.LEFT)
                assertTrue(viewModel.activePanelState().showThumbnails)
                viewModel.navigate(PanelId.LEFT, second.absolutePath)
                assertFalse(viewModel.activePanelState().showThumbnails)
                viewModel.navigate(PanelId.LEFT, first.absolutePath)
                assertTrue(viewModel.activePanelState().showThumbnails)
                viewModel.toggleThumbnails(PanelId.LEFT)
            }
        } finally {
            fixtureRoot.deleteRecursively()
        }
    }

    private fun createImage(file: File) {
        val bitmap = createBitmap(960, 640, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        canvas.drawColor(Color.rgb(0, 121, 107))
        canvas.drawCircle(480f, 320f, 210f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 193, 7) })
        file.outputStream().use { output -> assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        bitmap.recycle()
    }

    private fun createPdf(file: File) {
        val document = PdfDocument()
        try {
            val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText(
                "AF File Manager PDF",
                70f,
                150f,
                Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0, 96, 100); textSize = 42f },
            )
            document.finishPage(page)
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
