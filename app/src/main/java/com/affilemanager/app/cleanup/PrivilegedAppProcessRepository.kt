package com.affilemanager.app.cleanup

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.affilemanager.app.advanced.AdvancedAccessBackend
import com.affilemanager.app.advanced.AdvancedAccessManager
import com.affilemanager.app.advanced.PrivilegedAppProcessTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class PrivilegedRunningAppsResult(
    val apps: List<DeviceCleanupApp>,
    val truncated: Boolean,
)

class PrivilegedAppProcessRepository(
    private val application: Application,
    private val advancedAccess: AdvancedAccessManager,
) {
    companion object {
        private const val MAX_APPS = 500
    }

    suspend fun listRunningApps(): PrivilegedRunningAppsResult = withContext(Dispatchers.IO) {
        requireConnected()
        val rows = privilegedCall { it.listRunningAppMemory().orEmpty().toList() }
        val packageManager = application.packageManager
        val installed = installedApplications(packageManager)
            .asSequence()
            .filter(::isUserApplication)
            .filterNot { it.packageName == application.packageName }
            .associateBy(ApplicationInfo::packageName)
        val parsed = rows.mapNotNull { row ->
            val columns = row.split('\t', limit = 2)
            if (columns.size != 2) return@mapNotNull null
            val packageName = runCatching {
                PrivilegedAppProcessTools.requireValidPackageName(columns[0])
            }.getOrNull() ?: return@mapNotNull null
            val ramBytes = columns[1].toLongOrNull()?.takeIf { it >= 0L } ?: return@mapNotNull null
            val info = installed[packageName] ?: return@mapNotNull null
            DeviceCleanupApp(
                packageName = packageName,
                label = info.loadLabel(packageManager).toString().ifBlank { packageName },
                lastUsedMillis = null,
                firstInstalledMillis = 0L,
                cacheBytes = null,
                ramBytes = ramBytes,
            )
        }.sortedByDescending(DeviceCleanupApp::ramBytes)
        PrivilegedRunningAppsResult(
            apps = parsed.take(MAX_APPS),
            truncated = parsed.size > MAX_APPS,
        )
    }

    suspend fun forceStop(packageName: String) = withContext(Dispatchers.IO) {
        PrivilegedAppProcessTools.requireValidPackageName(packageName)
        require(packageName != application.packageName) { "AF File Manager negali sustabdyti savęs" }
        val info = runCatching { application.packageManager.getApplicationInfoCompat(packageName) }
            .getOrNull()
            ?.takeIf(::isUserApplication)
            ?: throw IllegalArgumentException("Galima sustabdyti tik įdiegtą naudotojo programą")
        require(info.packageName == packageName)
        requireConnected()
        check(privilegedCall { it.forceStopPackage(packageName) }) { "Programos sustabdyti nepavyko" }
    }

    private fun requireConnected() {
        check(advancedAccess.state.value.activeBackend in setOf(
            AdvancedAccessBackend.SHIZUKU_SHELL,
            AdvancedAccessBackend.SHIZUKU_ROOT,
            AdvancedAccessBackend.ROOT,
        )) { "Root arba Shizuku prieiga neaktyvi" }
    }

    private inline fun <T> privilegedCall(block: (com.affilemanager.app.advanced.IPrivilegedFileService) -> T): T =
        try {
            block(advancedAccess.privilegedTerminalServiceOrThrow())
        } catch (error: Throwable) {
            advancedAccess.reportOperationFailure(error)
            throw error
        }

    private fun isUserApplication(info: ApplicationInfo): Boolean =
        info.flags and ApplicationInfo.FLAG_SYSTEM == 0

    @Suppress("DEPRECATION")
    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            packageManager.getInstalledApplications(0)
        }

    @Suppress("DEPRECATION")
    private fun PackageManager.getApplicationInfoCompat(packageName: String): ApplicationInfo =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
        } else {
            getApplicationInfo(packageName, 0)
        }
}
