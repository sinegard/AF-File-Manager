package com.affilemanager.app.ui.preview

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.os.Build
import androidx.annotation.RequiresApi
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.Writer
import java.util.UUID

internal fun extractPdfPageText(
    context: Context,
    source: PreviewSource,
    pageIndex: Int,
): String {
    PdfTextRules.requireSourceSize(source.sizeBytes)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        extractNativePdfPageText(context, source, pageIndex)?.let { return it }
    }
    return extractPdfBoxPageText(context, source, pageIndex)
}

@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
private fun extractNativePdfPageText(
    context: Context,
    source: PreviewSource,
    pageIndex: Int,
): String? = source.openFileDescriptor(context).use { descriptor ->
    val knownSize = descriptor.statSize.takeIf { it >= 0L } ?: source.sizeBytes
    if (knownSize == null) return@use null
    PdfTextRules.requireSourceSize(knownSize)
    PdfRenderer(descriptor).use { renderer ->
        PdfTextRules.requirePageIndex(pageIndex, renderer.pageCount)
        renderer.openPage(pageIndex).use { page ->
            val output = StringBuilder()
            page.textContents.forEach { content ->
                appendBoundedPageText(output, content.text)
            }
            output.toString().trim()
        }
    }
}

// This is an app-private, bounded cache read. It must not evict unrelated user cache data.
@SuppressLint("UsableSpace")
internal fun extractPdfBoxPageText(
    context: Context,
    source: PreviewSource,
    pageIndex: Int,
): String {
    val scratchRoot = context.cacheDir.resolve(PdfTextRules.SCRATCH_ROOT_NAME)
    val scratchDirectory = scratchRoot.resolve(UUID.randomUUID().toString())
    require(scratchDirectory.mkdirs()) { "Nepavyko paruošti privačios PDF darbo vietos" }
    try {
        val sourceBytes = source.localFile?.length()?.also(PdfTextRules::requireSourceSize)
            ?: source.sizeBytes
        val requiredBytes = (sourceBytes ?: PdfTextRules.MAX_SOURCE_BYTES)
            .coerceAtMost(PdfTextRules.MAX_SOURCE_BYTES) + MIN_FREE_BYTES
        require(scratchDirectory.usableSpace <= 0L || scratchDirectory.usableSpace >= requiredBytes) {
            "Nepakanka vietos saugiai PDF darbo kopijai"
        }
        val memory = MemoryUsageSetting.setupMixed(MAX_MAIN_MEMORY_BYTES, MAX_SCRATCH_BYTES)
            .setTempDir(scratchDirectory)
        val document = source.localFile?.let { PDDocument.load(it, memory) }
            ?: source.openInputStream(context).use { input ->
                PDDocument.load(BoundedPdfInputStream(input, PdfTextRules.MAX_SOURCE_BYTES), memory)
            }
        document.use { pdf ->
            PdfTextRules.requirePageIndex(pageIndex, pdf.numberOfPages)
            val output = BoundedPageTextWriter(PdfTextRules.MAX_PAGE_TEXT_CHARS)
            PDFTextStripper().apply {
                sortByPosition = true
                startPage = pageIndex + 1
                endPage = pageIndex + 1
            }.writeText(pdf, output)
            return output.toString().trim()
        }
    } finally {
        scratchDirectory.deleteRecursively()
        synchronized(pdfTextScratchCleanupLock) {
            if (scratchRoot.listFiles()?.isEmpty() == true) scratchRoot.delete()
        }
    }
}

private fun appendBoundedPageText(output: StringBuilder, raw: String) {
    val text = raw.trim()
    if (text.isEmpty()) return
    val separatorLength = if (output.isEmpty()) 0 else 1
    PdfTextRules.requireTextLength(output.length + separatorLength + text.length)
    if (separatorLength > 0) output.append('\n')
    output.append(text)
}

private class BoundedPdfInputStream(
    input: InputStream,
    private val maximumBytes: Long,
) : FilterInputStream(input) {
    private var consumed = 0L

    override fun read(): Int {
        checkInterrupted()
        val value = super.read()
        if (value >= 0) account(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        checkInterrupted()
        val read = super.read(buffer, offset, length)
        if (read > 0) account(read)
        return read
    }

    private fun account(bytes: Int) {
        consumed = Math.addExact(consumed, bytes.toLong())
        if (consumed > maximumBytes) throw IOException("Failas per didelis peržiūrai")
    }
}

private class BoundedPageTextWriter(
    private val maximumCharacters: Int,
) : Writer() {
    private val output = StringBuilder()

    override fun write(buffer: CharArray, offset: Int, length: Int) {
        checkInterrupted()
        PdfTextRules.requireTextLength(Math.addExact(output.length, length))
        output.append(buffer, offset, length)
    }

    override fun flush() = Unit
    override fun close() = Unit
    override fun toString(): String = output.toString()
}

private fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedIOException("PDF text extraction cancelled")
}

private val pdfTextScratchCleanupLock = Any()
private const val MAX_MAIN_MEMORY_BYTES = 8L * 1_024L * 1_024L
private const val MAX_SCRATCH_BYTES = 384L * 1_024L * 1_024L
private const val MIN_FREE_BYTES = 16L * 1_024L * 1_024L
