package com.affilemanager.app.transfer

import android.app.Application
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.affilemanager.app.advanced.AdvancedAccessManager
import com.affilemanager.app.advanced.PrivilegedFileRepository
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.data.LocalFileRepository
import com.affilemanager.app.data.SafFileRepository
import com.affilemanager.app.model.EntryKind
import com.affilemanager.app.model.FileEntry
import com.affilemanager.app.model.SortDirection
import com.affilemanager.app.model.SortMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

/** Session-internal access only. This does not change exported providers, grants or root mode. */
internal class NearbySourceReader(
    private val app: Application,
    private val local: LocalFileRepository,
    private val saf: SafFileRepository,
    private val privileged: PrivilegedFileRepository,
    private val access: AdvancedAccessManager,
) {
    data class Node(val entry: FileEntry, val uri: String? = null, val privilegedPath: String? = null)

    internal fun documentUri(path: String): Uri? {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            val tree = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return null
            require(app.contentResolver.persistedUriPermissions.any {
                it.isReadPermission && it.uri.authority == uri.authority &&
                    runCatching { DocumentsContract.getTreeDocumentId(it.uri) == tree }.getOrDefault(false)
            }) { "Pasirinkta dokumentų vieta nepasiekiama" }
            return uri
        }
        return app.contentResolver.persistedUriPermissions.asSequence().filter {
            it.isReadPermission && it.uri.authority == "com.android.externalstorage.documents"
        }.mapNotNull { permission ->
            val tree = runCatching { DocumentsContract.getTreeDocumentId(permission.uri) }.getOrNull()
                ?: return@mapNotNull null
            NearbyDocumentPathRules.documentId(path, tree)?.let {
                DocumentsContract.buildDocumentUriUsingTree(permission.uri, it)
            }
        }.firstOrNull()
    }

    fun needsAuthorizedRead(path: String): Boolean = path.startsWith("content://") || documentUri(path) != null ||
        (access.state.value.connected && (protectedPath(path) || !File(path).canRead()))

    private fun protectedPath(path: String): Boolean = path == "/" ||
        Regex("^/storage/[^/]+/(?:[0-9]+/)?Android/(data|obb)(/|$)").containsMatchIn(path)

    suspend fun node(path: String): Node = withContext(Dispatchers.IO) {
        documentUri(path)?.let { uri ->
            val file = DocumentFile.fromTreeUri(app, uri)
                ?: throw IllegalArgumentException("Dokumentas nepasiekiamas")
            require(file.exists() && file.canRead()) { "Dokumentas nepasiekiamas" }
            return@withContext Node(FileEntry(uri.toString(), file.name.orEmpty(),
                FileSystemRules.detectKind(file.name.orEmpty(), file.type, file.isDirectory), file.length(), file.lastModified(),
                file.name?.startsWith('.') == true, true, file.canWrite()), uri = uri.toString())
        }
        require(!path.startsWith("content://")) { "Pasirinkta dokumentų vieta nepasiekiama" }
        if (access.state.value.connected && (protectedPath(path) || !File(path).canRead())) {
            val entry = privileged.readEntry(path)
            return@withContext Node(entry, privilegedPath = entry.absolutePath)
        }
        val file = File(path).absoluteFile
        require(!Files.isSymbolicLink(file.toPath())) { "Simbolinės nuorodos telefonu nesiunčiamos" }
        require(file.exists() && file.canRead() && (file.isFile || file.isDirectory)) { "Failas nepasiekiamas: ${file.name}" }
        Node(local.toEntry(file))
    }

    suspend fun children(node: Node, hidden: Boolean = true): List<Node> {
        val entries = when {
            node.uri != null -> saf.list(node.uri).getOrThrow().map { it.uri }
            node.privilegedPath != null -> privileged.list(node.privilegedPath, true, SortMode.NAME, SortDirection.ASCENDING)
                .getOrThrow().map { it.absolutePath }
            else -> withContext(Dispatchers.IO) {
                val children = ArrayList<String>()
                var characters = 0L
                Files.newDirectoryStream(File(node.entry.absolutePath).toPath()).use { stream ->
                    for (child in stream) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        require(children.size < NearbySourcePreparer.MAX_FILES + NearbySourcePreparer.MAX_DIRECTORIES) { "Siuntimo rinkinio kelių aprašas per didelis" }
                        val path = child.toString()
                        characters += path.length
                        require(characters <= NearbySourcePreparer.MAX_PATH_PAYLOAD_CHARS) { "Siuntimo rinkinio kelių aprašas per didelis" }
                        children += path
                    }
                }
                children
            }
        }
        return entries.map { this.node(it) }.filter { hidden || !it.entry.isHidden }
    }

    suspend fun roots(): List<FileEntry> = withContext(Dispatchers.IO) {
        val roots = local.roots().map { FileEntry(it.path, it.title, EntryKind.DIRECTORY, 0, 0, false, true, false) }.toMutableList()
        app.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.take(100).forEach {
            runCatching { node(it.uri.toString()) }.getOrNull()?.entry?.takeIf(FileEntry::isDirectory)?.let(roots::add)
        }
        if (access.state.value.connected) privileged.availableRoots().getOrThrow().forEach {
            roots += FileEntry(it.path, it.title, EntryKind.DIRECTORY, 0, 0, false, true, false)
        }
        roots.distinctBy(FileEntry::absolutePath)
    }

    suspend fun list(path: String, hidden: Boolean): Result<List<FileEntry>> = withContext(Dispatchers.IO) {
        runCatching { if (path.isEmpty()) roots() else children(node(path), hidden).map { it.entry } }
    }

    fun parent(path: String): String? {
        if (path.isEmpty()) return null
        if (!path.startsWith("content://")) return File(path).parent ?: ""
        val uri = Uri.parse(path)
        val tree = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return ""
        val doc = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return ""
        // ExternalStorage document IDs have a stable path. Other providers use opaque IDs;
        // Back retains the exact navigation stack and Up returns to the authorized roots.
        if (uri.authority != "com.android.externalstorage.documents") return ""
        val parent = NearbyDocumentPathRules.parentDocumentId(doc, tree) ?: return ""
        return DocumentsContract.buildDocumentUriUsingTree(uri, parent).toString()
    }
}

internal object NearbyDocumentPathRules {
    fun parentDocumentId(document: String, grantedTree: String): String? {
        val prefix = if (grantedTree.endsWith(':')) grantedTree else "$grantedTree/"
        if (document == grantedTree || !document.startsWith(prefix) || ':' !in document) return null
        val relative = document.substringAfter(':')
        if (relative.split('/').any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
        val parent = if ('/' in relative) document.substringBeforeLast('/') else document.substringBefore(':') + ":"
        return parent.takeIf { it == grantedTree || it.startsWith(prefix) }
    }

    fun documentId(path: String, grantedTree: String): String? {
        val volume = grantedTree.substringBefore(':')
        if (!Regex("[A-Za-z0-9-]{1,128}").matches(volume) || volume.equals("primary", true)) return null
        val prefix = "/storage/$volume"
        if (path != prefix && !path.startsWith("$prefix/")) return null
        val relative = path.removePrefix(prefix).removePrefix("/")
        if (relative.split('/').any { it == "." || it == ".." || it.any(Char::isISOControl) }) return null
        val root = grantedTree.substringAfter(':', "").trimEnd('/')
        if (root.isNotEmpty() && relative != root && !relative.startsWith("$root/")) return null
        return "$volume:$relative"
    }
}
