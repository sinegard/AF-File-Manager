package com.affilemanager.app.update

data class AppRelease(
    val tag: String,
    val version: String,
    val notes: String,
    val pageUrl: String,
    val asset: AppReleaseAsset,
)

data class AppReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val currentVersion: String) : AppUpdateState
    data class Available(val release: AppRelease) : AppUpdateState
    data class Downloading(val release: AppRelease, val downloadedBytes: Long) : AppUpdateState
    data class Ready(val release: AppRelease, val installPermissionRequired: Boolean = false) : AppUpdateState
    data class Failed(val message: String, val release: AppRelease? = null) : AppUpdateState
}
