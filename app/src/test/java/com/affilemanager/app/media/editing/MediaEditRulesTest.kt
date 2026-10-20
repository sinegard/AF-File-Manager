package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO

class MediaEditRulesTest {
    @get:Rule val folder = TemporaryFolder()
    private val video = MediaEditInfo(10_000, 1920, 1080, true, 1024)
    private val audio = video.copy(width = 0, height = 0)
    @Test fun audioEffectsHaveExplicitBoundsAndEveryAudioFormatCanReverse() {
        MediaEditOptions(MediaExportFormat.WAV, 0, 10_000, fadeInMs = 5000, fadeOutMs = 5000, echoDelayMs = 500, reverseAudio = true).validate(audio)
        MediaExportFormat.entries.filterNot { it.video }.forEach {
            MediaEditOptions(it, 0, 1000, reverseAudio = true).validate(audio)
        }
        listOf(MediaEditOptions(MediaExportFormat.WAV, 0, 1000, fadeInMs = -1),
            MediaEditOptions(MediaExportFormat.WAV, 0, 1000, fadeInMs = 750, fadeOutMs = 750),
            MediaEditOptions(MediaExportFormat.WAV, 0, 1000, echoDelayMs = 501),
            MediaEditOptions(MediaExportFormat.WAV, 0, 1000, sampleRate = 12345),
            MediaEditOptions(MediaExportFormat.WAV, 0, 1000, channels = 3))
            .forEach { assertThrows(IllegalArgumentException::class.java) { it.validate(audio) } }
    }
    @Test fun trimSpeedCropAndExplicitResolutionAreBounded() {
        val options = MediaEditOptions(MediaExportFormat.MP4, 2000, 6000, speed = 2f, crop = MediaCrop(.25f, .25f, .5f, .5f), height = 720)
        options.validate(video)
        assertEquals(2000, options.durationMs)
        assertEquals(1280 to 720, options.videoSize(video))
        assertEquals(MediaCrop(.5f, .5f, .5f, .5f), options.crop.move(100f, 100f))
        assertEquals(MediaCrop(.25f, .25f, .75f, .75f), options.crop.resize(100f, 100f))
        assertEquals(.05f, options.crop.resize(-100f, -100f).width)
    }
    @Test fun invalidSettingsNeverQuietlyClampOrChangeFormat() {
        listOf(MediaEditOptions(MediaExportFormat.MP4, -1, 1000), MediaEditOptions(MediaExportFormat.MP4, 0, 11_000),
            MediaEditOptions(MediaExportFormat.MP4, 0, 99), MediaEditOptions(MediaExportFormat.MP4, 0, 1000, speed = Float.NaN),
            MediaEditOptions(MediaExportFormat.MP4, 0, 1000, pitch = 3f), MediaEditOptions(MediaExportFormat.MP4, 0, 1000, height = 123),
            MediaEditOptions(MediaExportFormat.MP4, 0, 1000, frameRate = 61), MediaEditOptions(MediaExportFormat.MP4, 0, 1000, videoBitrate = 1),
            MediaEditOptions(MediaExportFormat.MP4, 0, 1000, audioBitrate = 1)).forEach { item -> assertTrue(runCatching { item.validate(video) }.isFailure) }
        assertTrue(runCatching { MediaEditOptions(MediaExportFormat.MP4, 0, 1000).validate(audio) }.isFailure)
        assertTrue(runCatching { MediaEditOptions(MediaExportFormat.M4A, 0, 1000).validate(video.copy(hasAudio = false)) }.isFailure)
        assertTrue(runCatching { MediaCrop(Float.POSITIVE_INFINITY) }.isFailure)
    }
    @Test fun gifLimitsAreAnInputContractNotJustALateFileSizeCheck() {
        MediaEditOptions(MediaExportFormat.GIF, 0, 10_000, height = 480, frameRate = 10).validate(video)
        val long = video.copy(durationMs = 70_000)
        listOf(MediaEditOptions(MediaExportFormat.GIF, 0, 70_000), MediaEditOptions(MediaExportFormat.GIF, 0, 50_000, frameRate = 15),
            MediaEditOptions(MediaExportFormat.GIF, 0, 1000, frameRate = 30), MediaEditOptions(MediaExportFormat.GIF, 0, 1000, height = 720))
            .forEach { assertTrue(runCatching { it.validate(long) }.isFailure) }
    }
    @Test fun outputNamesHaveRealFormatsAndCannotEscapeTheDestination() {
        assertEquals("movie.m4a", MediaEditNames.outputName("movie.mp4", MediaExportFormat.M4A))
        assertEquals("movie.wav", MediaEditNames.outputName("movie", MediaExportFormat.WAV))
        listOf("../movie", "a/b", "a\\b", "", "a\u0000b").forEach { assertTrue(runCatching { MediaEditNames.outputName(it, MediaExportFormat.MP4) }.isFailure) }
        assertNull(MediaEditOptions(MediaExportFormat.MP4, 0, 5000).estimatedBytes(video))
        assertTrue(MediaEditOptions(MediaExportFormat.M4A, 0, 5000).estimatedBytes(audio)!! > 100_000)
    }
    @Test fun dotsInBaseNamesArePreservedWhenChoosingTheOutputFormat() {
        assertEquals("Holiday.2026-edited.mp4", MediaEditNames.outputName("Holiday.2026-edited", MediaExportFormat.MP4))
        assertEquals("my.clip.gif", MediaEditNames.outputName("my.clip", MediaExportFormat.GIF))
        assertEquals("my.clip.wav", MediaEditNames.outputName("my.clip.M4A", MediaExportFormat.WAV))
    }
    @Test fun gifHasDecodableFramesPaletteAndTimedSequence() {
        val bytes = ByteArrayOutputStream()
        GifSequenceWriter(bytes, 32, 20).use { writer ->
            writer.frame(IntArray(640) { 0xffff0000.toInt() }, 7) {}
            writer.frame(IntArray(640) { 0xff00ff00.toInt() }, 13) {}
        }
        ImageIO.createImageInputStream(bytes.toByteArray().inputStream()).use { input ->
            val reader = ImageIO.getImageReadersByFormatName("gif").next()
            try {
                reader.input = input
                assertEquals(2, reader.getNumImages(true))
                assertEquals(0xffff0000.toInt(), reader.read(0).getRGB(10, 10))
                assertEquals(0xff00ff00.toInt(), reader.read(1).getRGB(10, 10))
                assertEquals(32, reader.getWidth(1)); assertEquals(20, reader.getHeight(1))
                val control = (reader.getImageMetadata(1).getAsTree("javax_imageio_gif_image_1.0") as org.w3c.dom.Element).getElementsByTagName("GraphicControlExtension").item(0)
                assertEquals("13", control.attributes.getNamedItem("delayTime").nodeValue)
            } finally { reader.dispose() }
        }
        assertTrue(runCatching { GifSequenceWriter(ByteArrayOutputStream(), 481, 20) }.isFailure)
    }
    @Test fun gifCancellationAndFrameLimitAreEnforcedDuringWrites() {
        val writer = GifSequenceWriter(ByteArrayOutputStream(), 1, 1)
        assertTrue(runCatching { writer.frame(intArrayOf(0), 1) { throw IllegalStateException("cancelled") } }.isFailure)
        val bounded = GifSequenceWriter(ByteArrayOutputStream(), 1, 1)
        repeat(600) { bounded.frame(intArrayOf(0), 1) {} }
        assertTrue(runCatching { bounded.frame(intArrayOf(0), 1) {} }.isFailure)
    }
    @Test fun mp4TagsAppendWithoutMovingEncodedSamplesAndRejectOtherLayouts() {
        val payload = Mp4AudioTags.atom("mdat", byteArrayOf(1, 2, 3))
        val base = Mp4AudioTags.atom("ftyp", ByteArray(8)) + payload + Mp4AudioTags.atom("moov", ByteArray(0))
        val file = folder.newFile("own.m4a").apply { writeBytes(base) }
        Mp4AudioTags.write(file, MediaTags(title = "Ačiū", artist = "AF", lyrics = "one\ntwo"), byteArrayOf(0xff.toByte(), 0xd8.toByte()))
        val out = file.readBytes()
        assertArrayEquals(base.copyOfRange(0, base.size - 8), out.copyOfRange(0, base.size - 8))
        assertTrue(out.toString(Charsets.ISO_8859_1).contains("covr"))
        assertTrue(out.toString(Charsets.UTF_8).contains("Ačiū"))
        assertEquals(out.size - base.size + 8, ByteBuffer.wrap(out, base.size - 8, 4).int)
        val bad = folder.newFile("wrong-layout.m4a").apply { writeBytes(Mp4AudioTags.atom("moov", ByteArray(0)) + payload) }
        val before = bad.readBytes()
        assertTrue(runCatching { Mp4AudioTags.write(bad, MediaTags(title = "title"), null) }.isFailure)
        assertArrayEquals(before, bad.readBytes())
    }
    @Test fun wavTagsKeepPcmUnchangedAndUseRealRiffId3Frames() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()
        output.write("RIFF".toByteArray()); output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
        output.write("WAVEdata".toByteArray()); output.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(4).array()); output.write(pcm)
        val before = output.toByteArray()
        val file = folder.newFile("own.wav").apply { writeBytes(before) }
        WavAudioTags.write(file, MediaTags(title = "音楽", lyrics = "line1\nline2", composer = "AF"), byteArrayOf(1, 2))
        val bytes = file.readBytes()
        assertArrayEquals(before.copyOfRange(8, before.size), bytes.copyOfRange(8, before.size))
        assertEquals(bytes.size - 8, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("id3 "))
        assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("APIC")); assertTrue(bytes.toString(Charsets.UTF_8).contains("音楽"))
        assertTrue(runCatching { MediaTags(title = "x".repeat(4097)).validate() }.isFailure)
        assertTrue(runCatching { MediaTags(lyrics = "x".repeat(32769)).validate() }.isFailure)
        assertTrue(runCatching { MediaTags(comment = "\u0000").validate() }.isFailure)
    }
}
