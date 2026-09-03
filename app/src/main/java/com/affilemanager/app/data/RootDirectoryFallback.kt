package com.affilemanager.app.data

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Android vendors may deny directory enumeration for `/` while still allowing individual root
 * entries to be inspected. This bounded fallback combines known Android names with top-level
 * mount points visible through procfs. A known entry is also shown when Android confirms that its
 * metadata is access-controlled: the browser marks it locked instead of silently omitting it.
 * The fallback deliberately reports itself as a partial listing to the caller.
 */
internal object RootDirectoryFallback {
    private const val MAX_MOUNT_LINES = 20_000
    private const val MAX_MOUNT_TEXT_CHARS = 2 * 1024 * 1024
    private val defaultMountSources = listOf(File("/proc/self/mountinfo"), File("/proc/mounts"))

    private val commonAndroidRootNames = listOf(
        "adb_keys",
        "acct",
        "apex",
        "bin",
        "bootstrap-apex",
        "bt_firmware",
        "bugreports",
        "cache",
        "charger",
        "config",
        "cust",
        "d",
        "data",
        "data_mirror",
        "debug_ramdisk",
        "default.prop",
        "dev",
        "dsp",
        "etc",
        "file_contexts",
        "firmware",
        "first_stage_ramdisk",
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
        "second_stage_resources",
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

    fun existingChildren(root: File, mountSources: List<File> = defaultMountSources): List<File> {
        val candidateNames = LinkedHashSet(commonAndroidRootNames)
        mountSources.forEach { source ->
            runCatching {
                source.bufferedReader().use { reader ->
                    var lines = 0
                    var characters = 0
                    while (lines < MAX_MOUNT_LINES && characters <= MAX_MOUNT_TEXT_CHARS) {
                        val line = reader.readLine() ?: break
                        lines += 1
                        characters = Math.addExact(characters, line.length)
                        mountPointFrom(line)?.let { mountPoint ->
                            topLevelName(mountPoint)?.let(candidateNames::add)
                        }
                    }
                }
            }
        }

        return candidateNames.mapNotNull { name ->
            val child = File(root, name)
            child.takeIf(::existsOrIsAccessControlled)
        }
    }

    private fun existsOrIsAccessControlled(file: File): Boolean = try {
        Files.readAttributes(
            file.toPath(),
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        true
    } catch (_: AccessDeniedException) {
        true
    } catch (_: SecurityException) {
        true
    } catch (_: Exception) {
        false
    }

    private fun mountPointFrom(line: String): String? {
        val fields = line.split(' ')
        return if (" - " in line) fields.getOrNull(4) else fields.getOrNull(1)
    }

    private fun topLevelName(mountPoint: String): String? {
        if (!mountPoint.startsWith('/')) return null
        val name = mountPoint.removePrefix("/").substringBefore('/')
        return name.takeIf {
            it.isNotEmpty() && it.length <= 255 && it != "." && it != ".." &&
                it.none { character -> character == '/' || character == '\\' || character.isISOControl() }
        }
    }
}
