package com.affilemanager.app.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.media.editing.MediaTestFixtures
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.ui.components.FileVisualLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID

class EmbeddedAudioArtworkTest {
    @Test fun genuineOpusCoverUsesBoundedSharedLoaderAndNoCoverKeepsTheTypeIcon() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val root = File(app.cacheDir, "opus-art-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("covered.opus")
        val noCover = root.resolve("plain.opus")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                MediaTestFixtures.opus(noCover)
                noCover.copyTo(source)
                val picture = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
                val jpeg = try { ByteArrayOutputStream().use { output -> check(picture.compress(Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() } }
                    finally { picture.recycle() }
                source.writeBytes(attachPicture(source.readBytes(), jpeg))
                val evidence = requireNotNull(app.getExternalFilesDir("validation"))
                source.copyTo(File(evidence, "generated-covered.opus"), overwrite = true)
                noCover.copyTo(File(evidence, "generated-plain.opus"), overwrite = true)
            } else {
                // API26 cannot encode Opus. Generate these genuine streams by running this
                // test on API29+, collect them with scripts/stage-audio-artwork-fixtures.ps1,
                // then stage them on this guest. Generated audio stays out of the repository.
                val fixtures = requireNotNull(app.getExternalFilesDir("artwork-fixtures"))
                val coveredFixture = File(fixtures, "covered.opus")
                val plainFixture = File(fixtures, "plain.opus")
                require(coveredFixture.isFile && plainFixture.isFile) { "Stage genuine API29+ Opus fixtures before this API26 test" }
                coveredFixture.copyTo(source)
                plainFixture.copyTo(noCover)
            }
            val original = java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes())
            val reader = MediaMetadataRetriever()
            try {
                reader.setDataSource(source.path)
                val duration = requireNotNull(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)).toLong()
                assertTrue("Not a real three-second Opus stream: $duration", duration in 2900..3300)
            } finally { reader.release() }
            val bytes = requireNotNull(EmbeddedAudioArtwork.read(source))
            val documentUri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", source)
            assertArrayEquals(bytes, EmbeddedAudioArtwork.read(app, documentUri))
            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            try { assertEquals(48, decoded.width); assertTrue(Color.blue(decoded.getPixel(24, 24)) > 200) } finally { decoded.recycle() }
            assertNull(EmbeddedAudioArtwork.read(noCover))
            val invalid = root.resolve("oversized-picture.opus").apply { writeBytes(attachPicture(noCover.readBytes(), byteArrayOf(1, 2), Int.MAX_VALUE)) }
            assertNull(EmbeddedAudioArtwork.read(invalid))
            val truncated = root.resolve("truncated.opus").apply { writeBytes(source.readBytes().copyOf(20)) }
            assertNull(EmbeddedAudioArtwork.read(truncated))
            val entry = app.graph.localFiles.toEntry(source)
            assertEquals(EntryKind.AUDIO, entry.kind)
            val visual = requireNotNull(FileVisualLoader.loadLocal(app, entry, 96, 96, true))
            assertTrue(visual.contentThumbnail)
            assertTrue(visual.bitmap.width <= 96 && Color.blue(visual.bitmap.getPixel(24, 24)) > 200)
            assertNull(FileVisualLoader.loadLocal(app, app.graph.localFiles.toEntry(noCover), 96, 96, true))
            assertArrayEquals(original, java.security.MessageDigest.getInstance("SHA-256").digest(source.readBytes()))
        } finally { root.deleteRecursively() }
    }

    /** Replace only the single OpusTags page and its Ogg CRC; encoded audio packets stay intact. */
    private fun attachPicture(original: ByteArray, jpeg: ByteArray, declaredBytes: Int = jpeg.size): ByteArray {
        val picture = ByteArrayOutputStream().also { out -> DataOutputStream(out).use { data ->
            data.writeInt(3); val mime = "image/jpeg".toByteArray(); data.writeInt(mime.size); data.write(mime)
            data.writeInt(0); data.writeInt(48); data.writeInt(48); data.writeInt(24); data.writeInt(0); data.writeInt(declaredBytes); data.write(jpeg)
        } }.toByteArray()
        val comment = ("METADATA_BLOCK_PICTURE=" + Base64.encodeToString(picture, Base64.NO_WRAP)).toByteArray()
        val tags = ByteArrayOutputStream().apply {
            fun little(value: Int) { repeat(4) { write(value ushr (8 * it) and 255) } }
            write("OpusTags".toByteArray()); val vendor = "AF generated test".toByteArray()
            little(vendor.size); write(vendor); little(1); little(comment.size); write(comment)
        }.toByteArray()
        var offset = 0
        while (offset + 27 <= original.size) {
            check(original.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII) == "OggS")
            val segments = original[offset + 26].toInt() and 255
            val body = offset + 27 + segments
            val size = (0 until segments).sumOf { original[offset + 27 + it].toInt() and 255 }
            if (size >= 8 && original.copyOfRange(body, body + 8).toString(Charsets.US_ASCII) == "OpusTags") {
                check(original[body + size - 1] == 0.toByte() || size > 8)
                val laces = tags.size / 255 + 1
                val page = original.copyOfRange(offset, offset + 27) + ByteArray(laces) { index -> minOf(255, tags.size - index * 255).toByte() } + tags
                page[26] = laces.toByte(); for (i in 22..25) page[i] = 0
                var crc = 0
                page.forEach { byte -> crc = crc xor ((byte.toInt() and 255) shl 24); repeat(8) {
                    crc = if (crc and Int.MIN_VALUE != 0) (crc shl 1) xor 0x04c11db7 else crc shl 1
                } }
                for (i in 0..3) page[22 + i] = (crc ushr (8 * i)).toByte()
                return original.copyOfRange(0, offset) + page + original.copyOfRange(body + size, original.size)
            }
            offset = body + size
        }
        error("No OpusTags page in genuine platform fixture")
    }
}
