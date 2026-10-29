package com.affilemanager.app.workflow

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** IO belongs to the enclosing storage session; grants are never inferred from raw paths. */
internal class AfDocumentStorage(private val context: Context) {
    private val resolver = context.contentResolver
    private data class Node(val uri: Uri, val name: String, val directory: Boolean, val size: Long, val modified: Long?)
    private data class Index(val names: MutableMap<String, Node>, var bytes: Long)
    private val indexes = linkedMapOf<String, Index>()
    private var indexBytes = 0L
    private var indexNodes = 0
    private val changed = linkedSetOf<String>()
    private val checkedRecoveryVolumes = hashSetOf<String>()

    private suspend fun recoverPending(uri: Uri) {
        if (uri.authority == "com.android.externalstorage.documents") {
            val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
            if (volume !in checkedRecoveryVolumes) {
                require(checkedRecoveryVolumes.size < 100) { "Document volume limit reached" }
                AfDocumentEmptyDirectoryRemoval(context).recoverPending(volume)
                checkedRecoveryVolumes += volume
            }
        }
    }

    private suspend fun anchor(location: AfLocationRef, write: Boolean = false): Uri {
        require(location.kind == AfLocationKind.DOCUMENT)
        val uri = Uri.parse(location.normalized().path)
        require(DocumentsContract.isTreeUri(uri)) { "A granted document tree is required" }
        val tree = DocumentsContract.getTreeDocumentId(uri)
        require(resolver.persistedUriPermissions.any { permission ->
            permission.uri.authority == uri.authority && DocumentsContract.isTreeUri(permission.uri) &&
                DocumentsContract.getTreeDocumentId(permission.uri) == tree && permission.isReadPermission &&
                (!write || permission.isWritePermission)
        }) { "Document access was revoked; select the folder again" }
        recoverPending(uri)
        return if (uri.pathSegments.contains("document")) uri else DocumentsContract.buildDocumentUriUsingTree(uri, tree)
    }

    private fun node(uri: Uri): Node? = resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        Node(uri, cursor.getString(1) ?: error("Document name is unavailable"),
            cursor.getString(2) == Document.MIME_TYPE_DIR,
            if (cursor.isNull(3)) 0 else cursor.getLong(3).coerceAtLeast(0),
            if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 })
    }

    private suspend fun children(uri: Uri, fresh: Boolean = false, maximumRows: Int = MAX_CHILDREN): Map<String, Node> {
        require(maximumRows in 1..MAX_CHILDREN) { "Document folder contains too many entries" }
        if (!fresh) indexes[uri.toString()]?.let { return it.names }
        val result = linkedMapOf<String, Node>()
        var bytes = 0L
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, DocumentsContract.getDocumentId(uri))
        resolver.query(childrenUri, COLUMNS, null, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                currentCoroutineContext().ensureActive()
                require(result.size < maximumRows) { "Document folder contains too many entries" }
                val name = cursor.getString(1) ?: error("Document name is unavailable")
                require(exactLeafName(name) == name) { "Unsafe document name" }
                val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, cursor.getString(0))
                bytes += 256L + name.length * 2L + childUri.toString().length * 2L
                require(bytes <= MAX_INDEX_BYTES) { "Document folder metadata exceeds its safe memory limit" }
                while (indexes.isNotEmpty() && indexBytes + bytes > MAX_INDEX_BYTES) {
                    val oldest = indexes.entries.first()
                    indexBytes -= oldest.value.bytes; indexNodes -= oldest.value.names.size; indexes.remove(oldest.key)
                }
                val child = Node(childUri, name, cursor.getString(2) == Document.MIME_TYPE_DIR,
                    if (cursor.isNull(3)) 0 else cursor.getLong(3).coerceAtLeast(0),
                    if (cursor.isNull(4)) null else cursor.getLong(4).takeIf { it > 0 })
                require(result.put(name, child) == null) { "Duplicate document names require manual resolution" }
            }
        } ?: error("Document folder cannot be read")
        if (bytes <= NORMAL_INDEX_BYTES) {
            while (indexes.isNotEmpty() && (indexBytes + bytes > NORMAL_INDEX_BYTES || indexNodes + result.size > MAX_CHILDREN)) {
                val first = indexes.entries.first()
                indexBytes -= first.value.bytes
                indexNodes -= first.value.names.size
                indexes.remove(first.key)
            }
            indexes.remove(uri.toString())?.let { indexBytes -= it.bytes; indexNodes -= it.names.size }
            indexes[uri.toString()] = Index(result, bytes)
            indexBytes += bytes
            indexNodes += result.size
        }
        return result
    }

    private suspend fun resolve(location: AfLocationRef, write: Boolean = false, fresh: Boolean = false): Node? {
        var current = node(anchor(location, write)) ?: return null
        if (location.documentPath.isNotEmpty()) for (part in location.documentPath.split('/')) {
            require(current.directory) { "A document path component is not a folder" }
            val indexed = children(current.uri, fresh)[part] ?: return null
            current = node(indexed.uri) ?: return null
            require(current.name == part) { "Document path changed; refresh before retrying" }
        }
        return current
    }

    private fun Node.snapshot(location: AfLocationRef) = AfNodeSnapshot(location, name, directory, if (directory) 0 else size, modified)

    suspend fun stat(location: AfLocationRef): AfNodeSnapshot? = resolve(location)?.snapshot(location)

    suspend fun list(location: AfLocationRef): List<AfNodeSnapshot> {
        val directory = resolve(location) ?: error("Document folder is unavailable")
        require(directory.directory) { "Document is not a folder" }
        return children(directory.uri, fresh = true).values.map { it.snapshot(child(location, it.name)) }
    }

    suspend fun enumerate(source: AfSourceRef): List<AfEnumeratedEntry> {
        data class Pending(val location: AfLocationRef, val node: Node, val relative: String, val depth: Int)
        val root = resolve(source.location) ?: error("Document source is unavailable")
        val pending = ArrayDeque<Pending>()
        val result = arrayListOf<AfEnumeratedEntry>()
        val seen = hashSetOf<String>()
        var metadataBytes = 0L
        fun estimate(item: Pending) = 768L + (item.location.path.length.toLong() + item.location.documentPath.length +
            item.relative.length + item.node.name.length + item.node.uri.toString().length) * 4L
        val first = Pending(source.location, root, "", 0)
        var pendingBytes = estimate(first)
        pending.add(first)
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val item = pending.removeLast()
            pendingBytes -= estimate(item)
            require(item.depth <= MAX_DEPTH && result.size + pending.size < MAX_ENTRIES) { "Document source tree exceeds its safe limit" }
            require(seen.add(item.node.uri.toString())) { "Cyclic document tree is not supported" }
            metadataBytes += estimate(item)
            require(metadataBytes <= AfWorkflowLimits.MAX_METADATA_BYTES * 3 / 4) { "Document tree metadata exceeds its safe memory limit" }
            result += AfEnumeratedEntry(item.relative, item.node.snapshot(item.location), item.depth,
                physicalPath(item.node.uri)?.let { "local:$it" } ?: "provider:${item.node.uri.authority}:${DocumentsContract.getDocumentId(item.node.uri)}")
            if (item.node.directory) children(item.node.uri, fresh = true).values.toList().asReversed().forEach { entry ->
                val next = Pending(child(item.location, entry.name), entry,
                    listOf(item.relative, entry.name).filter(String::isNotEmpty).joinToString("/"), item.depth + 1)
                pendingBytes += estimate(next)
                require(metadataBytes + pendingBytes <= AfWorkflowLimits.MAX_METADATA_BYTES * 3 / 4) { "Document tree metadata exceeds its safe memory limit" }
                pending.add(next)
            }
        }
        return result
    }

    suspend fun validateDestinations(source: AfSourceRef, destinations: List<AfDestinationRef>, allowSiblingCopy: Boolean) {
        val sourceNode = if (source.location.kind == AfLocationKind.DOCUMENT) resolve(source.location) else null
        val sourcePath = when (source.location.kind) {
            AfLocationKind.DOCUMENT -> sourceNode?.let { physicalPath(it.uri) }
            AfLocationKind.LOCAL -> File(source.location.path).canonicalPath
            AfLocationKind.REMOTE -> null
        }
        destinations.forEach { destination ->
            val destinationPath = when (destination.location.kind) {
                AfLocationKind.DOCUMENT -> resolve(destination.location, write = true)?.let { physicalPath(it.uri) }
                AfLocationKind.LOCAL -> File(destination.location.path).canonicalPath
                AfLocationKind.REMOTE -> null
            }
            if (sourcePath != null && destinationPath != null && source.kind == AfSourceKind.FILE_SYSTEM) {
                require(destinationPath != sourcePath && !destinationPath.startsWith(sourcePath.trimEnd('/') + "/") &&
                    (allowSiblingCopy || File(destinationPath, source.displayName).canonicalPath != sourcePath)) { "A source cannot be copied into itself" }
            }
        }
        destinations.filter { it.location.kind == AfLocationKind.DOCUMENT }.forEach { destination ->
            val target = resolve(destination.location, write = true) ?: error("Document destination is unavailable")
            if (sourceNode != null && sourceNode.uri.authority == target.uri.authority) {
                require(!sameDocument(sourceNode.uri, target.uri)) { "A source cannot be copied into itself" }
                if (sourceNode.directory) require(!isChildDocument(sourceNode, target)) {
                    "A source cannot be copied into itself"
                }
                children(target.uri)[source.displayName]?.let { projected ->
                    require(allowSiblingCopy || !sameDocument(sourceNode.uri, projected.uri)) { "A source cannot replace itself" }
                }
            }
        }
    }

    suspend fun validateDestinationSet(destinations: List<AfDestinationRef>) {
        if (destinations.none { it.location.kind == AfLocationKind.DOCUMENT }) return
        val nodes = destinations.map { if (it.location.kind == AfLocationKind.DOCUMENT) resolve(it.location, write = true) else null }
        val paths = destinations.mapIndexed { index, destination -> when (destination.location.kind) {
            AfLocationKind.DOCUMENT -> nodes[index]?.let { physicalPath(it.uri) }
            AfLocationKind.LOCAL -> File(destination.location.path).canonicalPath
            AfLocationKind.REMOTE -> null
        } }
        for (i in destinations.indices) for (j in i + 1 until destinations.size) {
            val a = nodes[i]; val b = nodes[j]; val x = paths[i]; val y = paths[j]
            if (a == null && b == null) continue
            if (x != null && y != null) require(x != y && !x.startsWith(y.trimEnd('/') + "/") && !y.startsWith(x.trimEnd('/') + "/")) {
                "Destination folders overlap"
            }
            if (a != null && b != null && a.uri.authority == b.uri.authority) require(
                !sameDocument(a.uri, b.uri) && !isChildDocument(a, b) && !isChildDocument(b, a)
            ) { "Destination folders overlap" }
        }
    }

    suspend fun hash(location: AfLocationRef, operation: OperationContext?): String {
        val source = resolve(location) ?: error("Document file is unavailable")
        require(!source.directory) { "A folder cannot be content-verified" }
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(source.uri)?.use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                currentCoroutineContext().ensureActive(); operation?.checkpoint()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        } ?: error("Document cannot be opened")
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    suspend fun materialize(entry: AfEnumeratedEntry, destination: File, operation: OperationContext) {
        val source = resolve(entry.snapshot.location) ?: error("Document file is unavailable")
        require(!source.directory && source.size == entry.snapshot.sizeBytes) { "Document source changed" }
        resolver.openInputStream(source.uri)?.use { input ->
            FileOutputStream(destination).use { output ->
                val buffer = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive(); operation.checkpoint()
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count > 0) {
                        require(count <= entry.snapshot.sizeBytes - total) { "Document source grew while copying" }
                        output.write(buffer, 0, count); total += count
                        operation.progress(byteDelta = count.toLong(), currentName = source.name)
                    }
                }
                require(total == entry.snapshot.sizeBytes) { "Document source changed while copying" }
                output.fd.sync()
            }
        } ?: error("Document cannot be opened")
    }

    suspend fun createDirectory(location: AfLocationRef) {
        resolve(location, write = true)?.let { require(it.directory) { "Destination is a file" }; return }
        val folder = resolve(parent(location), write = true) ?: error("Document parent is unavailable")
        val name = exactLeafName(location.documentPath.substringAfterLast('/'))
        val created = DocumentsContract.createDocument(resolver, folder.uri, Document.MIME_TYPE_DIR, name)
            ?: error("Document folder could not be created")
        mutated(created, folder.uri, added = node(created))
        require(node(created)?.let { it.directory && it.name == name } == true) { "Provider changed the destination name" }
    }

    suspend fun install(source: File, destination: AfLocationRef, replace: Boolean, operation: OperationContext) {
        require(!replace) { "Document replacement requires the shared recovery backup" }
        require(resolve(destination, write = true) == null) { "Document destination already exists" }
        val folder = resolve(parent(destination), write = true) ?: error("Document parent is unavailable")
        val name = exactLeafName(destination.documentPath.substringAfterLast('/'))
        // createDocument owns a NEW object, unlike provider rename which may replace
        // an existing name. An unfinished copy may be visible, but never clobbers
        // an existing destination or permits source deletion before verification.
        val owned = DocumentsContract.createDocument(resolver, folder.uri, "application/octet-stream", name)
            ?: error("Document destination could not be created")
        val ownedName = node(owned)?.name
        var published = false
        var writtenIdentity: StructStat? = null
        try {
            val descriptor = resolver.openFileDescriptor(owned, "wt") ?: error("Document destination cannot be opened")
            ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { output ->
              try {
                writtenIdentity = Os.fstat(descriptor.fileDescriptor)
                require(ownedName == name) { "Provider changed the destination name to avoid a conflict" }
                source.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER)
                    val expected = source.length()
                    var written = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive(); operation.checkpoint()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count > 0) {
                            require(count <= expected - written) { "Private staged file grew while copying" }
                            output.write(buffer, 0, count); written += count
                        }
                    }
                    require(written == expected) { "Private staged file changed while copying" }
                    output.flush()
                    if (writtenIdentity?.let { OsConstants.S_ISREG(it.st_mode) } == true) output.fd.sync()
                }
              } finally { writtenIdentity = runCatching { Os.fstat(descriptor.fileDescriptor) }.getOrNull() }
            }
            mutated(owned, folder.uri, added = node(owned))
            require(stat(destination)?.sizeBytes == source.length() && hash(destination, operation) == fileHash(source)) {
                "Document copy verification failed"
            }
            published = true
        } finally {
            if (!published) withContext(NonCancellable) {
                val expected = writtenIdentity
                val removed = runCatching {
                    require(expected != null && OsConstants.S_ISREG(expected.st_mode)) { "Provider cannot prove failed-copy ownership" }
                    resolver.openFileDescriptor(owned, "r")?.use { descriptor ->
                        val actual = Os.fstat(descriptor.fileDescriptor)
                        require(OsConstants.S_ISREG(actual.st_mode) && actual.st_dev == expected.st_dev && actual.st_ino == expected.st_ino &&
                            actual.st_size == expected.st_size && actual.st_mtime == expected.st_mtime) { "Failed destination changed; it was retained" }
                        check(DocumentsContract.deleteDocument(resolver, owned)) { "Failed copy cleanup did not complete" }
                    } ?: error("Failed copy is unavailable for identity checking")
                }.isSuccess
                mutated(owned, folder.uri, removedName = if (removed) ownedName else null)
                if (!removed) throw AfDocumentRecoveryException(folder.uri.toString(), IllegalStateException("Incomplete copy was retained"))
            }
        }
    }

    suspend fun rename(from: AfLocationRef, to: AfLocationRef) {
        require(from.path == to.path && parent(from) == parent(to)) { "Document rename must stay in its granted parent" }
        val source = resolve(from, write = true, fresh = true) ?: error("Rename source is unavailable")
        require(resolve(to, write = true, fresh = true) == null) { "Rename destination already exists" }
        val name = exactLeafName(to.documentPath.substringAfterLast('/'))
        val renamed = DocumentsContract.renameDocument(resolver, source.uri, name) ?: error("Document rename failed")
        mutated(source.uri); mutated(renamed)
        clearIndexes() // Renamed folders may have provider-specific descendant IDs.
        require(node(renamed)?.name == name) { "Provider changed the rename destination" }
    }

    suspend fun delete(location: AfLocationRef, recursive: Boolean) {
        val source = resolve(location, write = true) ?: return
        val parentNode = resolve(parent(location), write = true) ?: error("Document parent is unavailable")
        if (source.directory && !recursive) {
            require(children(source.uri, fresh = true).isEmpty()) { "Source folder gained new children; it was retained" }
            val native = physicalPath(source.uri)?.let(::File)
            if (native != null && native.parentFile?.canWrite() == true && native.canWrite()) {
                require(!java.nio.file.Files.isSymbolicLink(native.toPath())) { "Document source changed type" }
                java.nio.file.Files.delete(native.toPath()) // Existing native authority only; atomic empty-directory removal.
            } else AfDocumentEmptyDirectoryRemoval(context).remove(source.uri, parentNode.uri)
        } else check(DocumentsContract.deleteDocument(resolver, source.uri)) { "Document deletion failed" }
        mutated(source.uri, parentNode.uri, removedName = source.name)
        indexes.remove(source.uri.toString())?.let { indexBytes -= it.bytes; indexNodes -= it.names.size }
    }

    private fun mutated(uri: Uri, parentUri: Uri? = null, removedName: String? = null, added: Node? = null) {
        parentUri?.let { parent -> indexes[parent.toString()]?.let { index ->
            removedName?.let { name -> index.names.remove(name)?.let { old ->
                val bytes = nodeBytes(old); index.bytes -= bytes; indexBytes -= bytes; indexNodes--
            } }
            added?.let { value ->
                index.names.remove(value.name)?.let { old -> val bytes = nodeBytes(old); index.bytes -= bytes; indexBytes -= bytes; indexNodes-- }
                val bytes = nodeBytes(value)
                if (indexBytes + bytes > NORMAL_INDEX_BYTES || indexNodes >= MAX_CHILDREN) clearIndexes()
                else { index.names[value.name] = value; index.bytes += bytes; indexBytes += bytes; indexNodes++ }
            }
        } }
        check(changed.size < AfWorkflowLimits.MAX_PLANNED_ENTRIES || uri.toString() in changed) { "Document mutation limit reached" }
        changed += uri.toString()
    }

    suspend fun close() {
        clearIndexes()
        try { if (changed.isNotEmpty()) com.affilemanager.app.thumbnail.ThumbnailInvalidation.documents(changed.toList()) }
        finally { changed.clear() }
    }

    private fun nodeBytes(node: Node) = 256L + node.name.length * 2L + node.uri.toString().length * 2L
    private fun clearIndexes() { indexes.clear(); indexBytes = 0; indexNodes = 0 }

    private suspend fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) { currentCoroutineContext().ensureActive(); val count = input.read(buffer); if (count < 0) break; if (count > 0) digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun sameDocument(a: Uri, b: Uri) = a.authority == b.authority &&
        DocumentsContract.getDocumentId(a) == DocumentsContract.getDocumentId(b)

    private suspend fun isChildDocument(parent: Node, child: Node): Boolean {
        if (Build.VERSION.SDK_INT >= 29) return DocumentsContract.isChildDocument(resolver, parent.uri, child.uri)
        val parentPath = physicalPath(parent.uri)
        val childPath = physicalPath(child.uri)
        if (parentPath != null && childPath != null) return childPath.startsWith(parentPath.trimEnd('/') + "/")
        val parentId = DocumentsContract.getDocumentId(parent.uri)
        val childId = DocumentsContract.getDocumentId(child.uri)
        fun path(uri: Uri): List<String>? = try {
            DocumentsContract.findDocumentPath(resolver, uri)?.path?.let {
                AfDocumentAncestryRules.verifiedPath(it, DocumentsContract.getDocumentId(uri))
            }
        } catch (_: UnsupportedOperationException) { null }
        catch (_: java.io.FileNotFoundException) { null }
        AfDocumentAncestryRules.pathRelation(parentId, childId, path(child.uri))?.let { return it }
        return AfDocumentAncestryRules.reachable(parent, childId,
            id = { DocumentsContract.getDocumentId(it.uri) }, directory = { it.directory },
            estimate = { 256L + it.name.length * 2L + it.uri.toString().length * 2L },
            children = { node, remaining -> children(node.uri, fresh = true, maximumRows = minOf(MAX_CHILDREN, remaining)).values })
    }

    /** Identity comparison only. This never authorizes native IO through a document grant. */
    private fun physicalPath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = DocumentsContract.getDocumentId(uri)
        if (':' !in id) return null
        val volume = id.substringBefore(':')
        if (!volume.matches(Regex("[A-Za-z0-9-]{1,128}"))) return null
        val relative = id.substringAfter(':')
        validateRelativePath(relative)
        val root = when {
            volume.equals("primary", true) -> android.os.Environment.getExternalStorageDirectory()
            volume.equals("home", true) -> android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS)
            else -> File("/storage/$volume")
        }
        return File(root, relative).canonicalPath
    }

    private companion object {
        val COLUMNS = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        const val MAX_CHILDREN = 50_000
        const val MAX_ENTRIES = 100_000
        const val MAX_DEPTH = 64
        const val MAX_INDEX_BYTES = 8L * 1_024 * 1_024
        const val NORMAL_INDEX_BYTES = MAX_INDEX_BYTES * 3 / 4
        const val BUFFER = 256 * 1_024
    }
}
