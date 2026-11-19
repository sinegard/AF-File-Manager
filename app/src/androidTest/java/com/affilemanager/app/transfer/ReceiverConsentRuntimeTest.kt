package com.affilemanager.app.transfer

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverConsentRuntimeTest {
    @Test fun realOwnerUiRejectsUnapprovedWritesAndRevokesGrantsOnRestart() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val prefix = InstrumentationRegistry.getArguments().getString("afAuditCapturePrefix") ?: "consent-${System.nanoTime()}"
        assertTrue(ReceiverConsentRuntimeVerifier.verify(instrument, prefix))
    }
}
