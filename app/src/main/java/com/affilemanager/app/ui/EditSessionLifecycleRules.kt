package com.affilemanager.app.ui

internal object EditSessionLifecycleRules {
    fun closeAfterSuccessfulSave(temporaryDownload: Boolean, closeRequested: Boolean): Boolean =
        temporaryDownload || closeRequested
}
