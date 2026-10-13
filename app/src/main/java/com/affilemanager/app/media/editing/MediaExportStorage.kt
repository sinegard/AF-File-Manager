package com.affilemanager.app.media.editing

import android.content.Context
import kotlinx.coroutines.sync.Semaphore
import java.io.File
import java.nio.file.Files

/** Startup cleanup must not load Media3, start a player, or delay restoration of file operations. */
internal object MediaExportStorage {
    val admission = Semaphore(1)
    private var startupCleaned = false // Access is serialized by admission.
    fun ownedFile(context: Context, file: File): Boolean = file.canonicalFile.parentFile == File(context.cacheDir, "media-exports").canonicalFile &&
        file.name.startsWith("af-export-") && file.isFile
    fun cleanAbandoned(context: Context) {
        if (!admission.tryAcquire()) return
        try { cleanOnce(context) } finally { admission.release() }
    }
    fun cleanOnce(context: Context) {
        if (startupCleaned) return
        val root = File(context.cacheDir, "media-exports")
        if (root.isDirectory) Files.newDirectoryStream(root.toPath()).use { entries ->
            entries.asSequence().take(32).map { it.toFile() }.filter { ownedFile(context, it) }.forEach {
                require(it.delete()) { "Laikinos redagavimo kopijos pašalinti nepavyko" }
            }
        }
        startupCleaned = true
    }
}
