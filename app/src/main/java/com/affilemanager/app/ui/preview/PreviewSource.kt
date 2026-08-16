package com.affilemanager.app.ui.preview

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.editing.EditSession
import com.affilemanager.app.model.ContentFileEntry
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.network.RemoteEntry
import com.affilemanager.app.ui.PreviewTarget
import java.io.File
import java.io.InputStream

internal sealed interface PreviewSource {
    val key: String
    val name: String
    val kind: EntryKind
    val extension: String
    val sizeBytes: Long?
    val modifiedAtMillis: Long?
    val isReadable: Boolean
    val isWritable: Boolean
    val localFile: File?
    val locationLabel: String

    fun mimeType(context: Context): String
    fun uri(context: Context): Uri
    fun openFileDescriptor(context: Context): ParcelFileDescriptor
    fun openInputStream(context: Context): InputStream

    data class Local(val entry: FileEntry) : PreviewSource {
        override val key: String = "local|${entry.absolutePath}|${entry.modifiedAtMillis}|${entry.sizeBytes}"
        override val name: String = entry.name
        override val kind: EntryKind = entry.kind
        override val extension: String = entry.extension
        override val sizeBytes: Long = entry.sizeBytes
        override val modifiedAtMillis: Long = entry.modifiedAtMillis
        override val isReadable: Boolean = entry.isReadable
        override val isWritable: Boolean = entry.isWritable
        override val localFile: File = entry.file
        override val locationLabel: String = entry.absolutePath

        override fun mimeType(context: Context): String = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"

        override fun uri(context: Context): Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            entry.file,
        )

        override fun openFileDescriptor(context: Context): ParcelFileDescriptor = requireNotNull(
            ParcelFileDescriptor.open(entry.file, ParcelFileDescriptor.MODE_READ_ONLY),
        ) { "Failo srautas nepasiekiamas" }

        override fun openInputStream(context: Context): InputStream = entry.file.inputStream()
    }

    data class Content(val entry: ContentFileEntry) : PreviewSource {
        private val parsedUri = Uri.parse(entry.uri)
        override val key: String = "content|${entry.uri}|${entry.sizeBytes ?: -1}"
        override val name: String = entry.name
        override val kind: EntryKind = entry.kind
        override val extension: String = entry.extension
        override val sizeBytes: Long? = entry.sizeBytes
        override val modifiedAtMillis: Long? = entry.modifiedAtMillis
        override val isReadable: Boolean = true
        override val isWritable: Boolean = entry.isWritable
        override val localFile: File? = null
        override val locationLabel: String = entry.uri

        override fun mimeType(context: Context): String = entry.mimeType
        override fun uri(context: Context): Uri = parsedUri

        override fun openFileDescriptor(context: Context): ParcelFileDescriptor = requireNotNull(
            context.contentResolver.openFileDescriptor(parsedUri, "r"),
        ) { "Failo srautas nepasiekiamas" }

        override fun openInputStream(context: Context): InputStream = requireNotNull(
            context.contentResolver.openInputStream(parsedUri),
        ) { "Failo srautas nepasiekiamas" }
    }

    data class Remote(
        val entry: RemoteEntry,
        val cachedFile: File,
        val profileId: String,
        val connectionName: String,
    ) : PreviewSource {
        override val key: String = "remote|$profileId|${entry.path}|${cachedFile.absolutePath}"
        override val name: String = entry.name
        override val kind: EntryKind = FileSystemRules.detectKind(entry.name, mimeType = null, isDirectory = false)
        override val extension: String = entry.name.substringAfterLast('.', "").lowercase()
        override val sizeBytes: Long = cachedFile.length().coerceAtLeast(0)
        override val modifiedAtMillis: Long? = entry.modifiedAtMillis?.takeIf { it > 0 }
        override val isReadable: Boolean = cachedFile.isFile && cachedFile.canRead()
        override val isWritable: Boolean = false
        override val localFile: File = cachedFile
        override val locationLabel: String = "$connectionName · ${entry.path}"

        override fun mimeType(context: Context): String = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"

        override fun uri(context: Context): Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            cachedFile,
        )

        override fun openFileDescriptor(context: Context): ParcelFileDescriptor = requireNotNull(
            ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY),
        ) { "Failo srautas nepasiekiamas" }

        override fun openInputStream(context: Context): InputStream = cachedFile.inputStream()
    }

    data class Privileged(
        val entry: FileEntry,
        val cachedFile: File,
    ) : PreviewSource {
        override val key: String = "privileged|${entry.absolutePath}|${entry.modifiedAtMillis}|${entry.sizeBytes}"
        override val name: String = entry.name
        override val kind: EntryKind = entry.kind
        override val extension: String = entry.extension
        override val sizeBytes: Long = cachedFile.length().coerceAtLeast(0)
        override val modifiedAtMillis: Long? = entry.modifiedAtMillis.takeIf { it > 0 }
        override val isReadable: Boolean = cachedFile.isFile && cachedFile.canRead()
        override val isWritable: Boolean = entry.isWritable
        override val localFile: File = cachedFile
        override val locationLabel: String = entry.absolutePath

        override fun mimeType(context: Context): String = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"

        override fun uri(context: Context): Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            cachedFile,
        )

        override fun openFileDescriptor(context: Context): ParcelFileDescriptor = requireNotNull(
            ParcelFileDescriptor.open(cachedFile, ParcelFileDescriptor.MODE_READ_ONLY),
        ) { "Failo srautas nepasiekiamas" }

        override fun openInputStream(context: Context): InputStream = cachedFile.inputStream()
    }

    data class Working(
        val original: PreviewSource,
        val session: EditSession,
    ) : PreviewSource {
        override val key: String = "edit|${session.id}|${session.workingRevision.sha256}"
        override val name: String = session.displayName
        override val kind: EntryKind = original.kind
        override val extension: String = original.extension
        override val sizeBytes: Long = session.workingRevision.sizeBytes
        override val modifiedAtMillis: Long? = session.workingRevision.modifiedAtMillis
        override val isReadable: Boolean = session.workingFile.isFile && session.workingFile.canRead()
        override val isWritable: Boolean = true
        override val localFile: File = session.workingFile
        override val locationLabel: String = original.locationLabel

        override fun mimeType(context: Context): String = session.mimeType

        override fun uri(context: Context): Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.files",
            session.workingFile,
        )

        override fun openFileDescriptor(context: Context): ParcelFileDescriptor = requireNotNull(
            ParcelFileDescriptor.open(session.workingFile, ParcelFileDescriptor.MODE_READ_ONLY),
        ) { "Editable copy is unavailable" }

        override fun openInputStream(context: Context): InputStream = session.workingFile.inputStream()
    }
}

internal fun PreviewTarget.previewSource(): PreviewSource = when (this) {
    is PreviewTarget.LocalFile -> PreviewSource.Local(entry)
    is PreviewTarget.TrashFile -> PreviewSource.Local(entry)
    is PreviewTarget.ContentFile -> PreviewSource.Content(entry)
    is PreviewTarget.RemoteFile -> PreviewSource.Remote(remote, cachedFile, profileId, connectionName)
    is PreviewTarget.PrivilegedFile -> PreviewSource.Privileged(entry, cachedFile)
    is PreviewTarget.Archive -> PreviewSource.Local(file)
    is PreviewTarget.RemoteArchive -> PreviewSource.Remote(remote, file.file, profileId, connectionName)
    is PreviewTarget.Vault -> PreviewSource.Local(file)
}
