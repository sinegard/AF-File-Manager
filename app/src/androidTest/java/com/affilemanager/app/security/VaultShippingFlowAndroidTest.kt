package com.affilemanager.app.security

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultShippingFlowAndroidTest {
    @Test fun actualAfDialogsCreateKeepBothReadLegacyAndReportWrongPassphrases() {
        assertTrue(VaultRuntimeVerifier.verify(InstrumentationRegistry.getInstrumentation()))
    }
}
