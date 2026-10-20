package com.affilemanager.app.media.editing

import org.junit.Assert.*
import org.junit.Test

class MediaNativeFormatContractTest {
    @Test fun allApprovedFormatsAreRealEditorChoices() {
        assertEquals(setOf("mp4", "mkv", "mov", "gif", "m4a", "mp3", "wav", "flac", "wma"),
            MediaExportFormat.entries.map { it.extension }.toSet())
        assertEquals(setOf("mp4", "mkv", "mov", "gif"), MediaExportFormat.entries.filter { it.video }.map { it.extension }.toSet())
    }
    @Test fun recognizedNewSuffixIsReplacedNotDuplicated() {
        assertEquals("Holiday.2026.mp4", MediaEditNames.outputName("Holiday.2026.mkv", MediaExportFormat.MP4))
    }
    @Test fun reverseAudioIsNotArtificiallyLimitedToWave() {
        val info = MediaEditInfo(3000, 0, 0, true, 1000, 48000, 2)
        MediaEditOptions(MediaExportFormat.M4A, 0, 3000, reverseAudio = true).validate(info)
    }
}
