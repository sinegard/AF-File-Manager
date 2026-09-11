package com.affilemanager.app.ui.preview

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.data.LocalFileRepository
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PdfTextExtractorTest {
    @Test
    fun extractsOnlyTheRequestedPageAndLeavesNoScratchDirectory() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(application.cacheDir, "pdf-text-test-${System.nanoTime()}").apply { mkdirs() }
        val pdf = File(root, "selectable.pdf")
        createPdf(pdf, listOf("First selectable page", "Second selectable page"))
        val source = PreviewSource.Local(LocalFileRepository(application).toEntry(pdf))

        try {
            val first = extractPdfPageText(application, source, pageIndex = 0)
            val second = extractPdfPageText(application, source, pageIndex = 1)
            val fallbackSecond = extractPdfBoxPageText(application, source, pageIndex = 1)

            assertTrue(first.contains("First selectable page"))
            assertFalse(first.contains("Second selectable page"))
            assertTrue(second.contains("Second selectable page"))
            assertFalse(second.contains("First selectable page"))
            assertTrue(fallbackSecond.contains("Second selectable page"))
            assertFalse(fallbackSecond.contains("First selectable page"))
            assertFalse(File(application.cacheDir, PdfTextRules.SCRATCH_ROOT_NAME).exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativePointSelectionStartsWithAWordAndExtendsFromItsBoundary() {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(application.cacheDir, "pdf-point-selection-${System.nanoTime()}").apply { mkdirs() }
        val pdf = File(root, "selectable.pdf")
        createPdf(pdf, listOf("First selectable page"))
        val source = PreviewSource.Local(LocalFileRepository(application).toEntry(pdf))

        try {
            val word = selectPdfPageText(
                context = application,
                source = source,
                pageIndex = 0,
                start = PdfTextBoundary.AtPoint(PdfPagePoint(62f, 84f)),
                stop = PdfTextBoundary.AtPoint(PdfPagePoint(62f, 84f)),
            )
            assertNotNull(word)
            assertEquals("First", word?.text)
            assertTrue(requireNotNull(word).bounds.isNotEmpty())

            val extended = selectPdfPageText(
                context = application,
                source = source,
                pageIndex = 0,
                start = PdfTextBoundary.AtPoint(word.startHandle),
                stop = PdfTextBoundary.AtPoint(PdfPagePoint(235f, 84f)),
            )
            assertNotNull(extended)
            assertTrue(requireNotNull(extended).text.startsWith("First selectable"))
            assertTrue(
                "Native selection did not extend: word=${word.text}, extended=${extended.text}, " +
                    "start=${word.startHandle}, stop=${extended.stopHandle}",
                extended.text.length > word.text.length,
            )

            val blank = selectPdfPageText(
                context = application,
                source = source,
                pageIndex = 0,
                start = PdfTextBoundary.AtPoint(PdfPagePoint(400f, 700f)),
                stop = PdfTextBoundary.AtPoint(PdfPagePoint(400f, 700f)),
            )
            assertEquals(null, blank)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createPdf(file: File, pages: List<String>) {
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, text ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, index + 1).create())
                page.canvas.drawColor(Color.WHITE)
                page.canvas.drawText(text, 48f, 96f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.BLACK
                    textSize = 24f
                })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally {
            document.close()
        }
    }
}
