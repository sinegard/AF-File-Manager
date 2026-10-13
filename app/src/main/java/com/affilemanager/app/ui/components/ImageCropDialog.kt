package com.affilemanager.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.preview.PreviewLoadError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.math.min
import kotlin.math.roundToInt

internal object ImageCropStore {
    private const val MAX_SOURCE = 32L * 1024 * 1024
    private const val MAX_EDGE = 2048
    fun load(context: Context, uri: Uri, checkActive: () -> Unit): Bitmap {
        require(uri.scheme == "content") { "Pasirinkite paveikslėlį" }
        val directory = File(context.cacheDir, "crop-source").apply { require(isDirectory || mkdirs()) }
        val source = File.createTempFile("source-", ".tmp", directory)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Failo perskaityti nepavyko" }
                source.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        checkActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_SOURCE) { "Failas per didelis" }
                        target.write(buffer, 0, count)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Failo peržiūros sukurti nepavyko" }
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) sample *= 2
            val needed = (bounds.outWidth.toLong() / sample + 1) * (bounds.outHeight.toLong() / sample + 1) * 12
            val runtime = Runtime.getRuntime()
            val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
            require(needed <= available - available / 4) { "Paveikslėliui nepakanka atminties" }
            val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            val exif = ExifInterface(source)
            val matrix = Matrix().apply { if (exif.isFlipped) postScale(-1f, 1f); postRotate(exif.rotationDegrees.toFloat()) }
            return try { Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                .also { if (it !== bitmap) bitmap.recycle() } } catch (error: Throwable) { bitmap.recycle(); throw error }
        } finally { source.delete() }
    }

    fun save(context: Context, role: String, bitmap: Bitmap, region: ImageCropRegion): Uri {
        require(role in setOf("receiver", "group", "wallpaper"))
        val directory = File(context.filesDir, "visual-crops").apply { require(isDirectory || mkdirs()) }
        val output = File(directory, "$role.jpg")
        val stage = File.createTempFile("$role-", ".partial", directory)
        val x = (region.x * bitmap.width).roundToInt().coerceIn(0, bitmap.width - 1)
        val y = (region.y * bitmap.height).roundToInt().coerceIn(0, bitmap.height - 1)
        val width = (region.width * bitmap.width).roundToInt().coerceIn(1, bitmap.width - x)
        val height = (region.height * bitmap.height).roundToInt().coerceIn(1, bitmap.height - y)
        val crop = Bitmap.createBitmap(bitmap, x, y, width, height)
        try {
            java.io.FileOutputStream(stage).use { stream -> require(crop.compress(Bitmap.CompressFormat.JPEG, 90, stream)); stream.fd.sync() }
            require(stage.length() <= 4L * 1024 * 1024) { "Paveikslėlis per didelis" }
            try { Files.move(stage.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(stage.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING) }
            return FileProvider.getUriForFile(context, "${context.packageName}.files", output).buildUpon()
                .appendQueryParameter("revision", System.nanoTime().toString()).build()
        } finally { if (crop !== bitmap) crop.recycle(); stage.delete() }
    }
}

@Composable
internal fun ImageCropDialog(uri: Uri, role: String, aspect: Float?, onDismiss: () -> Unit, onCropped: (Uri) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val result by produceState<Result<Bitmap>?>(null, uri) {
        value = withContext(Dispatchers.IO) { runCatching { val jobContext = coroutineContext; ImageCropStore.load(context, uri) { jobContext.ensureActive() } } }
    }
    val bitmap = result?.getOrNull()
    val ratio = bitmap?.let { aspect?.times(it.height.toFloat() / it.width) }
    var region by remember(uri, ratio) { mutableStateOf(ImageCropRegion.centered(ratio)) }
    val latestRegion by rememberUpdatedState(region)
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val outline = MaterialTheme.colorScheme.primary
    AfModalDialog(title = "Apkirpti paveikslėlį", icon = Icons.Rounded.Crop, onDismissRequest = { if (!saving) onDismiss() }, expandedContent = true,
        modifier = Modifier.testTag("image_crop_dialog"), actions = {
            TextButton(onClick = { region = ImageCropRegion.centered(ratio) }, enabled = !saving) { LText("Atkurti") }
            TextButton(onClick = onDismiss, enabled = !saving) { LText("Atšaukti") }
            TextButton(onClick = { if (bitmap != null) scope.launch {
                saving = true
                try {
                    val output = withContext(Dispatchers.IO) { ImageCropStore.save(context, role, bitmap, region) }
                    onCropped(output)
                } catch (failure: Throwable) { if (failure is kotlinx.coroutines.CancellationException) throw failure; error = failure }
                finally { saving = false }
            } }, enabled = bitmap != null && !saving, modifier = Modifier.testTag("image_crop_confirm")) { LText("Naudoti") }
        }) {
        Column(Modifier.fillMaxSize().padding(14.dp)) {
            LText("Vilkite rėmelį arba jo apatinį kampą.")
            (error ?: result?.exceptionOrNull())?.let { PreviewLoadError(it) }
            if (bitmap != null) Canvas(Modifier.fillMaxWidth().weight(1f).testTag("image_crop_canvas").pointerInput(bitmap, saving) {
                fun fit() = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (saving) return@awaitEachGesture
                    val scale = fit()
                    val left = (size.width - bitmap.width * scale) / 2
                    val top = (size.height - bitmap.height * scale) / 2
                    val initial = latestRegion
                    val bottomRight = Offset(left + (initial.x + initial.width) * bitmap.width * scale, top + (initial.y + initial.height) * bitmap.height * scale)
                    // Choose the handle from the first touch, not a later point beyond touch slop.
                    val resizing = (down.position - bottomRight).getDistance() <= 36.dp.toPx()
                    val accepted = resizing || (down.position.x in (left + initial.x * bitmap.width * scale)..bottomRight.x &&
                        down.position.y in (top + initial.y * bitmap.height * scale)..bottomRight.y)
                    if (!accepted) return@awaitEachGesture
                    down.consume()
                    var previous = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed || event.changes.count { it.pressed } > 1) break
                        val delta = change.position - previous
                        previous = change.position
                        change.consume()
                        val dx = delta.x / (bitmap.width * scale)
                        val dy = delta.y / (bitmap.height * scale)
                        // An event burst need not recompose before the next pointer delta.
                        region = if (resizing) region.resized(dx, dy, ratio) else region.moved(dx, dy)
                    }
                }
            }) {
                val scale = min(size.width / bitmap.width, size.height / bitmap.height)
                val imageWidth = bitmap.width * scale
                val imageHeight = bitmap.height * scale
                val left = (size.width - imageWidth) / 2
                val top = (size.height - imageHeight) / 2
                drawImage(bitmap.asImageBitmap(), dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(imageWidth.roundToInt(), imageHeight.roundToInt()))
                val cropLeft = left + region.x * imageWidth
                val cropTop = top + region.y * imageHeight
                val cropWidth = region.width * imageWidth
                val cropHeight = region.height * imageHeight
                drawRect(outline, Offset(cropLeft, cropTop), Size(cropWidth, cropHeight), style = Stroke(3.dp.toPx()))
                drawCircle(Color.White, 11.dp.toPx(), Offset(cropLeft + cropWidth, cropTop + cropHeight))
                drawCircle(outline, 8.dp.toPx(), Offset(cropLeft + cropWidth, cropTop + cropHeight))
            }
        }
    }
}
