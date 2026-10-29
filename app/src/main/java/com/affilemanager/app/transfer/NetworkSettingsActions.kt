package com.affilemanager.app.transfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build

internal enum class NetworkSettingsTarget { WIFI, HOTSPOT, WIFI_DIRECT }

/** System-owned controls only: AF never toggles a network behind the user's back. */
internal object NetworkSettingsActions {
    fun candidates(target: NetworkSettingsTarget, sdk: Int): List<String> = when (target) {
        NetworkSettingsTarget.WIFI -> if (sdk >= 29) listOf(
            "android.settings.panel.action.WIFI", "android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS",
        ) else listOf("android.settings.WIFI_SETTINGS", "android.settings.WIRELESS_SETTINGS")
        // Some vendors omit these specific pages. Fall back to their public
        // wireless settings instead of hard-coding a vendor component name.
        NetworkSettingsTarget.HOTSPOT -> listOf("android.settings.TETHER_SETTINGS", "android.settings.WIRELESS_SETTINGS")
        NetworkSettingsTarget.WIFI_DIRECT -> listOf("android.settings.WIFI_DIRECT_SETTINGS", "android.settings.WIFI_SETTINGS",
            "android.settings.WIRELESS_SETTINGS")
    }

    fun open(context: Context, target: NetworkSettingsTarget): Result<Unit> {
        var unavailable: Exception? = null
        for (action in candidates(target, Build.VERSION.SDK_INT)) {
            try {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return Result.success(Unit)
            } catch (failure: ActivityNotFoundException) {
                unavailable = failure
            } catch (failure: SecurityException) {
                unavailable = failure
            }
        }
        return Result.failure(unavailable ?: ActivityNotFoundException())
    }
}
