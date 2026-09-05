package com.affilemanager.app.cleanup

import android.content.Intent
import android.net.Uri
import android.provider.Settings

internal object ApplicationActions {
    private val packageNamePattern = Regex("[A-Za-z][A-Za-z0-9_]*(?:[.][A-Za-z0-9_]+)+")

    private fun packageUri(packageName: String): Uri {
        require(packageName.length <= 255 && packageNamePattern.matches(packageName)) { "Invalid application package" }
        return Uri.fromParts("package", packageName, null)
    }

    fun settingsIntent(packageName: String): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(packageName))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    @Suppress("DEPRECATION")
    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_UNINSTALL_PACKAGE, packageUri(packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
