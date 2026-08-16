package com.affilemanager.app.advanced

import com.affilemanager.app.editing.EditLimits
import com.affilemanager.app.model.FileEntry
import java.io.File
import java.security.MessageDigest
import java.util.UUID

internal class PrivilegedPreviewCache(cacheDirectory: File) {
    private val root = File(cacheDirectory, "privileged-previews")

    init {
        clearStale()
    }

    fun createDestination(entry: FileEntry): File {
        require(!entry.isDirectory && entry.sizeBytes in 0..EditLimits.MAX_FILE_BYTES) { "Netinkamas failas peržiūrai" }
        require(clearStale()) { "Ankstesnės privilegijuotos peržiūros pašalinti nepavyko" }
        require(root.mkdirs() || root.isDirectory) { "Peržiūros talpyklos sukurti nepavyko" }
        val required = Math.addExact(entry.sizeBytes, EditLimits.MIN_FREE_BYTES)
        require(root.usableSpace <= 0L || root.usableSpace >= required) { "Peržiūrai nepakanka laisvos vietos" }
        val identity = "${entry.absolutePath}\u0000${entry.sizeBytes}\u0000${entry.modifiedAtMillis}\u0000${UUID.randomUUID()}"
        val directory = File(root, sha256(identity))
        require(directory.mkdir()) { "Peržiūros aplanko sukurti nepavyko" }
        val extension = entry.extension.takeIf { it.length in 1..10 && it.all(Char::isLetterOrDigit) }
        val destination = File(directory, extension?.let { "content.$it" } ?: "content.bin")
        require(destination.canonicalFile.parentFile == directory.canonicalFile) { "Peržiūros kelias išeina už talpyklos" }
        return destination
    }

    fun validateCompleted(destination: File) {
        require(destination.isFile && destination.length() <= EditLimits.MAX_FILE_BYTES) { "Peržiūros kopija neužbaigta" }
    }

    fun discard(destination: File?): Boolean {
        val directory = destination?.parentFile ?: return true
        if (!directory.exists()) return true
        val contained = runCatching { directory.canonicalFile.parentFile == root.canonicalFile }.getOrDefault(false)
        return contained && directory.deleteRecursively()
    }

    private fun clearStale(): Boolean = !root.exists() || root.deleteRecursively()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
}
