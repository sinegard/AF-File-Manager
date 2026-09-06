package com.affilemanager.app.ui.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class AppearanceWallpaperTest {
    @Test fun importHonorsOrientationIsPrivateAndFailedOrCancelledImportLeavesNoDebris() {
        check(android.os.Build.MODEL.contains("sdk"))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "wallpaper-test-${UUID.randomUUID()}").apply { mkdir() }
        val repository = AppearanceRepository(app)
        val before = repository.settings.value
        var imported = 0L
        try {
            val source = File(root, "portrait.jpg")
            Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888).let { bitmap ->
                bitmap.eraseColor(android.graphics.Color.BLUE)
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                bitmap.recycle()
            }
            ExifInterface(source).apply { setAttribute(ExifInterface.TAG_ORIENTATION, "6"); saveAttributes() }
            val original = source.readBytes()
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", source)
            imported = AppearanceWallpaper.import(app, uri)
            val stored = AppearanceWallpaper.file(app, imported)
            assertTrue(stored.canonicalPath.startsWith(app.filesDir.canonicalPath + File.separator))
            BitmapFactory.decodeFile(stored.path).let { bitmap ->
                assertEquals(64, bitmap.width); assertEquals(96, bitmap.height); bitmap.recycle()
            }
            assertArrayEquals(original, source.readBytes())
            repository.setWallpaperRevision(imported)
            repository.setCardTransparency(100)
            val restored = AppearanceRepository(app).settings.value
            assertEquals(imported, restored.wallpaperRevision)
            assertEquals(100, restored.cardTransparency)
            repository.setCardTransparency(-1)
            assertEquals(0, AppearanceRepository(app).settings.value.cardTransparency)
            assertThrows(kotlinx.coroutines.CancellationException::class.java) {
                AppearanceWallpaper.import(app, uri) { throw kotlinx.coroutines.CancellationException() }
            }
            source.writeText("invalid image")
            assertThrows(IllegalArgumentException::class.java) { AppearanceWallpaper.import(app, uri) }
            assertTrue(stored.isFile)
            val leftover = File(stored.parentFile, "import-abandoned.tmp").apply { writeText("test") }
            AppearanceWallpaper.cleanup(app, imported)
            assertFalse(leftover.exists())
            assertEquals(listOf(stored.name), stored.parentFile!!.listFiles().orEmpty().map { it.name })
        } finally {
            repository.setWallpaperRevision(before.wallpaperRevision)
            repository.setCardTransparency(before.cardTransparency)
            if (imported != 0L) AppearanceWallpaper.file(app, imported).delete()
            root.deleteRecursively()
        }
    }
}
