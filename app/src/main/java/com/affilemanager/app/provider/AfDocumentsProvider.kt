package com.affilemanager.app.provider

import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException

class AfDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val MAX_CHILDREN = 50_000
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_AVAILABLE_BYTES,
            Root.COLUMN_ICON,
        )
        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }

    private data class ExportedRoot(val id: String, val title: String, val directory: File)

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection?.let(::copyProjection) ?: ROOT_PROJECTION)
        roots().forEach { root ->
            val row = cursor.newRow()
            put(row, cursor, Root.COLUMN_ROOT_ID, root.id)
            put(row, cursor, Root.COLUMN_DOCUMENT_ID, AfDocumentIdRules.root(root.id))
            put(row, cursor, Root.COLUMN_TITLE, root.title)
            put(row, cursor, Root.COLUMN_SUMMARY, "AF File Manager")
            put(
                row,
                cursor,
                Root.COLUMN_FLAGS,
                Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_CREATE or Root.FLAG_SUPPORTS_IS_CHILD,
            )
            put(row, cursor, Root.COLUMN_MIME_TYPES, "*/*")
            put(row, cursor, Root.COLUMN_AVAILABLE_BYTES, root.directory.usableSpace.coerceAtLeast(0L))
            put(row, cursor, Root.COLUMN_ICON, context?.applicationInfo?.icon ?: 0)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection?.let(::copyProjection) ?: DOCUMENT_PROJECTION)
        includeDocument(cursor, documentId, resolve(documentId))
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection?.let(::copyProjection) ?: DOCUMENT_PROJECTION)
        val parent = resolve(parentDocumentId)
        if (!parent.isDirectory) throw FileNotFoundException("Not a directory")
        val parsed = AfDocumentIdRules.parse(parentDocumentId)
        val exportedRoot = root(parsed.rootId)
        parent.listFiles().orEmpty().asSequence()
            .filter { child -> runCatching { AfDocumentIdRules.relative(exportedRoot.directory, child) }.isSuccess }
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            .take(MAX_CHILDREN)
            .forEach { child ->
                val relative = AfDocumentIdRules.relative(exportedRoot.directory, child)
                includeDocument(cursor, AfDocumentIdRules.encode(exportedRoot.id, relative), child)
            }
        return cursor
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        signal?.throwIfCanceled()
        val file = resolve(documentId)
        if (!file.isFile) throw FileNotFoundException("Not a file")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode))
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        val parent = resolve(parentDocumentId)
        if (!parent.isDirectory || !parent.canWrite()) throw FileNotFoundException("Parent is not writable")
        val safeName = safeName(displayName)
        val target = uniqueChild(parent, safeName)
        val created = if (mimeType == Document.MIME_TYPE_DIR) target.mkdir() else target.createNewFile()
        if (!created) throw FileNotFoundException("Document could not be created")
        return idFor(parentDocumentId, target)
    }

    override fun deleteDocument(documentId: String) {
        val file = resolve(documentId)
        if (file.parentFile == null || !deleteRecursivelyBounded(file, 0)) throw FileNotFoundException("Delete failed")
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val file = resolve(documentId)
        val parent = file.parentFile ?: throw FileNotFoundException("Storage root cannot be renamed")
        val target = uniqueChild(parent, safeName(displayName), file)
        if (!file.renameTo(target)) throw FileNotFoundException("Rename failed")
        return idFor(documentId, target)
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean = runCatching {
        val parentParsed = AfDocumentIdRules.parse(parentDocumentId)
        val childParsed = AfDocumentIdRules.parse(documentId)
        if (parentParsed.rootId != childParsed.rootId) return@runCatching false
        val parent = resolve(parentDocumentId).canonicalFile
        val child = resolve(documentId).canonicalFile
        child != parent && child.path.startsWith(parent.path + File.separator)
    }.getOrDefault(false)

    private fun includeDocument(cursor: MatrixCursor, documentId: String, file: File) {
        if (!file.exists()) throw FileNotFoundException("Document not found")
        val row = cursor.newRow()
        put(row, cursor, Document.COLUMN_DOCUMENT_ID, documentId)
        put(row, cursor, Document.COLUMN_DISPLAY_NAME, file.name.ifEmpty { root(AfDocumentIdRules.parse(documentId).rootId).title })
        put(row, cursor, Document.COLUMN_MIME_TYPE, mimeType(file))
        put(row, cursor, Document.COLUMN_SIZE, if (file.isFile) file.length().coerceAtLeast(0L) else null)
        put(row, cursor, Document.COLUMN_LAST_MODIFIED, file.lastModified().takeIf { it > 0L })
        var flags = 0
        if (file.canWrite() && file.parentFile != null) flags = flags or Document.FLAG_SUPPORTS_RENAME or Document.FLAG_SUPPORTS_DELETE
        if (file.isDirectory && file.canWrite()) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (file.isFile && file.canWrite()) flags = flags or Document.FLAG_SUPPORTS_WRITE
        put(row, cursor, Document.COLUMN_FLAGS, flags)
    }

    private fun resolve(documentId: String): File {
        val parsed = runCatching { AfDocumentIdRules.parse(documentId) }
            .getOrElse { throw FileNotFoundException("Invalid document ID") }
        val exportedRoot = root(parsed.rootId)
        return runCatching { AfDocumentIdRules.resolve(exportedRoot.directory, documentId) }
            .getOrElse { throw FileNotFoundException(it.message) }
    }

    private fun idFor(referenceDocumentId: String, file: File): String {
        val parsed = AfDocumentIdRules.parse(referenceDocumentId)
        val exportedRoot = root(parsed.rootId)
        return AfDocumentIdRules.encode(exportedRoot.id, AfDocumentIdRules.relative(exportedRoot.directory, file))
    }

    private fun roots(): List<ExportedRoot> {
        val found = linkedMapOf<String, ExportedRoot>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val manager = context?.getSystemService(StorageManager::class.java)
            manager?.storageVolumes.orEmpty().forEachIndexed { index, volume ->
                val directory = volume.directory?.takeIf(File::isDirectory) ?: return@forEachIndexed
                val id = if (volume.isPrimary) "primary" else "volume-${volume.uuid?.lowercase() ?: index}"
                val title = if (volume.isPrimary) "Internal storage" else volume.getDescription(requireNotNull(context))
                found[id] = ExportedRoot(id, title, directory)
            }
        }
        if (found.isEmpty()) {
            val directory = Environment.getExternalStorageDirectory()
            if (directory.isDirectory) found["primary"] = ExportedRoot("primary", "Internal storage", directory)
        }
        return found.values.toList()
    }

    private fun root(id: String): ExportedRoot = roots().firstOrNull { it.id == id }
        ?: throw FileNotFoundException("Storage root is unavailable")

    private fun mimeType(file: File): String = if (file.isDirectory) {
        Document.MIME_TYPE_DIR
    } else {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }

    private fun safeName(value: String): String {
        val trimmed = value.trim()
        require(trimmed.isNotEmpty() && trimmed.length <= 255 && trimmed !in setOf(".", "..") &&
            '/' !in trimmed && '\\' !in trimmed && '\u0000' !in trimmed) { "Invalid display name" }
        return trimmed
    }

    private fun uniqueChild(parent: File, requested: String, current: File? = null): File {
        var candidate = File(parent, requested)
        if (candidate == current || !candidate.exists()) return candidate
        val stem = requested.substringBeforeLast('.', requested)
        val suffix = requested.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        for (index in 1..9_999) {
            candidate = File(parent, "$stem ($index)$suffix")
            if (candidate == current || !candidate.exists()) return candidate
        }
        throw FileNotFoundException("No free document name")
    }

    private fun deleteRecursivelyBounded(file: File, depth: Int): Boolean {
        if (depth > 64) return false
        if (file.isDirectory) {
            val children = file.listFiles() ?: return false
            if (children.size > MAX_CHILDREN || children.any { !deleteRecursivelyBounded(it, depth + 1) }) return false
        }
        return file.delete()
    }

    private fun put(row: MatrixCursor.RowBuilder, cursor: MatrixCursor, column: String, value: Any?) {
        if (cursor.getColumnIndex(column) >= 0) row.add(column, value)
    }

    private fun copyProjection(projection: Array<out String>): Array<String> =
        Array(projection.size) { index -> projection[index] }
}
