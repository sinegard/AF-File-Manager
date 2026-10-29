package com.affilemanager.app

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentUiEndToEndTest {
    @Test fun normalPasteCopiesTheFullNameBothDirectionsThroughTheActualAfUi() {
        assertTrue(DocumentWorkflowRuntimeVerifier.verify(InstrumentationRegistry.getInstrumentation()))
    }
}
