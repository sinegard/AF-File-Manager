package com.affilemanager.app.data

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Android vendors may deny directory enumeration for `/` while still allowing individual root
 * entries to be inspected. This bounded fallback exposes only entries that actually exist; it
 * never invents paths and deliberately reports itself as a partial listing to the caller.
 */
internal object RootDirectoryFallback {
    private val commonAndroidRootNames = listOf(
        "acct",
        "apex",
        "bin",
        "bt_firmware",
        "bugreports",
        "cache",
        "charger",
        "config",
        "cust",
        "d",
        "data",
        "debug_ramdisk",
        "default.prop",
        "dev",
        "dsp",
        "etc",
        "file_contexts",
        "firmware",
        "init",
        "init.environ.rc",
        "init.rc",
        "linkerconfig",
        "lost+found",
        "metadata",
        "mnt",
        "odm",
        "odm_dlkm",
        "oem",
        "persist",
        "postinstall",
        "proc",
        "product",
        "product_services",
        "property_contexts",
        "recovery",
        "res",
        "root",
        "sbin",
        "sdcard",
        "seapp_contexts",
        "service_contexts",
        "storage",
        "sys",
        "system",
        "system_dlkm",
        "system_ext",
        "tmp",
        "ueventd.rc",
        "vendor",
        "vendor_dlkm",
        "verity_key",
    )

    fun existingChildren(root: File): List<File> = commonAndroidRootNames.mapNotNull { name ->
        val child = File(root, name)
        child.takeIf {
            runCatching { Files.exists(child.toPath(), LinkOption.NOFOLLOW_LINKS) }
                .getOrDefault(false)
        }
    }
}
