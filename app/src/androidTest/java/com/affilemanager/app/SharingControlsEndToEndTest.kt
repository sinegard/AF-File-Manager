package com.affilemanager.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingControlsEndToEndTest {
    @Test fun shippingControlsUseTheActualPickersPreferencesSystemPanelAndGroupServer() {
        assertTrue(com.affilemanager.app.transfer.SharingControlsRuntimeVerifier.verify(
            InstrumentationRegistry.getInstrumentation(), InstrumentationRegistry.getArguments().getString("afAuditCapturePrefix")))
    }
}
