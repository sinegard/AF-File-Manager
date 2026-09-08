package com.affilemanager.app.ui.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal val LocalAppearanceSettings = staticCompositionLocalOf { AppearanceSettings() }
internal val LocalOpaqueColors = staticCompositionLocalOf { androidx.compose.material3.lightColorScheme() }
private val LocalWallpaper = staticCompositionLocalOf<Bitmap?> { null }

/** One private, bounded image, decoded off the UI thread only when its revision changes. */
internal object AppearanceWallpaper {
    const val MAX_SOURCE_BYTES = 32L * 1024 * 1024
    const val MAX_EDGE = 2048
    fun file(context: Context, revision: Long) = File(context.filesDir, "appearance/wallpaper-$revision.jpg")

    fun cleanup(context: Context, keepRevision: Long) {
        File(context.filesDir, "appearance").listFiles()?.filter { candidate ->
            candidate.isFile && (candidate.name.matches(Regex("import-.*\\.tmp")) ||
                (candidate.name.matches(Regex("wallpaper-[0-9]+\\.jpg")) && candidate != file(context, keepRevision)))
        }?.forEach { it.delete() }
    }

    fun import(context: Context, uri: Uri, checkActive: () -> Unit = {}): Long {
        require(uri.scheme == "content") { "Pasirinkite paveikslėlį" }
        val directory = File(context.filesDir, "appearance").apply { check(isDirectory || mkdirs()) }
        val source = File.createTempFile("import-", ".tmp", directory)
        val revision = System.nanoTime().and(Long.MAX_VALUE).coerceAtLeast(1L)
        val output = file(context, revision)
        try {
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Failo perskaityti nepavyko" }
                source.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        checkActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        require(bytes <= MAX_SOURCE_BYTES) { "Failas per didelis" }
                        target.write(buffer, 0, read)
                    }
                }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Failo peržiūros sukurti nepavyko" }
            var sample = 1
            while (bounds.outWidth / sample > MAX_EDGE || bounds.outHeight / sample > MAX_EDGE) sample *= 2
            val bitmap = requireNotNull(BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply { inSampleSize = sample }))
            try {
                val exif = runCatching { ExifInterface(source) }.getOrNull()
                val transform = Matrix().apply {
                    if (exif?.isFlipped == true) postScale(-1f, 1f)
                    postRotate(exif?.rotationDegrees?.toFloat() ?: 0f)
                }
                val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
                try {
                    checkActive()
                    output.outputStream().use { check(oriented.compress(Bitmap.CompressFormat.JPEG, 90, it)) }
                } finally { if (oriented !== bitmap) oriented.recycle() }
            }
            finally { bitmap.recycle() }
            return revision
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally { source.delete() }
    }
}

@Composable
internal fun ProvideAppearanceWallpaper(content: @Composable () -> Unit) {
    val settings = LocalAppearanceSettings.current
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, settings.wallpaperRevision) {
        value = if (settings.wallpaperRevision == 0L) null else withContext(Dispatchers.IO) {
            BitmapFactory.decodeFile(AppearanceWallpaper.file(context, settings.wallpaperRevision).path)
        }
    }
    CompositionLocalProvider(LocalWallpaper provides bitmap, content = content)
}

@Composable
internal fun AppearanceBackground() {
    val settings = LocalAppearanceSettings.current
    val bitmap = LocalWallpaper.current
    Box(Modifier.fillMaxSize().background(LocalOpaqueColors.current.background)) {
        bitmap?.let {
            Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().testTag("appearance_wallpaper"))
            // Keep text outside cards readable over photographs. Does not modify the user's image.
            Box(Modifier.fillMaxSize().background(LocalOpaqueColors.current.background.copy(alpha = settings.wallpaperShading.coerceIn(0, 100) / 100f)))
        }
    }
}

/** Page-sized dialogs and Activities share the same decoded wallpaper as the root, not a card fill. */
@Composable
internal fun AppearancePage(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier) {
        AppearanceBackground()
        AppearanceContentOn(LocalOpaqueColors.current.background) {
        Surface(color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = LocalOpaqueColors.current.onBackground, modifier = Modifier.fillMaxSize(), content = content)
        }
    }
}
