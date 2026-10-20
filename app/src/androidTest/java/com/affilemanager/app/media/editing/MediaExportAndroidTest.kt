@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.affilemanager.app.media.editing

import android.content.Context
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.editing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt

class MediaExportAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun digest(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    @Test fun pitchVolumeMuteAndSpeedChangeTheActualPcmNotOnlyTheControls() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-signal-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("sine.wav").also { MediaTestFixtures.wav(it) }
        val original = digest(source)
        val uri = Uri.fromFile(source)
        val info = MediaExportEngine.info(context, uri, source.length())
        try {
            data class Setting(val speed: Float, val pitch: Float, val gain: Float, val frequency: Double)
            listOf(Setting(1f, 1f, .5f, 440.0), Setting(1f, 1.5f, 2f, 660.0),
                Setting(2f, 1f, 1f, 440.0), Setting(1f, 1f, 0f, 0.0)).forEachIndexed { index, setting ->
                val result = MediaExportEngine(context).export(source, uri, info, MediaEditOptions(
                    MediaExportFormat.WAV, 250, 2250, speed = setting.speed, pitch = setting.pitch, volume = setting.gain), "signal-$index")
                try {
                    val pcm = wavSignal(result.file)
                    assertTrue("Native duration ${result.durationMs}", abs(result.durationMs - (2000 / setting.speed).toLong()) <= 150)
                    if (setting.gain == 0f) assertTrue("Mute left audible PCM", pcm.all { it == 0 })
                    else {
                        val rms = sqrt(pcm.sumOf { it.toDouble() * it } / pcm.size)
                        assertTrue("Gain ${setting.gain}: RMS $rms", abs(rms - 6000 / sqrt(2.0) * setting.gain) < 700)
                        val crossings = pcm.indices.drop(1).count { pcm[it - 1] <= 0 && pcm[it] > 0 }
                        val frequency = crossings * 48000.0 / pcm.size
                        assertTrue("Pitch/speed: $frequency Hz instead of ${setting.frequency}", abs(frequency - setting.frequency) < 15)
                    }
                } finally { result.file.delete() }
            }
            assertEquals(original, digest(source))
        } finally { root.deleteRecursively() }
    }
    @Test fun sampleRateAndChannelControlsChangeTheActualExportedTracks() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-resample-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("original.wav").also { MediaTestFixtures.wav(it) }
        val original = digest(source)
        try {
            val uri = Uri.fromFile(source)
            val info = MediaExportEngine.info(context, uri, source.length())
            assertEquals(48000, info.audioSampleRate)
            for ((format, rate, channels) in listOf(Triple(MediaExportFormat.WAV, 22050, 1),
                Triple(MediaExportFormat.WAV, 44100, 2), Triple(MediaExportFormat.M4A, 32000, 1))) {
                val result = MediaExportEngine(context).export(source, uri, info,
                    MediaEditOptions(format, 0, 2000, sampleRate = rate, channels = channels), "resampled")
                try {
                    val actual = MediaExportEngine.info(context, Uri.fromFile(result.file), result.file.length())
                    assertEquals(rate, actual.audioSampleRate)
                    assertEquals(channels, actual.audioChannels)
                    assertTrue(abs(actual.durationMs - 2000) <= 150)
                    assertTrue(result.file.length() > 1000)
                } finally { result.file.delete() }
            }
            assertEquals(original, digest(source))
        } finally { root.deleteRecursively() }
    }

    @Test fun allMetadataFieldsAndRealJpegCoverSurviveNativeAudioExports() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-tags-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("original.wav").also { MediaTestFixtures.wav(it) }
        val original = digest(source)
        val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        val cover = try { java.io.ByteArrayOutputStream().use { output -> check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output)); output.toByteArray() } } finally { bitmap.recycle() }
        val tags = MediaTags("Title 音楽", "Artist AF", "Album AF", "Album artist AF", "Composer AF", "Publisher AF", "2026", "Genre AF", "first line\nsecond line", "Comment AF")
        try {
            val uri = Uri.fromFile(source); val info = MediaExportEngine.info(context, uri, source.length())
            listOf(MediaExportFormat.M4A, MediaExportFormat.WAV).forEach { format ->
                val result = MediaExportEngine(context).export(source, uri, info, MediaEditOptions(format, 0, 2000, tags = tags), "tagged", cover)
                try {
                    val contents = result.file.readBytes()
                    val text = contents.toString(Charsets.UTF_8)
                    tags.values().values.forEach { assertTrue("Missing $format tag $it", text.contains(it)) }
                    assertTrue("Missing actual JPEG cover in $format", (0..contents.size - cover.size).any { offset ->
                        contents[offset] == cover[0] && cover.indices.all { contents[offset + it] == cover[it] }
                    })
                    if (format == MediaExportFormat.M4A) {
                        val reader = MediaMetadataRetriever()
                        try {
                            reader.setDataSource(result.file.path)
                            assertEquals(tags.title, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE))
                            assertEquals(tags.album, reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM))
                            val decoded = requireNotNull(android.graphics.BitmapFactory.decodeByteArray(requireNotNull(reader.embeddedPicture), 0, reader.embeddedPicture!!.size))
                            try { assertEquals(32, decoded.width); assertTrue(Color.blue(decoded.getPixel(16, 16)) > 200) } finally { decoded.recycle() }
                        } finally { reader.release() }
                    }
                } finally { result.file.delete() }
            }
            assertEquals(original, digest(source))
        } finally { root.deleteRecursively() }
    }
    private fun wavSignal(file: File): List<Int> = java.io.RandomAccessFile(file, "r").use { input ->
        fun little() = Integer.reverseBytes(input.readInt())
        input.seek(12)
        var channels = 0
        while (input.filePointer + 8 <= input.length()) {
            val id = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
            val length = little(); val start = input.filePointer
            check(length >= 0 && start + length <= input.length())
            if (id == "fmt ") {
                assertEquals(1, java.lang.Short.reverseBytes(input.readShort()).toInt())
                channels = java.lang.Short.reverseBytes(input.readShort()).toInt()
                assertEquals(48000, little()); input.skipBytes(6)
                assertEquals(16, java.lang.Short.reverseBytes(input.readShort()).toInt())
            } else if (id == "data") {
                check(channels in 1..2 && length >= 48_000)
                val samples = List(length / 2) { java.lang.Short.reverseBytes(input.readShort()).toInt() }
                // Ignore processor start/end padding; use the first channel only.
                return@use samples.chunked(channels).map { it.first() }.let { it.subList(2400, it.size - 2400) }
            }
            input.seek(start + length + length % 2)
        }
        error("Missing PCM data")
    }
    @Test fun realVideoTrimCropSpeedAndAudioExtractionPreserveTheOriginal() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("original.mp4")
        try {
            MediaTestFixtures.video(source)
            val before = digest(source); val uri = Uri.fromFile(source); val info = MediaExportEngine.info(context, uri, source.length())
            assertTrue(info.hasVideo && info.hasAudio)
            val engine = MediaExportEngine(context)
            val compressed = engine.export(source, uri, info, MediaEditOptions(MediaExportFormat.MP4, 0, info.durationMs,
                frameRate = 5, videoBitrate = 128_000, audioBitrate = 32_000), "compressed")
            try {
                assertTrue("Explicit lower bitrate compression did not reduce this fixture", compressed.file.length() < source.length())
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(compressed.file.path)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString("mime")!!.startsWith("video/") }
                    extractor.selectTrack(track); var frames = 0
                    while (extractor.sampleTime >= 0) { frames++; extractor.advance() }
                    assertTrue("Frame rate limit did not drop frames: $frames", frames in 10..16)
                } finally { extractor.release() }
            } finally { compressed.file.delete() }
            val trim = engine.export(source, uri, info, MediaEditOptions(MediaExportFormat.MP4, 500, 2500,
                speed = 2f, volume = .5f, crop = MediaCrop(.5f, 0f, .5f, 1f), height = 240, frameRate = 10), "cut")
            try {
                assertEquals("cut.mp4", trim.displayName); assertEquals(before, trim.sourceSha256)
                val reader = MediaMetadataRetriever()
                try {
                    reader.setDataSource(trim.file.path)
                    assertEquals("160", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
                    assertEquals("240", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
                    val bitmap = requireNotNull(reader.getFrameAtTime(200_000, MediaMetadataRetriever.OPTION_CLOSEST))
                    try { val color = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2); assertTrue(Color.green(color) > 150 && Color.red(color) < 80) } finally { bitmap.recycle() }
                } finally { reader.release() }
            } finally { trim.file.delete() }
            val audio = engine.export(source, uri, info, MediaEditOptions(MediaExportFormat.M4A, 500, 2500, tags = MediaTags(title = "AF title", artist = "AF artist", lyrics = "one\ntwo")), "audio", byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()))
            try {
                assertEquals("audio.m4a", audio.displayName)
                val extractor = MediaExtractor()
                try { extractor.setDataSource(audio.file.path); assertEquals(1, extractor.trackCount); assertEquals("audio/mp4a-latm", extractor.getTrackFormat(0).getString("mime")) } finally { extractor.release() }
                val reader = MediaMetadataRetriever()
                try { reader.setDataSource(audio.file.path); assertEquals("AF title", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)); assertEquals("AF artist", reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)) } finally { reader.release() }
            } finally { audio.file.delete() }
            val pcmAudio = engine.export(source, uri, info, MediaEditOptions(MediaExportFormat.WAV, 500, 2500), "decoded-audio")
            try {
                val pcm = wavSignal(pcmAudio.file)
                assertTrue("Decoded AAC duration", abs(pcmAudio.durationMs - 2000) <= 150)
                val frequency = pcm.indices.drop(1).count { pcm[it - 1] <= 0 && pcm[it] > 0 } * 48000.0 / pcm.size
                assertTrue("Decoded AAC to WAV: $frequency Hz", abs(frequency - 440) < 15)
            } finally { pcmAudio.file.delete() }
            val fullGif = engine.export(source, uri, info, MediaEditOptions(MediaExportFormat.GIF, 0, info.durationMs,
                height = 480, frameRate = 10), "audio-tail-animation")
            try {
                val movie = requireNotNull(android.graphics.Movie.decodeFile(fullGif.file.path))
                assertTrue("GIF retained full selected duration", abs(movie.duration() - info.durationMs) <= 10)
                assertEquals(480, movie.width())
            } finally { fullGif.file.delete() }
            assertEquals(before, digest(source))
        } finally { root.deleteRecursively() }
    }
    @Test fun wavPcmExportIsTrimmedAndTaggedAndGifIsRealDecodableAnimation() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val source = root.resolve("sound.wav").also { MediaTestFixtures.wav(it) }
            val before = digest(source); val uri = Uri.fromFile(source); val info = MediaExportEngine.info(context, uri, source.length())
            val sound = MediaExportEngine(context).export(source, uri, info, MediaEditOptions(MediaExportFormat.WAV, 500, 2500,
                speed = 2f, pitch = 1.2f, volume = .5f, tags = MediaTags(title = "AF WAV", lyrics = "test")), "result")
            try { assertEquals("RIFF", sound.file.inputStream().use { input -> ByteArray(4).also { assertEquals(4, input.read(it)) }.toString(Charsets.US_ASCII) }); assertTrue(sound.file.readBytes().toString(Charsets.ISO_8859_1).contains("TIT2")); assertTrue(sound.durationMs in 650..1350) }
            finally { sound.file.delete() }
            assertEquals(before, digest(source))
            val video = root.resolve("video.mp4").also { MediaTestFixtures.video(it, false) }
            val videoUri = Uri.fromFile(video); val videoInfo = MediaExportEngine.info(context, videoUri, video.length()); val original = digest(video)
            val gif = MediaExportEngine(context).export(video, videoUri, videoInfo, MediaEditOptions(MediaExportFormat.GIF, 0, 1000,
                frameRate = 5, crop = MediaCrop(.5f, 0f, .5f, 1f)), "animation")
            try {
                val movie = requireNotNull(android.graphics.Movie.decodeFile(gif.file.path)); assertEquals(1000, movie.duration()); assertEquals(160, movie.width()); assertEquals(240, movie.height())
                val image = android.graphics.Bitmap.createBitmap(160, 240, android.graphics.Bitmap.Config.ARGB_8888)
                try { movie.setTime(500); movie.draw(android.graphics.Canvas(image), 0f, 0f); val color = image.getPixel(80, 120); assertTrue(Color.green(color) > 150 && Color.red(color) < 80) } finally { image.recycle() }
            } finally { gif.file.delete() }
            assertEquals(original, digest(video))
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledUnsupportedAndMismatchedInputsLeaveNoExportOrOriginalChanges() = runBlocking<Unit> {
        val root = File(context.cacheDir, "media-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val source = root.resolve("video.mp4").also { MediaTestFixtures.video(it, false) }
            val uri = Uri.fromFile(source); val info = MediaExportEngine.info(context, uri, source.length()); val original = digest(source)
            val directory = File(context.cacheDir, "media-exports"); val filesBefore = directory.list()?.toSet().orEmpty()
            assertTrue(runCatching { MediaExportEngine(context).export(source, uri, info, MediaEditOptions(MediaExportFormat.M4A, 0, 1000), "bad") }.isFailure)
            assertTrue(runCatching { MediaExportEngine(context).export(source, Uri.fromFile(root.resolve("different.mp4")), info, MediaEditOptions(MediaExportFormat.GIF, 0, 1000), "bad") }.isFailure)
            assertTrue(runCatching { MediaExportEngine(context).export(source, uri, info, MediaEditOptions(MediaExportFormat.MP4, 0, 1000, height = 2160, crop = MediaCrop(0f, 0f, 1f, .05f)), "bad") }.isFailure)
            val result = async { MediaExportEngine(context).export(source, uri, info, MediaEditOptions(MediaExportFormat.GIF, 0, 2500, frameRate = 10), "cancel") { cancel("fixture cancellation") } }
            try { result.await(); fail("Cancellation was ignored") } catch (_: CancellationException) { }
            assertEquals(filesBefore, directory.list()?.toSet().orEmpty()); assertEquals(original, digest(source))
            val afterCancel = MediaExportEngine(context).export(source, uri, info, MediaEditOptions(MediaExportFormat.GIF, 0, 300, frameRate = 5), "next")
            assertTrue(afterCancel.file.isFile); assertTrue(afterCancel.file.delete())
            val restricted = localMediaSource(context, uri).createDataSource()
            try { assertTrue(runCatching { restricted.open(DataSpec(Uri.parse("https://example.invalid/video"))) }.isFailure) } finally { restricted.close() }
            val store = EditSessionStore(root.resolve("sessions").apply { mkdir() })
            val session = store.prepareFromFile("fixture", source.name, "video/mp4", source, EditOrigin.Local(source.path, true), source.lastModified(), false)
            val workingBefore = digest(session.workingFile)
            assertTrue(runCatching { store.transformWorking(session) { _, output -> output.writeBytes(byteArrayOf(1, 2)); error("injected") } }.isFailure)
            assertEquals(workingBefore, digest(session.workingFile)); assertEquals(original, digest(source)); store.discard(session)
        } finally { root.deleteRecursively() }
    }
}
