package com.affilemanager.app.transfer

import android.app.Application
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.workflow.*
import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** One process-local receiver. A folder change swaps metadata, not the listener or an active sink. */
internal class NearbyReceiveStorage(
    private val app: Application,
    private val reader: NearbySourceReader,
    path: String,
    configuration: NearbyReceiveConfiguration,
    private val warning: (String) -> Unit = {},
) : LanReceiveStorage {
    private sealed interface Root {
        val path: String
        data class Local(val file: File, val identity: Any) : Root { override val path: String get() = file.path }
        data class Document(override val path: String, val title: String) : Root
    }
    private data class Snapshot(val base: Root, val types: Map<EntryKind, Root>)
    private val journal = NearbyReceiveStageJournal(app, warning)
    private val current = AtomicReference(validate(path, configuration))
    override val label: String get() = when (val base = current.get().base) {
        is Root.Local -> base.file.name.ifBlank { "AF File Manager" }
        is Root.Document -> base.title
    }
    val rootPath: String get() = current.get().base.path
    val usesDocuments: Boolean get() = current.get().base is Root.Document

    private fun requireDocumentAccess(uri: Uri, write: Boolean) {
        val tree = android.provider.DocumentsContract.getTreeDocumentId(uri)
        if (app.contentResolver.persistedUriPermissions.none {
            it.isReadPermission && (!write || it.isWritePermission) && it.uri.authority == uri.authority &&
                runCatching { android.provider.DocumentsContract.getTreeDocumentId(it.uri) == tree }.getOrDefault(false)
        }) throw SecurityException("Document access was revoked; select the folder again")
    }

    fun update(path: String, configuration: NearbyReceiveConfiguration) {
        val validated = validate(path, configuration)
        current.set(validated)
    }
    fun prepareUpdate(path: String, configuration: NearbyReceiveConfiguration): () -> Unit {
        val validated = validate(path, configuration)
        return { current.set(validated) }
    }

    private fun validate(path: String, configuration: NearbyReceiveConfiguration): Snapshot {
        configuration.checkMemory()
        journal.recover()
        val allowed = configuration.approvedRootPaths.map { File(it).absoluteFile }
        fun root(value: String): Root {
            require(value.length in 1..4_096 && value.none(Char::isISOControl)) { "Netinkamas gavimo kelias" }
            reader.documentUri(value)?.let { uri ->
                requireDocumentAccess(uri, write = true)
                val location = AfLocationRef.document(uri.toString())
                val storage = AfDocumentStorage(app)
                val title = try { runBlocking {
                    val node = storage.stat(location)
                    require(node?.directory == true) { "Gavimo katalogas nepasiekiamas" }
                    storage.uriFor(location, write = true)
                    node.name
                } } finally { runBlocking { storage.close() } }
                return Root.Document(uri.toString(), title)
            }
            val file = File(value).absoluteFile
            val approved = allowed.ifEmpty { listOf(File(path).absoluteFile) }
                .firstOrNull { FileSystemRules.isContained(it, file) }
                ?: throw IllegalArgumentException("Gavimo katalogas nepasiekiamas")
            val contained = FileSystemRules.containedNonSymbolicPath(approved, file)
            require(contained.isDirectory && contained.canRead() && contained.canWrite()) { "Gavimo katalogas nepasiekiamas" }
            val identity = requireNotNull(Files.readAttributes(contained.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey())
            return Root.Local(contained, identity)
        }
        return Snapshot(root(path), configuration.typePaths.mapValues { root(it.value) })
    }

    private fun checked(root: Root.Local): File {
        require(!Files.isSymbolicLink(root.file.toPath()) && root.file.isDirectory &&
            Files.readAttributes(root.file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == root.identity) {
            "Gavimo katalogas nepasiekiamas"
        }
        return root.file
    }

    private fun parts(relative: String): List<String> {
        require(relative.length <= 4_096 && !relative.startsWith('/') && !relative.startsWith('\\') && '\u0000' !in relative) { "Netinkamas santykinis kelias" }
        val parts = relative.replace('\\', '/').split('/').filter(String::isNotEmpty)
        require(parts.size <= 65) { "Netinkamas santykinis kelias" }
        return parts.map { FileSystemRules.validateFileName(it).getOrThrow() }
    }

    private fun local(root: Root.Local, relative: String, create: Boolean): File {
        val base = checked(root)
        var directory = base
        parts(relative).forEach { name ->
            directory = FileSystemRules.containedNonSymbolicPath(base, File(directory, name))
            if (create && !directory.exists()) require(directory.mkdir()) { "Aplanko sukurti nepavyko" }
        }
        return directory
    }

    private fun document(root: Root.Document, relative: String, create: Boolean, storage: AfDocumentStorage): AfLocationRef = runBlocking {
        requireDocumentAccess(Uri.parse(root.path), write = create)
        var location = AfLocationRef.document(root.path)
        for (name in parts(relative)) {
            location = AfWorkflowPaths.child(location, name)
            if (create) storage.createDirectory(location)
        }
        location
    }

    override fun directory(relativeDirectory: String, fileName: String, routeByType: Boolean): LanReceiveDirectory {
        parts(relativeDirectory)
        FileSystemRules.validateFileName(fileName).getOrThrow()
        val snapshot = current.get() // This reference never changes for an in-flight file.
        return when (val root = if (routeByType) snapshot.types[FileSystemRules.detectKind(fileName, null)] ?: snapshot.base else snapshot.base) {
            is Root.Local -> LanReceiveDirectory.Local(checked(root), local(root, relativeDirectory, create = true).also {
                require(it.isDirectory && it.canWrite()) { "Gavimo katalogas neleidžia rašyti" }
            })
            is Root.Document -> LanReceiveDirectory.Documents { name ->
                val storage = AfDocumentStorage(app)
                try {
                    val folder = document(root, relativeDirectory, create = true, storage)
                    DocumentUpload(storage, folder, name)
                } catch (error: Throwable) { runBlocking { storage.close() }; throw error }
            }
        }
    }

    override fun createDirectory(relativePath: String) {
        when (val root = current.get().base) {
            is Root.Local -> require(local(root, relativePath, true).isDirectory) { "Aplankas nepasiekiamas" }
            is Root.Document -> AfDocumentStorage(app).let { storage ->
                try { document(root, relativePath, true, storage) } finally { runBlocking { storage.close() } }
            }
        }
    }

    override fun list(relativeDirectory: String): List<LanSharedEntry> = when (val root = current.get().base) {
        is Root.Local -> {
            val folder = local(root, relativeDirectory, false)
            require(folder.isDirectory) { "Aplankas nepasiekiamas" }
            val entries = ArrayList<LanSharedEntry>()
            var characters = 0L
            Files.newDirectoryStream(folder.toPath()).use { children -> for (child in children) {
                require(entries.size < 46_000) { "Kataloge per daug elementų interneto peržiūrai" }
                if (Files.isSymbolicLink(child)) continue
                val file = FileSystemRules.containedNonSymbolicPath(checked(root), child.toFile())
                characters += file.name.length
                require(characters <= NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS) { "Siuntimo rinkinio kelių aprašas per didelis" }
                entries += LanSharedEntry(file.name, file.isDirectory, if (file.isFile) file.length() else 0)
            } }
            entries
        }
        is Root.Document -> AfDocumentStorage(app).let { storage ->
            try { runBlocking { storage.list(document(root, relativeDirectory, false, storage)).map { LanSharedEntry(it.name, it.directory, it.sizeBytes) } } }
            finally { runBlocking { storage.close() } }
        }
    }

    override fun read(relativePath: String): LanSharedRead = when (val root = current.get().base) {
        is Root.Local -> local(root, relativePath, false).let { file ->
            require(file.isFile && file.canRead()) { "Failas nepasiekiamas" }
            LanSharedRead(file.name, file.length(), file.inputStream())
        }
        is Root.Document -> AfDocumentStorage(app).let { storage ->
            try { runBlocking {
                val location = document(root, relativePath, false, storage)
                val node = requireNotNull(storage.stat(location)) { "Failas nepasiekiamas" }
                require(!node.directory) { "Failas nepasiekiamas" }
                LanSharedRead(node.name, node.sizeBytes, requireNotNull(app.contentResolver.openInputStream(storage.uriFor(location))))
            } } finally { runBlocking { storage.close() } }
        }
    }

    private inner class DocumentUpload(private val storage: AfDocumentStorage, private val folder: AfLocationRef,
                                       requestedName: String) : LanDocumentUpload {
        private val digest = MessageDigest.getInstance("SHA-256")
        private val stageName = ".af-nearby-${UUID.randomUUID()}.partial"
        private val parentUri = runBlocking { storage.uriFor(folder, write = true) }
        private val stageLocation = AfWorkflowPaths.child(folder, stageName)
        private var identity: StructStat? = null
        private var payloadHash: String? = null
        private var published = false
        private var closed = false
        private var disposed = false
        private val target = runBlocking {
            val used = storage.list(folder).mapTo(hashSetOf()) { it.name }
            var name = FileSystemRules.validateFileName(requestedName).getOrThrow()
            val dot = name.lastIndexOf('.').takeIf { it > 0 }
            val stem = dot?.let { name.substring(0, it) } ?: name
            val extension = dot?.let { name.substring(it) }.orEmpty()
            var suffix = 0
            while (name in used) {
                require(++suffix <= 10_000) { "Nepavyko parinkti laisvo pavadinimo" }
                name = "$stem ($suffix)$extension"
            }
            AfWorkflowPaths.child(folder, name)
        }
        private val journalId = journal.reserve(parentUri, stageName)
        private val stageUri = try {
            requireNotNull(android.provider.DocumentsContract.createDocument(app.contentResolver, parentUri,
                "application/octet-stream", stageName)) { "Document destination could not be created" }
        } catch (error: Throwable) { journal.release(journalId); warning("Document recovery is required"); throw error }
        private val descriptor = try {
            require(runBlocking { storage.uriFor(stageLocation, write = true) }.toString() == stageUri.toString()) { "Provider changed the destination name" }
            requireNotNull(app.contentResolver.openFileDescriptor(stageUri, "rw")) { "Document destination cannot be opened" }.also {
                try {
                    val initial = Os.fstat(it.fileDescriptor)
                    require(initial.st_size == 0L && OsConstants.S_ISREG(initial.st_mode)) { "Provider cannot prove failed-copy ownership" }
                }
                catch (error: Throwable) { it.close(); throw error }
            }
        } catch (error: Throwable) {
            journal.release(journalId); journal.recover(); warning("Document recovery is required"); throw error
        }
        // Android may return raw-volume writers but FUSE readers (different dev IDs and
        // FAT timestamp precision). Compare two READ views, retaining the original object.
        private val readWitness = try {
            requireNotNull(app.contentResolver.openFileDescriptor(stageUri, "r")) { "Document cannot be opened" }.also {
                try { journal.witness(journalId, stageUri, Os.fstat(it.fileDescriptor)) }
                catch (error: Throwable) { it.close(); throw error }
            }
        } catch (error: Throwable) {
            descriptor.close(); journal.release(journalId); warning("Document recovery is required"); throw error
        }
        private val descriptorOutput = ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
        override val output = object : FilterOutputStream(DigestOutputStream(descriptorOutput, digest)) {
            override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
            override fun close() {
                if (closed) return
                try { flush() }
                finally {
                    closed = true
                    try { super.close() }
                    finally {
                        identity = Os.fstat(readWitness.fileDescriptor)
                        payloadHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                        journal.witness(journalId, stageUri, requireNotNull(identity), requireNotNull(payloadHash))
                    }
                }
            }
        }
        override fun sync() { output.flush(); if (OsConstants.S_ISREG(Os.fstat(descriptor.fileDescriptor).st_mode)) descriptor.fileDescriptor.sync() }
        override fun commit(expectedSize: Long, checkpoint: () -> Unit): LanPublishedDocument = runBlocking {
            checkpoint()
            requireDocumentAccess(Uri.parse(folder.path), write = true)
            val hash = requireNotNull(payloadHash)
            require(storage.stat(stageLocation)?.sizeBytes == expectedSize && storage.hash(stageLocation, null, checkpoint) == hash) { "Document copy verification failed" }
            storage.installStream(expectedSize, hash, {
                requireNotNull(app.contentResolver.openInputStream(stageUri)) { "Document cannot be opened" }
            }, target, OperationContext.background(), checkpoint)
            published = true
            val node = requireNotNull(storage.stat(target))
            LanPublishedDocument(storage.uriFor(target).toString(), node.name, node.modifiedAtMillis ?: 0)
        }
        override fun close() {
            if (disposed) return
            disposed = true
            try {
                output.close()
                journal.removeOwned(journalId)
            } catch (failure: Exception) {
                warning("Document recovery is required")
                if (!published) throw AfDocumentRecoveryException(parentUri.toString(), failure)
            } finally { try { readWitness.close() } finally { journal.release(journalId); runBlocking { storage.close() } } }
        }
    }
}
