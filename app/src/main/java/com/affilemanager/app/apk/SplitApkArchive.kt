package com.affilemanager.app.apk

import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import java.util.zip.ZipFile

internal data class SplitApkPart(val name: String, val bytes: Long)
internal data class SplitApkPlan(val parts: List<SplitApkPart>, val base: SplitApkPart, val hasExtraData: Boolean)

/** Reads original APKs without merging resources, altering signatures, or trusting archive paths. */
internal object SplitApkArchive {
    const val MAX_PARTS = 200
    const val MAX_ENTRIES = 4096
    const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
    const val MAX_PART_BYTES = 512L * 1024 * 1024
    fun isBundle(file: File) = file.extension.lowercase(Locale.ROOT) in setOf("apks", "apkm", "xapk")

    fun plan(file: File): SplitApkPlan {
        checkDirectoryBounds(file)
        ZipFile(file).use { zip ->
            val names = mutableSetOf<String>()
            val parts = mutableListOf<SplitApkPart>()
            var entries = 0
            var extra = false
            var total = 0L
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                require(++entries <= MAX_ENTRIES && entry.name.length <= 1024 && names.add(entry.name)) { "Netinkamas APK rinkinys" }
                require(!entry.name.startsWith('/') && '\\' !in entry.name && entry.name.split('/').none { it == ".." }) { "Netinkamas APK rinkinys" }
                if (entry.isDirectory) continue
                if (!entry.name.endsWith(".apk", true)) {
                    if (entry.name.endsWith(".obb", true)) extra = true
                    continue
                }
                require(entry.size in 1..MAX_PART_BYTES) { "APK rinkinys per didelis" }
                total += entry.size
                require(total <= MAX_EXPANDED_BYTES && parts.size < MAX_PARTS) { "APK rinkinys per didelis" }
                parts += SplitApkPart(entry.name, entry.size)
            }
            // A universal APK already is standalone; never manufacture one from signed splits.
            val universal = parts.filter { it.name.substringAfterLast('/').equals("universal.apk", true) }
            if (universal.size == 1) return SplitApkPlan(universal, universal.single(), extra)
            val bases = parts.filter { it.name.substringAfterLast('/').lowercase(Locale.ROOT) in setOf("base.apk", "base-master.apk") }
            require(bases.size == 1 && parts.none { it.name.startsWith("standalones/") || it.name.startsWith("instant/") }) {
                "Šiame rinkinyje yra keli diegimo variantai arba nėra pagrindinio APK. Išpakuokite originalias dalis."
            }
            return SplitApkPlan(parts.sortedBy { it.name }, bases.single(), extra)
        }
    }

    fun copyPart(archive: File, part: SplitApkPart, destination: File, limit: Long = MAX_PART_BYTES, checkActive: () -> Unit = {}) {
        require(part.bytes <= limit) { "APK rinkinys per didelis" }
        try {
            ZipFile(archive).use { zip ->
                val entry = requireNotNull(zip.getEntry(part.name)) { "Netinkamas APK rinkinys" }
                require(entry.size == part.bytes) { "Failas pasikeitė" }
                zip.getInputStream(entry).use { input -> destination.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var written = 0L
                    while (true) {
                        checkActive()
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val count = input.read(buffer)
                        if (count < 0) break
                        written += count
                        require(written <= part.bytes && written <= limit) { "APK rinkinys per didelis" }
                        output.write(buffer, 0, count)
                    }
                    require(written == part.bytes) { "Netinkamas APK rinkinys" }
                } }
            }
        } catch (error: Throwable) { destination.delete(); throw error }
    }

    private fun checkDirectoryBounds(file: File) {
        RandomAccessFile(file, "r").use { input ->
            require(input.length() in 22..MAX_EXPANDED_BYTES) { "APK rinkinys per didelis" }
            val tail = ByteArray(minOf(input.length(), 65_557).toInt())
            input.seek(input.length() - tail.size)
            input.readFully(tail)
            fun u16(offset: Int) = (tail[offset].toInt() and 255) or ((tail[offset + 1].toInt() and 255) shl 8)
            fun u32(offset: Int) = (0..3).fold(0L) { value, byte -> value or ((tail[offset + byte].toLong() and 255) shl (byte * 8)) }
            val end = (tail.size - 22 downTo 0).firstOrNull { index ->
                u32(index) == 0x06054b50L && index + 22 + u16(index + 20) == tail.size
            } ?: throw IllegalArgumentException("Netinkamas APK rinkinys")
            require(u16(end + 4) == 0 && u16(end + 6) == 0 && u16(end + 10) in 1..MAX_ENTRIES &&
                u32(end + 12) <= 4 * 1024 * 1024) { "APK rinkinys per didelis" }
        }
    }
}
