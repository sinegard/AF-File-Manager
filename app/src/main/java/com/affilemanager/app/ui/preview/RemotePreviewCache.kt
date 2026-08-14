package com.affilemanager.app.ui.preview

import com.affilemanager.app.network.RemoteEntry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/**
 * Bounded, app-private staging for remote files that are opened for preview.
 * Files copied explicitly by the user use the normal transfer destination instead.
 */
internal class RemotePreviewCache(
    cacheDirectory: File,
    private val tokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    companion object {
        const val MAX_FILE_BYTES: Long = 256L * 1_024L * 1_024L
        const val MAX_CACHE_BYTES: Long = 512L * 1_024L * 1_024L
        const val MAX_CACHE_ENTRIES: Int = 8
        private const val MIN_FREE_SPACE_BYTES: Long = 32L * 1_024L * 1_024L
    }

    private val root = File(cacheDirectory, "remote-previews")

    fun createDestination(profileId: String, entry: RemoteEntry): File {
        require(profileId.isNotBlank()) { "Remote preview profile is missing" }
        require(!entry.directory) { "Folders cannot be opened as files" }
        require(entry.sizeBytes in 0..MAX_FILE_BYTES) { "Remote preview file exceeds the size limit" }
        ensureRoot()
        prune()

        val usable = root.usableSpace
        if (usable > 0) {
            require(entry.sizeBytes <= (usable - MIN_FREE_SPACE_BYTES).coerceAtLeast(0)) {
                "Not enough free space for remote preview"
            }
        }

        val identity = buildString {
            append(profileId)
            append('\u0000')
            append(entry.path)
            append('\u0000')
            append(entry.sizeBytes)
            append('\u0000')
            append(entry.modifiedAtMillis ?: -1L)
            append('\u0000')
            append(tokenFactory())
        }
        val directory = File(root, sha256(identity))
        require(directory.mkdirs()) { "Could not create the remote preview directory" }
        val extension = entry.name.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        val destination = File(directory, if (extension == null) "content.bin" else "content.$extension")
        require(destination.canonicalFile.parentFile == directory.canonicalFile) {
            "Remote preview path escaped its cache directory"
        }
        return destination
    }

    fun validateCompleted(destination: File) {
        require(destination.isFile) { "Remote preview was not downloaded" }
        val actualBytes = destination.length()
        require(actualBytes in 0..MAX_FILE_BYTES) { "Remote preview file exceeds the size limit" }
        destination.parentFile?.setLastModified(System.currentTimeMillis())
        prune(keepDirectory = destination.parentFile)
    }

    fun discard(destination: File?) {
        val directory = destination?.parentFile ?: return
        if (isDirectChild(directory)) directory.deleteRecursively()
    }

    fun prune(keepDirectory: File? = null) {
        if (!root.isDirectory) return
        val keep = keepDirectory?.canonicalFile
        val entries = root.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .sortedByDescending(File::lastModified)
        val retained = entries.firstOrNull { directory ->
            keep != null && runCatching { directory.canonicalFile }.getOrNull() == keep
        }
        var retainedCount = if (retained == null) 0 else 1
        var retainedBytes = retained?.let(::directorySize) ?: 0L
        entries.filterNot { it == retained }.forEach { directory ->
            val canonical = runCatching { directory.canonicalFile }.getOrNull() ?: return@forEach
            val bytes = directorySize(directory)
            val shouldRetain = canonical == keep || (
                retainedCount < MAX_CACHE_ENTRIES &&
                    bytes <= MAX_CACHE_BYTES - retainedBytes
                )
            if (shouldRetain) {
                retainedCount += 1
                retainedBytes += bytes
            } else if (isDirectChild(directory)) {
                directory.deleteRecursively()
            }
        }
    }

    private fun ensureRoot() {
        if (!root.exists()) require(root.mkdirs()) { "Could not create the remote preview cache" }
        require(root.isDirectory) { "Remote preview cache is not a directory" }
    }

    private fun isDirectChild(directory: File): Boolean = runCatching {
        directory.canonicalFile.parentFile == root.canonicalFile
    }.getOrDefault(false)

    private fun directorySize(directory: File): Long = directory.listFiles()
        .orEmpty()
        .asSequence()
        .filter(File::isFile)
        .fold(0L) { total, file ->
            val length = file.length().coerceAtLeast(0)
            if (Long.MAX_VALUE - total < length) Long.MAX_VALUE else total + length
        }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
