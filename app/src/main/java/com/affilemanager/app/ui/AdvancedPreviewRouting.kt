package com.affilemanager.app.ui

import com.affilemanager.app.model.FileEntry
import java.io.File

/** Uses an ordinary app-readable path before asking a privileged backend to stage a copy. */
internal object AdvancedPreviewRouting {
    fun directlyReadableFile(entry: FileEntry): File? {
        if (entry.isDirectory) return null
        return runCatching { File(entry.absolutePath).canonicalFile }
            .getOrNull()
            ?.takeIf { it.isFile && it.canRead() }
    }
}
