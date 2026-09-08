package com.affilemanager.app.apk

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile

internal data class ApkInstallMetadata(
    val label: String,
    val packageName: String,
    val candidateVersion: String,
    val installedVersion: String?,
    val fileBytes: Long,
    val minimumSdk: Int?,
    val targetSdk: Int?,
    val abis: List<String>,
    val icon: Bitmap?,
)

internal object ApkAbiScanner {
    private const val MAX_ENTRIES = 20_000
    private val supported = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64", "riscv64")

    fun scan(apk: File): List<String> = ZipFile(apk).use { zip ->
        val result = linkedSetOf<String>()
        val entries = zip.entries()
        var count = 0
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            require(++count <= MAX_ENTRIES) { "APK turi per daug įrašų" }
            if (entry.isDirectory || entry.name.length > 1_024) continue
            val parts = entry.name.split('/', limit = 3)
            if (parts.size == 3 && parts[0] == "lib" && parts[1] in supported && parts[2].endsWith(".so")) {
                result += parts[1]
            }
        }
        result.sorted()
    }
}

internal object ApkInstallMetadataReader {
    fun standalone(context: Context, apk: File, displayedBytes: Long = apk.length()): ApkInstallMetadata {
        require(apk.isFile && apk.canRead()) { "APK failas nepasiekiamas" }
        val manager = context.packageManager
        @Suppress("DEPRECATION")
        val info = manager.getPackageArchiveInfo(apk.absolutePath, 0)
            ?: throw IllegalArgumentException("APK informacija nepasiekiama")
        val application = requireNotNull(info.applicationInfo) { "APK informacija nepasiekiama" }.apply {
            sourceDir = apk.absolutePath
            publicSourceDir = apk.absolutePath
        }
        @Suppress("DEPRECATION")
        val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val installed = runCatching {
            @Suppress("DEPRECATION")
            val installedInfo = if (android.os.Build.VERSION.SDK_INT >= 33) {
                manager.getPackageInfo(info.packageName, PackageManager.PackageInfoFlags.of(0))
            } else manager.getPackageInfo(info.packageName, 0)
            @Suppress("DEPRECATION")
            val installedCode = if (android.os.Build.VERSION.SDK_INT >= 28) installedInfo.longVersionCode
                else installedInfo.versionCode.toLong()
            "${installedInfo.versionName ?: "?"} ($installedCode)"
        }.getOrNull()
        return ApkInstallMetadata(
            label = runCatching { application.loadLabel(manager).toString() }.getOrDefault(info.packageName),
            packageName = info.packageName,
            candidateVersion = "${info.versionName ?: "?"} ($code)",
            installedVersion = installed,
            fileBytes = displayedBytes,
            minimumSdk = application.minSdkVersion.takeIf { it > 0 },
            targetSdk = application.targetSdkVersion.takeIf { it > 0 },
            abis = ApkAbiScanner.scan(apk),
            icon = runCatching { application.loadIcon(manager).toBitmap(144, 144) }.getOrNull(),
        )
    }

    fun bundle(context: Context, archive: File, plan: SplitApkPlan, selectedNames: Set<String>): ApkInstallMetadata {
        val chosen = plan.parts.filter { it.name in selectedNames || it == plan.base }
        require(chosen.isNotEmpty() && plan.base in chosen) { "Turi būti pasirinktas pagrindinis APK" }
        val stagingRoot = File(context.cacheDir, "apk-metadata")
        require(stagingRoot.exists() || stagingRoot.mkdirs()) { "APK metaduomenų talpyklos sukurti nepavyko" }
        val stage = File(stagingRoot, UUID.randomUUID().toString()).canonicalFile
        require(stage.parentFile == stagingRoot.canonicalFile && stage.mkdir()) { "APK metaduomenų talpyklos sukurti nepavyko" }
        try {
            val base = File(stage, "base.apk")
            SplitApkArchive.copyPart(archive, plan.base, base)
            val metadata = standalone(context, base, chosen.sumOf(SplitApkPart::bytes))
            val abis = linkedSetOf<String>()
            chosen.forEachIndexed { index, part ->
                val file = if (part == plan.base) base else File(stage, "part-$index.apk").also {
                    SplitApkArchive.copyPart(archive, part, it)
                }
                try { abis += ApkAbiScanner.scan(file) } finally { if (file != base) file.delete() }
            }
            return metadata.copy(abis = abis.sorted())
        } finally {
            stage.deleteRecursively()
            if (stagingRoot.listFiles().isNullOrEmpty()) stagingRoot.delete()
        }
    }
}
