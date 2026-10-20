package com.affilemanager.app.media.editing

import android.content.Context
import com.affilemanager.app.editing.EditLimits
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Flat, exclusively owned job directory. Never traverses or removes a source/destination. */
internal class MediaWorkspace private constructor(val directory: File) : Closeable {
    companion object {
        const val MAX_BYTES = 768L * 1024 * 1024
        const val MAX_FILES = 32
        private val jobName = Regex("af-mediajob-[a-f0-9-]{36}")
        private val active = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        fun create(context: Context, reserveBytes: Long = MAX_BYTES): MediaWorkspace {
            require(reserveBytes in 1..MAX_BYTES)
            val root = File(context.cacheDir, "media-work")
            check(root.isDirectory || root.mkdirs())
            check(root.canonicalFile.parentFile == context.cacheDir.canonicalFile && !Files.isSymbolicLink(root.toPath()))
            require(root.usableSpace >= reserveBytes + EditLimits.MIN_FREE_BYTES) { "Nepakanka laisvos vietos" }
            Files.newDirectoryStream(root.toPath()).use { require(it.asSequence().take(33).count() < 32) { "Laikinų failų riba pasiekta" } }
            val directory = File(root, "af-mediajob-${UUID.randomUUID()}")
            check(directory.mkdir())
            active.add(directory.name)
            return MediaWorkspace(directory)
        }
        fun cleanAbandoned(context: Context) {
            val root = File(context.cacheDir, "media-work")
            if (!root.isDirectory || Files.isSymbolicLink(root.toPath())) return
            Files.newDirectoryStream(root.toPath()).use { entries ->
                entries.asSequence().take(32).filter { jobName.matches(it.fileName.toString()) && it.fileName.toString() !in active && Files.isDirectory(it) && !Files.isSymbolicLink(it) }
                    .forEach { MediaWorkspace(it.toFile()).close() }
            }
        }
    }
    fun file(name: String): File {
        require(name.matches(Regex("[a-zA-Z0-9._-]{1,96}")) && name !in setOf(".", ".."))
        val file = File(directory, name)
        check(file.canonicalFile.parentFile == directory.canonicalFile && !Files.isSymbolicLink(file.toPath()))
        return file
    }
    fun checkBudget() {
        var bytes = 0L
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            var count = 0
            entries.forEach {
                require(++count <= MAX_FILES && !Files.isSymbolicLink(it) && Files.isRegularFile(it)) { "Laikinų failų riba pasiekta" }
                bytes += Files.size(it)
                require(bytes <= MAX_BYTES) { "Išvesties failas per didelis" }
            }
        }
        require(directory.usableSpace >= EditLimits.MIN_FREE_BYTES) { "Nepakanka laisvos vietos" }
    }
    override fun close() {
        check(jobName.matches(directory.name) && !Files.isSymbolicLink(directory.toPath()))
        if (!directory.exists()) return
        Files.newDirectoryStream(directory.toPath()).use { entries ->
            val paths = entries.asSequence().take(MAX_FILES + 1).toList()
            check(paths.size <= MAX_FILES) { "AF_MEDIA_CLEANUP_LIMIT" }
            paths.forEach {
                check(!Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "AF_MEDIA_CLEANUP_UNEXPECTED" }
                Files.deleteIfExists(it)
            }
        }
        check(directory.delete()) { "Laikinos redagavimo kopijos pašalinti nepavyko" }
        active.remove(directory.name)
    }
}
