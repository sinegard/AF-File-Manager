package com.affilemanager.app.media.editing

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the same framework-only workflow used with the downloaded release APK. */
class MediaShippingFlowAndroidTest {
    @Test fun allNineFormatsSaveThroughTheActualUiAndRemovePrivateWorkingFiles() {
        assertTrue(MediaEditingRuntimeVerifier.verify(InstrumentationRegistry.getInstrumentation()))
    }
}
