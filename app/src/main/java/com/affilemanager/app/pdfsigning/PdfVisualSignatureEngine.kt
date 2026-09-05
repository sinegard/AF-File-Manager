package com.affilemanager.app.pdfsigning

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.affilemanager.app.editing.EditLimits
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.util.Matrix
import java.io.File
import java.io.RandomAccessFile

class PdfVisualSignatureEngine(cacheDirectory: File) {
    private val scratchRoot = File(cacheDirectory, "pdf-signing-scratch").canonicalFile

    init {
        if (scratchRoot.exists()) scratchRoot.deleteRecursively()
        require(scratchRoot.mkdirs() || scratchRoot.isDirectory) { "Nepavyko paruošti privačios PDF darbo vietos" }
    }

    fun apply(
        source: File,
        destination: File,
        drawing: SignatureDrawing,
        placement: PdfSignaturePlacement,
    ) {
        require(source.canonicalFile != destination.canonicalFile) { "PDF darbo failai negali sutapti" }
        require(source.isFile && source.canRead()) { "PDF failas nepasiekiamas" }
        require(source.length() in 1..EditLimits.MAX_FILE_BYTES) { "PDF failas per didelis redaguoti" }
        val signature = VisualSignatureRules.validate(drawing)
        requireScratchSpace(source.length())

        val document = try {
            PDDocument.load(source, memoryUsage())
        } catch (error: InvalidPasswordException) {
            throw IllegalArgumentException("Slaptažodžiu apsaugotų PDF pasirašymas dar nepalaikomas", error)
        } catch (error: Throwable) {
            throw IllegalArgumentException("PDF perskaityti nepavyko", error)
        }

        val expectedPageCount = document.numberOfPages
        document.use { pdf ->
            require(!pdf.isEncrypted) { "Slaptažodžiu apsaugotų PDF pasirašymas dar nepalaikomas" }
            require(pdf.signatureDictionaries.isEmpty()) {
                "PDF jau turi kriptografinį parašą; jo keitimas galėtų panaikinti parašo galiojimą"
            }
            val pageCount = pdf.numberOfPages
            val boundedPlacement = VisualSignatureRules.validatePlacement(placement, pageCount)
            val page = pdf.getPage(boundedPlacement.pageIndex)
            val crop = page.cropBox
            val matrix = VisualSignatureRules.imageMatrix(
                cropLeft = crop.lowerLeftX,
                cropBottom = crop.lowerLeftY,
                cropWidth = crop.width,
                cropHeight = crop.height,
                pageRotation = page.rotation,
                placement = boundedPlacement,
            )
            val bitmap = renderSignature(signature)
            try {
                val image = LosslessFactory.createFromImage(pdf, bitmap)
                PDPageContentStream(
                    pdf,
                    page,
                    PDPageContentStream.AppendMode.APPEND,
                    true,
                    true,
                ).use { content ->
                    content.drawImage(image, Matrix(matrix.a, matrix.b, matrix.c, matrix.d, matrix.e, matrix.f))
                }
                pdf.save(destination)
            } finally {
                bitmap.recycle()
            }
        }

        require(destination.isFile && destination.canRead() && destination.length() > 0L) { "Pasirašyto PDF sukurti nepavyko" }
        require(destination.length() <= EditLimits.MAX_FILE_BYTES) { "Pasirašytas PDF viršijo saugaus redagavimo ribą" }
        verify(destination, expectedPages = expectedPageCount)
        RandomAccessFile(destination, "rw").use { it.fd.sync() }
    }

    private fun renderSignature(drawing: SignatureDrawing): Bitmap {
        val bitmap = Bitmap.createBitmap(
            VisualSignatureRules.BITMAP_WIDTH,
            VisualSignatureRules.BITMAP_HEIGHT,
            Bitmap.Config.ARGB_8888,
        )
        bitmap.eraseColor(Color.TRANSPARENT)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 7f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val canvas = Canvas(bitmap)
        drawing.strokes.forEach { stroke ->
            val first = stroke.points.first()
            if (stroke.points.size == 1) {
                canvas.drawCircle(
                    first.x * bitmap.width,
                    first.y * bitmap.height,
                    paint.strokeWidth / 2f,
                    paint.apply { style = Paint.Style.FILL },
                )
                paint.style = Paint.Style.STROKE
            } else {
                val path = Path().apply {
                    moveTo(first.x * bitmap.width, first.y * bitmap.height)
                    stroke.points.drop(1).forEach { point ->
                        lineTo(point.x * bitmap.width, point.y * bitmap.height)
                    }
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    private fun verify(file: File, expectedPages: Int) {
        val verified = try {
            PDDocument.load(file, memoryUsage())
        } catch (error: Throwable) {
            throw IllegalStateException("Pasirašyto PDF patikra nepavyko", error)
        }
        verified.use { pdf ->
            require(!pdf.isEncrypted) { "Pasirašyto PDF patikra nepavyko" }
            require(pdf.numberOfPages == expectedPages) { "Pasirašyto PDF puslapių patikra nepavyko" }
        }
    }

    private fun memoryUsage(): MemoryUsageSetting = MemoryUsageSetting
        .setupMixed(MAX_MAIN_MEMORY_BYTES, MAX_SCRATCH_BYTES)
        .setTempDir(scratchRoot)

    private fun requireScratchSpace(sourceBytes: Long) {
        val needed = (sourceBytes.coerceAtMost(EditLimits.MAX_FILE_BYTES) * 2L + MIN_FREE_BYTES)
            .coerceAtMost(MAX_SCRATCH_BYTES)
        val available = scratchRoot.usableSpace
        require(available <= 0L || available >= needed) { "Nepakanka vietos saugiai PDF darbo kopijai" }
    }

    private companion object {
        const val MAX_MAIN_MEMORY_BYTES = 16L * 1_024L * 1_024L
        const val MAX_SCRATCH_BYTES = 768L * 1_024L * 1_024L
        const val MIN_FREE_BYTES = 24L * 1_024L * 1_024L
    }
}
