package com.affilemanager.app.ui.theme

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogInsetsPolicyTest {
    @Test fun legacyDialogsAskForResizeAndModernFittingIsPreserved() {
        for (sdk in 26..29) assertFalse(dialogDecorFitsSystemWindows(true, sdk))
        for (sdk in 30..36) assertTrue(dialogDecorFitsSystemWindows(true, sdk))
    }

    @Test fun explicitEdgeToEdgeRequestsStayEdgeToEdgeOnEverySupportedSdk() {
        for (sdk in 26..36) assertFalse(dialogDecorFitsSystemWindows(false, sdk))
    }
}
