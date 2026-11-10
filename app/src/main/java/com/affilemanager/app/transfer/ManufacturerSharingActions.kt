package com.affilemanager.app.transfer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

internal enum class ManufacturerSharingTarget(
    val id: String,
    val label: String,
    val packages: List<String>,
    val settingsSearch: String = label,
) {
    SAMSUNG("samsung", "Samsung Quick Share", listOf("com.samsung.android.app.sharelive", "com.samsung.android.sharing"), "Quick Share"),
    HONOR("honor", "HONOR Share", listOf("com.hihonor.android.instantshare", "com.hihonar.android.instantshare")),
    HUAWEI("huawei", "Huawei Share", listOf("com.huawei.android.instantshare")),
    XIAOMI("xiaomi", "Xiaomi Mi Share", listOf("com.miui.mishare.connectivity"), "Mi Share"),
    // Opening Google Play services itself is not opening Quick Share.
    QUICK_SHARE("quick_share", "Quick Share", emptyList()),
}

/** User-initiated launcher only. No transfers, extra permissions, or file URI grants. */
internal object ManufacturerSharingActions {
    fun open(context: Context, target: ManufacturerSharingTarget): Boolean =
        target.packages.any { openInstalledPackage(context, it) }

    fun openInstalledPackage(context: Context, packageName: String): Boolean {
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (launch.component?.packageName != packageName) return false
        return openActivity(context, launch)
    }

    fun openSettings(context: Context): Boolean = openActivity(context, Intent(Settings.ACTION_SETTINGS))

    private fun openActivity(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}
