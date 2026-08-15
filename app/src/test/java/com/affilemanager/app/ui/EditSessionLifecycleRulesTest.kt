package com.affilemanager.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditSessionLifecycleRulesTest {
    @Test
    fun downloadedEditClosesAfterSaveSoItsPrivateCopyCanBeRemoved() {
        assertTrue(EditSessionLifecycleRules.closeAfterSuccessfulSave(temporaryDownload = true, closeRequested = false))
    }

    @Test
    fun localEditCanStayOpenUnlessTheUserAlreadyRequestedClose() {
        assertFalse(EditSessionLifecycleRules.closeAfterSuccessfulSave(temporaryDownload = false, closeRequested = false))
        assertTrue(EditSessionLifecycleRules.closeAfterSuccessfulSave(temporaryDownload = false, closeRequested = true))
    }
}
