package com.affilemanager.app.pdfsigning

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class PdfVisualSignatureEngineTest {
    @Test
    fun visibleSignatureIsAddedOnlyToTheSelectedPageAndSourceStaysUntouched() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(application.cacheDir, "pdf-signature-engine-test-${System.nanoTime()}")
        require(root.mkdirs())
        val source = File(root, "source.pdf")
        val destination = File(root, "signed.pdf")
        try {
            PDDocument().use { document ->
                document.addPage(PDPage(PDRectangle(600f, 800f)))
                document.addPage(PDPage(PDRectangle(600f, 800f)))
                document.save(source)
            }
            val sourceHash = sha256(source)
            val drawing = SignatureDrawing(
                listOf(
                    SignatureStroke(
                        listOf(
                            SignaturePoint(0.08f, 0.70f),
                            SignaturePoint(0.25f, 0.25f),
                            SignaturePoint(0.50f, 0.75f),
                            SignaturePoint(0.80f, 0.20f),
                            SignaturePoint(0.94f, 0.65f),
                        ),
                    ),
                ),
            )
            val placement = VisualSignatureRules.defaultPlacement(pageIndex = 1, pageAspectRatio = 0.75f)

            PdfVisualSignatureEngine(root).apply(source, destination, drawing, placement)

            assertEquals(sourceHash, sha256(source))
            assertEquals(2, pageCount(destination))
            assertEquals(0, darkPixelCount(destination, pageIndex = 0))
            assertTrue(darkPixelCount(destination, pageIndex = 1) > 40)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
        PdfRenderer(descriptor).use(PdfRenderer::getPageCount)
    }

    private fun darkPixelCount(file: File, pageIndex: Int): Int =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.openPage(pageIndex).use { page ->
                    val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        var dark = 0
                        for (y in 0 until bitmap.height step 2) {
                            for (x in 0 until bitmap.width step 2) {
                                val color = bitmap.getPixel(x, y)
                                if (Color.red(color) < 80 && Color.green(color) < 80 && Color.blue(color) < 80) dark += 1
                            }
                        }
                        dark
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
        }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
