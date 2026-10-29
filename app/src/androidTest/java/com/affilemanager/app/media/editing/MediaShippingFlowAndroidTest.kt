package com.affilemanager.app.media.editing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the same framework-only workflow used with the downloaded release APK. */
class MediaShippingFlowAndroidTest {
    @Test fun videoFormatsSaveThroughTheActualUiAndRemovePrivateWorkingFiles() {
        assertTrue(MediaEditingRuntimeVerifier.verifyFormats(InstrumentationRegistry.getInstrumentation(), "MP4", "MKV", "MOV"))
    }
    @Test fun commonAudioFormatsSaveThroughTheActualUiAndRemovePrivateWorkingFiles() {
        assertTrue(MediaEditingRuntimeVerifier.verifyFormats(InstrumentationRegistry.getInstrumentation(), "M4A", "MP3", "WAV"))
    }
    @Test fun losslessLegacyAndAnimatedFormatsSaveThroughTheActualUiAndRemovePrivateWorkingFiles() {
        assertTrue(MediaEditingRuntimeVerifier.verifyFormats(InstrumentationRegistry.getInstrumentation(), "FLAC", "WMA", "GIF"))
    }
}
