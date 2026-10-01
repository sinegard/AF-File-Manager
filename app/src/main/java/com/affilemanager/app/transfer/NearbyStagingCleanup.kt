package com.affilemanager.app.transfer

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Only private transfer copies, never an original or a symbolic link's target. */
internal object NearbyStagingCleanup {
    @Synchronized
    fun delete(stagingRoot: File, path: String): Boolean {
        val root = runCatching { stagingRoot.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        if (candidate.parentFile != root) return false
        return remove(candidate)
    }

    private fun remove(file: File): Boolean {
        // Two cancelled jobs can own the same group copy. Unlike FileTreeWalk, this
        // remains safe when another owner has already removed the directory.
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
        var complete = true
        if (!Files.isSymbolicLink(file.toPath()) && file.isDirectory) {
            val children = file.listFiles()
            if (children == null && file.exists()) return false
            children.orEmpty().forEach { if (!remove(it)) complete = false }
        }
        return (file.delete() || !Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) && complete
    }
}
