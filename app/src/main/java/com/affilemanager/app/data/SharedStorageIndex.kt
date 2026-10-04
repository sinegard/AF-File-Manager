package com.affilemanager.app.data

import android.content.ContentResolver
import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.media.MediaScannerConnection
import android.os.Bundle
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Publishes committed filesystem changes to other Android apps, not just AF's list. */
class SharedStorageIndex(context: Context) {
    private val context = context.applicationContext
    private val lock = Mutex()
    var onChanged: () -> Unit = {}

    suspend fun changed(paths: List<File>) = withContext(Dispatchers.IO) {
        require(paths.size <= MAX_NODES) { "Per daug elementų" }
        lock.withLock {
            val roots = LocalFileRepository(context).roots().map { File(it.path).toPath().normalize() }
            var visited = 0
            var sharedMutation = false
            val batch = linkedSetOf<String>()
            suspend fun add(path: String) {
                batch += path
                if (batch.size >= BATCH_SIZE) {
                    scan(batch.toTypedArray())
                    batch.clear()
                }
            }
            try {
                val normalized = paths.map { it.absoluteFile.toPath().normalize() }.distinct()
                val requestedPaths = normalized.toHashSet()
                val topLevel = normalized.filter { candidate ->
                    var parent = candidate.parent
                    var selectedParent = false
                    while (parent != null) {
                        if (parent in requestedPaths) { selectedParent = true; break }
                        parent = parent.parent
                    }
                    !selectedParent
                }
                topLevel.forEach { path ->
                    coroutineContext.ensureActive()
                    if (roots.none(path::startsWith) || isPrivate(path.toString()) || isPrivate(path.toFile().canonicalPath)) return@forEach
                    sharedMutation = true
                    // Also find obsolete children after replacing or partially deleting a
                    // directory. An existing root does not mean all its old files still exist.
                    val exists = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    if (!exists || Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        val escaped = path.toString().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
                        val remaining = MAX_NODES - visited
                        val args = Bundle().apply {
                            putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                                "${MediaStore.MediaColumns.DATA} = ? OR ${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'")
                            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(path.toString(), "$escaped/%"))
                            putInt(ContentResolver.QUERY_ARG_LIMIT, remaining + 1)
                        }
                        val uri = MediaStore.Files.getContentUri("external").buildUpon()
                            .appendQueryParameter("limit", (remaining + 1).toString()).build()
                        val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), args, null)
                            ?: throw java.io.IOException("Android failų indekso atnaujinti nepavyko")
                        cursor.use {
                            while (it.moveToNext()) {
                                coroutineContext.ensureActive()
                                check(++visited <= MAX_NODES) { "Per daug elementų" }
                                it.getString(0)?.let { old -> if (!File(old).exists()) add(old) }
                            }
                        }
                    }
                    if (exists) {
                        suspend fun visit(file: File, depth: Int) {
                            coroutineContext.ensureActive()
                            check(++visited <= MAX_NODES && depth <= MAX_DEPTH) { "Per daug elementų" }
                            if (Files.isSymbolicLink(file.toPath())) return
                            if (file.isDirectory) {
                                Files.newDirectoryStream(file.toPath()).use { children ->
                                    for (child in children) visit(child.toFile(), depth + 1)
                                }
                            } else if (file.isFile && !isPrivate(file.path)) add(file.absolutePath)
                        }
                        visit(path.toFile(), 0)
                    }
                }
                if (batch.isNotEmpty()) scan(batch.toTypedArray())
            } finally {
                // Even partial changes invalidate AF's old category snapshots.
                if (sharedMutation) onChanged()
            }
        }
    }

    private suspend fun scan(paths: Array<String>) = withTimeout(30_000L) {
        suspendCancellableCoroutine<Unit> { continuation ->
            val remaining = AtomicInteger(paths.size)
            val failure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            MediaScannerConnection.scanFile(context, paths, null) { path, uri ->
                try {
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) updateLegacyFile(path)
                } catch (error: Throwable) { failure.compareAndSet(null, error) }
                if (remaining.decrementAndGet() == 0 && continuation.isActive) {
                    val error = failure.get()
                    if (error == null) continuation.resume(Unit) else continuation.resumeWithException(error)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun updateLegacyFile(path: String) {
        val file = File(path)
        val collection = MediaStore.Files.getContentUri("external")
        if (file.isFile && file.canRead()) {
            // Android 8/9's scanner ignores some non-media types, including stored APKs.
            // Keep the legacy Files provider current without altering the actual file.
            var parent = file.parentFile
            while (parent != null) {
                if (File(parent, ".nomedia").exists()) return
                parent = parent.parentFile
            }
            val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase(java.util.Locale.ROOT))
                ?: "application/octet-stream"
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, path)
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.SIZE, file.length())
                put(MediaStore.MediaColumns.DATE_MODIFIED, file.lastModified() / 1000L)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
            }
            if (context.contentResolver.update(collection, values, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path)) == 0) {
                check(context.contentResolver.insert(collection, values) != null) { "Android failų indekso atnaujinti nepavyko" }
            }
        }
        // The legacy provider normally deletes the underlying media as well. The
        // metadata-only flag also protects a file recreated here by another app
        // between our existence check and the provider call (Android 8/9 only).
        else if (!file.exists()) context.contentResolver.delete(
            collection.buildUpon().appendQueryParameter("deletedata", "false").build(),
            "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path))
    }

    private fun isPrivate(path: String): Boolean = path == context.dataDir.path || path == context.filesDir.path ||
        path.startsWith(context.dataDir.path + "/") ||
        path.endsWith("/Android/data") || path.endsWith("/Android/obb") ||
        path.contains("/Android/data/") || path.contains("/Android/obb/")

    companion object {
        // A move can publish both the old indexed tree and its new physical tree.
        private const val MAX_NODES = 400_000
        private const val MAX_DEPTH = 128
        private const val BATCH_SIZE = 64
    }
}
