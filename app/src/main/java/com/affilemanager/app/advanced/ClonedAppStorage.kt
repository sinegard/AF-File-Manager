package com.affilemanager.app.advanced

import java.io.File
import java.util.Locale

/** OEM clone storage is either a normal folder or another Android user's isolated storage. */
internal object ClonedAppStorage {
    const val SHORTCUT_ID = "builtin.cloned_apps"
    const val PRIVILEGED_PROFILE_ROOT = "/storage/emulated"

    private val normalRelativePaths = listOf(
        "Twin App data", // Honor / Huawei when exposed to the owner profile.
        "DualApp", // Samsung compatibility folder inside the owner profile.
        "Internal storage (Dual Messenger)", // Some firmware exposes the display label as a directory.
    )
    private val supportedManufacturers = setOf("honor", "huawei", "samsung")

    fun normalLocation(primaryRoot: File): File? = normalRelativePaths.asSequence()
        .map { relative -> File(primaryRoot, relative) }
        .firstOrNull(File::isDirectory)

    fun shouldOffer(manufacturer: String, primaryRoot: File): Boolean =
        manufacturer.trim().lowercase(Locale.ROOT) in supportedManufacturers || normalLocation(primaryRoot) != null
}
