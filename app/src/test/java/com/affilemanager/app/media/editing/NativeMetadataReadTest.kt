package com.affilemanager.app.media.editing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeMetadataReadTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun asfRoutingUsesItsBoundedHeaderInsteadOfThePrivateWorkingFileSuffix() {
        val source=temporary.newFile("original.wav")
        source.writeBytes(byteArrayOf(0x30,0x26,0xb2.toByte(),0x75,0x8e.toByte(),0x66,0xcf.toByte(),0x11,0xa6.toByte(),0xd9.toByte(),0,0xaa.toByte(),0,0x62,0xce.toByte(),0x6c)+ByteArray(64))
        assertTrue(NativeMediaProbe.isAsf(source))
        assertTrue(NativeMediaExport.needed(source,MediaEditOptions(MediaExportFormat.M4A,0,1000)))
    }
    @Test fun shortOrOrdinaryInputIsNotMistakenForAsf() {
        val source=temporary.newFile("original.wav")
        source.writeBytes(byteArrayOf(0x30,0x26,0xb2.toByte()))
        assertFalse(NativeMediaProbe.isAsf(source))
        source.writeBytes("RIFF ordinary wave data, not ASF".toByteArray())
        assertFalse(NativeMediaProbe.isAsf(source))
    }
    @Test fun originalLyricsAreNotDiscardedBecauseTheirId3LanguageIsNotEnglish() {
        val info=NativeMediaInfo(1000,"mp3",emptyList(),mapOf("title" to "Original","lyrics-fra" to "Première ligne\nDeuxième ligne"))
        assertEquals("Première ligne\nDeuxième ligne",info.editableTags().lyrics)
        assertEquals("Original",info.editableTags().title)
    }
}
