package com.affilemanager.app.ui.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileVisualLoaderTest {
    @Test
    fun realImageProducesBoundedThumbnail() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val imageFile = File(application.cacheDir, "thumbnail-${System.nanoTime()}.png")
        try {
            val source = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.rgb(0, 121, 107))
            }
            imageFile.outputStream().use { output -> assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            source.recycle()
            val entry = fileEntry(imageFile, EntryKind.IMAGE)

            val visual = FileVisualLoader.loadLocal(application, entry, 96, 96, showThumbnails = true)

            assertNotNull(visual)
            assertTrue(requireNotNull(visual).bitmap.width <= 96)
            assertTrue(visual.bitmap.height <= 96)
            assertTrue(visual.crop)
            assertTrue(visual.contentThumbnail)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun iconsModeDoesNotReturnImageContentAsAThumbnail() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val imageFile = File(application.cacheDir, "icon-mode-${System.nanoTime()}.png")
        try {
            val source = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.MAGENTA)
            }
            imageFile.outputStream().use { output -> assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            source.recycle()

            val visual = FileVisualLoader.loadLocal(
                application,
                fileEntry(imageFile, EntryKind.IMAGE),
                96,
                96,
                showThumbnails = false,
            )

            assertFalse(visual?.contentThumbnail == true)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun installedTargetApkProvidesItsRealApplicationIcon() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val apk = File(application.applicationInfo.sourceDir)

        val visual = FileVisualLoader.loadLocal(application, fileEntry(apk, EntryKind.APK), 96, 96, showThumbnails = false)

        assertNotNull(visual)
        assertFalse(requireNotNull(visual).crop)
        assertFalse(visual.contentThumbnail)
        assertTrue(visual.bitmap.width <= 96)
        assertTrue(visual.bitmap.height <= 96)
    }

    @Test
    fun xmlAndZipUseBuiltInTypeIconsInsteadOfAnAssociatedApplicationIcon() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val xml = File(application.cacheDir, "type-${System.nanoTime()}.xml").apply { writeText("<root />") }
        val zip = File(application.cacheDir, "type-${System.nanoTime()}.zip").apply { writeBytes(byteArrayOf()) }
        try {
            assertNull(
                FileVisualLoader.loadLocal(
                    application,
                    fileEntry(xml, EntryKind.DOCUMENT),
                    96,
                    96,
                    showThumbnails = false,
                ),
            )
            assertNull(
                FileVisualLoader.loadLocal(
                    application,
                    fileEntry(zip, EntryKind.ARCHIVE),
                    96,
                    96,
                    showThumbnails = false,
                ),
            )
        } finally {
            xml.delete()
            zip.delete()
        }
    }

    private fun fileEntry(file: File, kind: EntryKind) = FileEntry(
        absolutePath = file.absolutePath,
        name = file.name,
        kind = kind,
        sizeBytes = file.length(),
        modifiedAtMillis = file.lastModified(),
        isHidden = false,
        isReadable = file.canRead(),
        isWritable = file.canWrite(),
    )
}
