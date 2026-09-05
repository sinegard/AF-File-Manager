package com.affilemanager.app.cleanup

import android.app.AppOpsManager
import android.app.Application
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class DeviceCleanupApp(
    val packageName: String,
    val label: String,
    val lastUsedMillis: Long?,
    val firstInstalledMillis: Long,
    val cacheBytes: Long?,
)

data class DeviceCleanupSnapshot(
    val usageAccessGranted: Boolean,
    val unusedApps: List<DeviceCleanupApp>,
    val cachedApps: List<DeviceCleanupApp>,
    val scannedApps: Int,
    val cacheSizesAvailable: Boolean,
    val appsTruncated: Boolean = false,
    val usageHistoryAvailable: Boolean = true,
)

internal object DeviceCleanupRules {
    const val UNUSED_AFTER_DAYS = 90
    const val DAY_MILLIS = 24L * 60L * 60L * 1_000L

    fun isUnused(nowMillis: Long, lastUsedMillis: Long?, firstInstalledMillis: Long): Boolean {
        val lastRelevantUse = maxOf(lastUsedMillis ?: 0L, firstInstalledMillis)
        return lastRelevantUse > 0L && nowMillis - lastRelevantUse >= UNUSED_AFTER_DAYS * DAY_MILLIS
    }
}

class DeviceCleanupRepository(private val application: Application) {
    companion object {
        private const val MAX_APPS = 500
        private const val USAGE_WINDOW_DAYS = 365L
    }

    suspend fun scan(nowMillis: Long = System.currentTimeMillis()): DeviceCleanupSnapshot = withContext(Dispatchers.IO) {
        val granted = hasUsageAccess()
        if (!granted) return@withContext DeviceCleanupSnapshot(false, emptyList(), emptyList(), 0, false)
        val usageManager = application.getSystemService(UsageStatsManager::class.java)
        val usageResult = runCatching {
            usageManager.queryAndAggregateUsageStats(
                nowMillis - USAGE_WINDOW_DAYS * DeviceCleanupRules.DAY_MILLIS,
                nowMillis,
            )
        }
        val usage = usageResult.getOrDefault(emptyMap())
        val packageManager = application.packageManager
        val applications = installedApplications(packageManager).asSequence()
            .filterNot { it.packageName == application.packageName }
            .filterNot { it.flags and ApplicationInfo.FLAG_SYSTEM != 0 }
            .take(MAX_APPS + 1)
            .toList()
        val storage = application.getSystemService(StorageStatsManager::class.java)
        var cacheQueriesSucceeded = 0
        val items = applications.take(MAX_APPS).mapNotNull { info ->
            currentCoroutineContext().ensureActive()
            val packageInfo = runCatching { packageInfo(packageManager, info.packageName) }.getOrNull() ?: return@mapNotNull null
            val cacheBytes = runCatching {
                storage.queryStatsForPackage(info.storageUuid, info.packageName, Process.myUserHandle()).cacheBytes.coerceAtLeast(0L)
            }.onSuccess { cacheQueriesSucceeded += 1 }.getOrNull()
            DeviceCleanupApp(
                packageName = info.packageName,
                label = info.loadLabel(packageManager).toString().ifBlank { info.packageName },
                lastUsedMillis = usage[info.packageName]?.lastTimeUsed?.takeIf { it > 0L },
                firstInstalledMillis = packageInfo.firstInstallTime.coerceAtLeast(0L),
                cacheBytes = cacheBytes,
            )
        }
        DeviceCleanupSnapshot(
            usageAccessGranted = true,
            unusedApps = items.filter { usageResult.isSuccess && DeviceCleanupRules.isUnused(nowMillis, it.lastUsedMillis, it.firstInstalledMillis) }
                .sortedBy { it.lastUsedMillis ?: it.firstInstalledMillis },
            cachedApps = items.filter { it.cacheBytes == null || it.cacheBytes > 0L }.sortedByDescending { it.cacheBytes },
            scannedApps = items.size,
            cacheSizesAvailable = cacheQueriesSucceeded > 0,
            appsTruncated = applications.size > MAX_APPS,
            usageHistoryAvailable = usageResult.isSuccess,
        )
    }

    fun hasUsageAccess(): Boolean {
        val operations = application.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            operations.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), application.packageName)
        } else {
            @Suppress("DEPRECATION")
            operations.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), application.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    @Suppress("DEPRECATION")
    private fun installedApplications(packageManager: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else packageManager.getInstalledApplications(0)

    @Suppress("DEPRECATION")
    private fun packageInfo(packageManager: PackageManager, packageName: String) =
        if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        else packageManager.getPackageInfo(packageName, 0)
}
