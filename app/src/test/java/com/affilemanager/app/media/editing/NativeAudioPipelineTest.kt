package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class NativeAudioPipelineTest {
    @Test fun boundedClipIsAnInputOptionForBothAudioAndVideoReverse() {
        val values=NativeClipInput.arguments(java.io.File("fixture.mp4"),500000,1750000)
        assertTrue(values.indexOf("-t")<values.indexOf("-i"))
        assertEquals("1.250000",values[values.indexOf("-t")+1])
    }
    @Test fun pitchTempoAndEffectsAreExplicitAndLocaleIndependent() {
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMAN)
            val options=MediaEditOptions(MediaExportFormat.MP3,0,3000,speed=.25f,pitch=2f,noiseGate=true,noiseReduction=true,echoDelayMs=100,fadeInMs=250,fadeOutMs=500)
            val filters=NativeAudioExport.filters(options,48000,true)
            assertTrue(filters.contains("asetrate=96000"))
            assertEquals(3,filters.count {it.startsWith("atempo=") && it.substringAfter('=').toDouble()==.5})
            assertTrue(filters.any {it.startsWith("afftdn=")})
            assertTrue(filters.any {it.startsWith("agate=")})
            assertTrue(filters.indexOfFirst {it.startsWith("aecho=")}<filters.indexOfFirst {it.startsWith("afade=")})
            assertTrue(filters.contains("atrim=duration=12.000000"))
        }finally{java.util.Locale.setDefault(previous)}
    }
    @Test fun pcmReversePreservesChannelsAcrossBufferBoundariesAndIsInvolutive() {
        val file=Files.createTempFile("af-pcm-test-",".pcm").toFile()
        try {
            val original=ByteArray(4*40001){(it%251).toByte()};file.writeBytes(original)
            PcmFrameReverse.file(file,4){}
            val actual=file.readBytes()
            for(frame in 0 until 40001)for(channelByte in 0..3)assertEquals(original[(40000-frame)*4+channelByte],actual[frame*4+channelByte])
            PcmFrameReverse.file(file,4){};assertArrayEquals(original,file.readBytes())
        }finally{check(file.delete())}
    }
    @Test fun coverParserRejectsTruncatedJpegWithoutOutOfBounds() {
        for(bytes in listOf(byteArrayOf(-1,-40,-1,-64),byteArrayOf(-1,-40,-1,-1,-1)))
            assertThrows(IllegalArgumentException::class.java){NativeAudioTags.jpegSize(bytes)}
    }
}
