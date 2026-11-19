package com.affilemanager.app.transfer

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Small private ownership ledger, not an unbounded transfer history or a scan by filename. */
internal class NearbyReceiveStageJournal(private val context: Context, private val warning: (String) -> Unit) {
    private val file = AtomicFile(File(context.filesDir, "nearby-receive-stages.json"))
    companion object {
        private val lock = Any()
        private val active = hashSetOf<String>()
        private const val LIMIT = 64
        private const val MAX_BYTES = 1_048_576
    }
    private fun read(): MutableList<JSONObject> {
        if (!file.baseFile.exists()) return mutableListOf()
        require(file.baseFile.length() <= MAX_BYTES) { "Document recovery is required" }
        val rows = JSONArray(file.openRead().bufferedReader().use { it.readText() })
        require(rows.length() <= LIMIT) { "Document recovery is required" }
        return MutableList(rows.length()) { rows.getJSONObject(it) }
    }
    private fun save(rows: List<JSONObject>) {
        val bytes = JSONArray(rows).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "Document recovery is required" }
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }
    fun reserve(parent: Uri, name: String): String = synchronized(lock) {
        val rows = read()
        require(rows.size < LIMIT) { "Document recovery is required" }
        require(parent.toString().length <= 4_096 && name.matches(Regex("\\.af-nearby-[a-f0-9-]{36}\\.partial")))
        val id = java.util.UUID.randomUUID().toString()
        rows += JSONObject().put("id", id).put("parent", parent.toString()).put("name", name)
        save(rows); active += id
        id
    }
    fun witness(id: String, uri: Uri, identity: StructStat, sha256: String? = null) = synchronized(lock) {
        val rows = read()
        val row = rows.single { it.getString("id") == id }
        require(uri.toString().length <= 4_096)
        row.put("uri", uri.toString()).put("dev", identity.st_dev).put("ino", identity.st_ino)
            .put("size", identity.st_size).put("mtime", identity.st_mtime).put("regular", OsConstants.S_ISREG(identity.st_mode))
        if (sha256 != null) {
            require(sha256.matches(Regex("[a-f0-9]{64}")))
            row.put("sha256", sha256)
            externalDocumentIdentity(Uri.parse(row.getString("parent")), uri, row.getString("name"))?.let {
                row.put("externalDocument", it)
            }
        }
        save(rows)
    }
    fun forget(id: String) = synchronized(lock) {
        val rows = read(); rows.removeAll { it.getString("id") == id }; save(rows); active -= id
    }
    fun release(id: String) = synchronized(lock) { active -= id; Unit }

    fun removeOwned(id: String) {
        val row = synchronized(lock) { read().single { it.getString("id") == id } }
        // Hash large documents outside the metadata lock: independent receive workers
        // must not become one serial copy queue. The active ID excludes recovery.
        removeOwned(row)
        forget(id)
    }

    /** Never delete a changed object, a folder, an unknown witness or a currently active stage. */
    fun recover() {
        val pending = synchronized(lock) {
            read().filter { it.getString("id") !in active }.also { rows -> rows.forEach { active += it.getString("id") } }
        }
        var retained = false
        for (row in pending) {
            val id = row.getString("id")
            try {
                if (runCatching { removeOwned(row) }.isSuccess) forget(id) else retained = true
            } finally { release(id) }
        }
        if (retained) warning("Document recovery is required")
    }

    private fun removeOwned(row: JSONObject) {
        val resolver = context.contentResolver
        val parent = Uri.parse(row.getString("parent"))
        val grant = resolver.persistedUriPermissions.firstOrNull {
            it.isReadPermission && it.isWritePermission && it.uri.authority == parent.authority &&
                DocumentsContract.getTreeDocumentId(it.uri) == DocumentsContract.getTreeDocumentId(parent)
        } ?: error("Document access was revoked; select the folder again")
        if (parent.authority == "com.android.externalstorage.documents" && missingFromExistingTree(grant.uri, parent, row.getString("name"))) return
        val target = row.optString("uri").takeIf(String::isNotBlank)?.let(Uri::parse)
            ?: findChild(parent, row.getString("name")) ?: return
        val cursor = resolver.query(target, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?: error("Document file is unavailable")
        val name = cursor.use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        require(name == row.getString("name") && row.optBoolean("regular"))
        resolver.openFileDescriptor(target, "r")?.use { descriptor ->
            val current = Os.fstat(descriptor.fileDescriptor)
            val hash = row.optString("sha256").takeIf { it.matches(Regex("[a-f0-9]{64}")) }
            val sameView = current.st_dev == row.getLong("dev")
            // Removable ExternalStorageProvider grants may switch between FUSE and
            // raw-volume READ descriptors after real OS re-consent. Only that exact
            // volume/document, same inode/metadata AND original payload hash qualify.
            val sameExternalDocument = hash != null && row.has("externalDocument") &&
                externalDocumentIdentity(parent, target, name) == row.getString("externalDocument")
            // The raw FAT view rounds to two seconds, whereas an already-open FUSE
            // descriptor may retain odd seconds. This allowance is ONLY cross-view,
            // exact-document and hash-proven, never a generic timestamp tolerance.
            val sameTime = current.st_mtime == row.getLong("mtime") || (!sameView && sameExternalDocument &&
                current.st_mtime / 2 == row.getLong("mtime") / 2)
            require(OsConstants.S_ISREG(current.st_mode) && (sameView || sameExternalDocument) && current.st_ino == row.getLong("ino") &&
                current.st_size == row.getLong("size") && sameTime)
            if (hash != null) {
                require(current.st_size in 0..LanHttpServer.MAX_UPLOAD_BYTES)
                val digest = MessageDigest.getInstance("SHA-256")
                ParcelFileDescriptor.AutoCloseInputStream(ParcelFileDescriptor.dup(descriptor.fileDescriptor)).use { input ->
                    val bytes = ByteArray(128 * 1_024)
                    var total = 0L
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException()
                        val count = input.read(bytes)
                        if (count < 0) break
                        require(count <= current.st_size - total)
                        digest.update(bytes, 0, count); total += count
                    }
                    require(total == current.st_size)
                }
                require(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == hash) {
                    "Failed destination changed; it was retained"
                }
                val after = Os.fstat(descriptor.fileDescriptor)
                require(after.st_dev == current.st_dev && after.st_ino == current.st_ino && after.st_size == current.st_size &&
                    after.st_mtime == current.st_mtime) { "Failed destination changed; it was retained" }
            }
            check(DocumentsContract.deleteDocument(resolver, target))
        } ?: error("Document file is unavailable")
    }

    private fun findChild(parent: Uri, name: String): Uri? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
        val cursor = context.contentResolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null) ?: error("Document file is unavailable")
        return cursor.use {
            var scanned = 0
            var found: Uri? = null
            while (cursor.moveToNext()) {
                require(++scanned <= 50_000)
                if (cursor.getString(1) == name) { found = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0)); break }
            }
            found
        }
    }

    private fun externalDocumentIdentity(parent: Uri, target: Uri, name: String): String? {
        if (parent.authority != "com.android.externalstorage.documents" || target.authority != parent.authority) return null
        return runCatching {
            val tree = DocumentsContract.getTreeDocumentId(parent)
            val volume = tree.substringBefore(':')
            require(volume.matches(Regex("[a-fA-F0-9]{4}-[a-fA-F0-9]{4}")))
            require(DocumentsContract.getTreeDocumentId(target) == tree)
            val parentId = DocumentsContract.getDocumentId(parent)
            require(parentId == tree || parentId.startsWith(if (tree.endsWith(':')) tree else "$tree/"))
            val expected = (if (parentId.endsWith(':')) parentId else "$parentId/") + name
            require(DocumentsContract.getDocumentId(target) == expected)
            expected
        }.getOrNull()
    }

    private fun missingFromExistingTree(grant: Uri, parent: Uri, name: String): Boolean {
        val tree = DocumentsContract.getTreeDocumentId(grant)
        val parentId = DocumentsContract.getDocumentId(parent)
        require(parentId == tree || parentId.startsWith(if (tree.endsWith(':')) tree else "$tree/"))
        val relative = parentId.removePrefix(tree).trimStart('/')
        val storage = com.affilemanager.app.workflow.AfDocumentStorage(context)
        return try { kotlinx.coroutines.runBlocking {
            val root = com.affilemanager.app.workflow.AfLocationRef.document(grant.toString())
            require(storage.stat(root)?.directory == true) { "Document file is unavailable" }
            val target = com.affilemanager.app.workflow.AfLocationRef.document(grant.toString(),
                listOf(relative, name).filter(String::isNotEmpty).joinToString("/"))
            storage.stat(target) == null
        } } finally { kotlinx.coroutines.runBlocking { storage.close() } }
    }
}
