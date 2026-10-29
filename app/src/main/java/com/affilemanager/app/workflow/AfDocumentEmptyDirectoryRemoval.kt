package com.affilemanager.app.workflow

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.system.Os
import android.system.OsConstants
import com.affilemanager.app.data.RemovableStorageAccess
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AfDocumentRecoveryException(val recoveryUri: String, cause: Throwable) : java.io.IOException("Document recovery is required", cause)

/** Provider deleteDocument is recursive; it is NEVER used for move/undo directory cleanup. */
internal class AfDocumentEmptyDirectoryRemoval(private val context: Context) {
    private val resolver = context.contentResolver
    private val records = File(context.filesDir, "af-document-recovery-v1")

    suspend fun remove(source: Uri, parent: Uri) = recoveryMutex.withLock { removeLocked(source, parent) }

    private fun removeLocked(source: Uri, parent: Uri) {
        require(Build.VERSION.SDK_INT in 26..29 && source.authority == AUTHORITY) {
            "This provider cannot safely remove an empty source folder; the folder was retained"
        }
        val sourceId = DocumentsContract.getDocumentId(source)
        val volume = sourceId.substringBefore(':')
        val grant = resolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission &&
            RemovableStorageAccess.belongsToVolume(it.uri.toString(), volume, rootOnly = true) }
            ?: error("A full removable-volume grant is needed for safe empty-folder removal; the folder was retained")
        val provider = context.packageManager.resolveContentProvider(AUTHORITY, 0) ?: error("Storage provider is unavailable")
        require(provider.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0) { "Untrusted storage provider" }
        val external = context.getExternalFilesDirs(null).filterNotNull().singleOrNull {
            it.absolutePath == "/storage/$volume/Android/data/${context.packageName}/files"
        } ?: error("Same-volume private recovery storage is unavailable; the folder was retained")
        val relative = sourceId.substringAfter(':', "")
        validateRelativePath(relative)
        require(relative.isNotEmpty() && '\\' !in relative) { "The volume root cannot be removed" }
        require(records.isDirectory || records.mkdirs()) { "Recovery journal is unavailable" }
        require((records.list()?.size ?: error("Recovery journal cannot be read")) < MAX_RECORDS) { "Unresolved document recovery records must be restored first" }
        val id = UUID.randomUUID().toString()
        val container = File(external, "af-workflow-recovery/$id")
        require(container.mkdirs()) { "Recovery folder could not be created" }
        val name = exactLeafName(relative.substringAfterLast('/'))
        val native = File(container, name)
        val containerId = "$volume:Android/data/${context.packageName}/files/af-workflow-recovery/$id"
        val containerUri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, containerId)
        val heldUri = DocumentsContract.buildDocumentUriUsingTree(grant.uri, "$containerId/$name")
        val guardName = ".af-empty-$id"
        val nonce = ByteArray(32).also { SecureRandom().nextBytes(it) }
        var guard: Uri? = null
        val record = File(records, "$id.json")
        val json = JSONObject().put("schemaVersion", 2).put("source", source.toString()).put("parent", parent.toString())
            .put("held", heldUri.toString()).put("path", native.absolutePath).put("guard", guardName)
            .put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
        var journaled = false
        try {
            guard = DocumentsContract.createDocument(resolver, source, "application/octet-stream", guardName)
                ?: error("Empty-folder identity guard could not be created")
            require(displayName(guard) == guardName) { "Provider changed the identity guard name" }
            resolver.openOutputStream(guard, "wt")?.use { it.write(nonce); it.flush() } ?: error("Identity guard cannot be written")
            writeRecord(record, json); journaled = true
            val moved = DocumentsContract.moveDocument(resolver, source, parent, containerUri) ?: error("Source folder could not be moved to recovery")
            require(DocumentsContract.getDocumentId(moved) == DocumentsContract.getDocumentId(heldUri)) { "Provider changed the recovery destination" }
            verifyGuard(File(native, guardName), nonce)
            val attributes = Os.lstat(native.absolutePath)
            require(OsConstants.S_ISDIR(attributes.st_mode)) { "Recovery object changed type" }
            // FUSE inode numbers may change during provider moves. Pin only the new, guarded location.
            json.put("heldDev", attributes.st_dev).put("heldIno", attributes.st_ino)
            writeRecord(record, json)
            Files.delete(File(native, guardName).toPath())
            Files.delete(native.toPath()) // Atomic rmdir. New/unselected children make this fail, never recurse.
            check(record.delete()) { "Recovery record could not be cleared" }
            container.delete()
        } catch (error: Throwable) {
            if (journaled) {
                try { restore(record, json) }
                catch (restoreError: Throwable) {
                    error.addSuppressed(restoreError)
                    throw AfDocumentRecoveryException(containerUri.toString(), error)
                }
            } else {
                guard?.let { owned -> runCatching { DocumentsContract.deleteDocument(resolver, owned) } }
                container.delete()
            }
            throw error
        } finally { nonce.fill(0) }
    }

    suspend fun recoverPending(volume: String? = null) = recoveryMutex.withLock { recoverLocked(volume) }

    private fun recoverLocked(volume: String?) {
        if (!records.isDirectory) return
        val files = records.listFiles() ?: error("Recovery journal cannot be read")
        require(files.size <= MAX_RECORDS * 2) { "Document recovery record limit exceeded" }
        files.forEach { require(it.name.matches(Regex("[a-f0-9-]{36}\\.json(\\.tmp)?")) && it.length() in 1..16_384) { "Invalid document recovery record" } }
        files.groupBy { it.name.removeSuffix(".tmp") }.values.forEach { versions ->
            val record = versions.firstOrNull { !it.name.endsWith(".tmp") } ?: versions.single()
            val json = JSONObject(record.readText())
            if (volume != null && DocumentsContract.getDocumentId(Uri.parse(json.getString("held"))).substringBefore(':') != volume) return@forEach
            versions.filter { it != record }.forEach { other ->
                val candidate = JSONObject(other.readText())
                require(listOf("schemaVersion", "source", "parent", "held", "path", "guard", "nonce").all { candidate.get(it) == json.get(it) }) {
                    "Recovery journal versions disagree; data was retained"
                }
            }
            restore(record, json)
            versions.filter { it != record }.forEach { check(it.delete()) { "Obsolete recovery journal could not be removed" } }
        }
    }

    private fun restore(record: File, json: JSONObject) {
        require(json.getInt("schemaVersion") == 2) { "Unsupported document recovery record; data was retained" }
        val source = Uri.parse(json.getString("source")); val parent = Uri.parse(json.getString("parent")); val held = Uri.parse(json.getString("held"))
        require(listOf(source, parent, held).all { it.authority == AUTHORITY }) { "Invalid recovery provider" }
        val native = File(json.getString("path"))
        val root = context.getExternalFilesDirs(null).filterNotNull().map { File(it, "af-workflow-recovery").canonicalFile }
            .singleOrNull { native.canonicalFile.toPath().startsWith(it.toPath()) } ?: error("Recovery path escaped private storage")
        val id = record.name.removeSuffix(".tmp").removeSuffix(".json")
        require(native.parentFile?.parentFile?.canonicalFile == root && native.parentFile?.name == id) { "Invalid recovery path" }
        val guardName = json.getString("guard")
        require(guardName == ".af-empty-$id") { "Invalid identity guard" }
        val nonce = android.util.Base64.decode(json.getString("nonce"), android.util.Base64.NO_WRAP)
        require(nonce.size == 32) { "Invalid identity guard length" }
        try {
            val attributes = try { Files.readAttributes(native.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS) }
                catch (_: NoSuchFileException) { null }
            if (attributes == null) {
                val sourceGuard = DocumentsContract.buildDocumentUriUsingTree(source, "${DocumentsContract.getDocumentId(source)}/$guardName")
                if (!json.has("heldIno")) resolver.openInputStream(sourceGuard)?.use { input ->
                    require(MessageDigest.isEqual(nonce, input.readBytesBounded())) { "Source identity guard changed" }
                    check(DocumentsContract.deleteDocument(resolver, sourceGuard)) { "Identity guard cleanup failed" }
                }
                check(record.delete()) { "Recovery record could not be cleared" }; native.parentFile?.delete(); return
            }
            require(attributes.isDirectory && !attributes.isSymbolicLink) { "Recovery identity changed" }
            val guardFile = File(native, guardName)
            if (Files.exists(guardFile.toPath(), NOFOLLOW_LINKS)) verifyGuard(guardFile, nonce)
            else {
                val current = Os.lstat(native.absolutePath)
                require(json.has("heldIno") && current.st_dev == json.getLong("heldDev") && current.st_ino == json.getLong("heldIno")) { "Recovery identity changed" }
            }
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                var count = 0
                while (cursor.moveToNext()) {
                    require(++count <= 50_000) { "Recovery parent contains too many entries" }
                    require(!native.name.equals(cursor.getString(0), ignoreCase = true)) { "Original name is occupied; recovery data was retained" }
                }
            } ?: error("Original name could not be checked")
            val heldParent = DocumentsContract.buildDocumentUriUsingTree(held, DocumentsContract.getDocumentId(held).substringBeforeLast('/'))
            val restored = DocumentsContract.moveDocument(resolver, held, heldParent, parent) ?: error("Recovery restore failed")
            require(DocumentsContract.getDocumentId(restored) == DocumentsContract.getDocumentId(source)) { "Recovery restore changed the name" }
            val sourceGuard = DocumentsContract.buildDocumentUriUsingTree(source, "${DocumentsContract.getDocumentId(source)}/$guardName")
            if (displayName(sourceGuard) != null) {
                resolver.openInputStream(sourceGuard)?.use { require(MessageDigest.isEqual(nonce, it.readBytesBounded())) { "Restored identity guard changed" } }
                    ?: error("Restored identity guard cannot be read")
                check(DocumentsContract.deleteDocument(resolver, sourceGuard)) { "Restored identity guard cleanup failed" }
            }
            check(record.delete()) { "Recovery record could not be cleared" }; native.parentFile?.delete()
        } finally { nonce.fill(0) }
    }

    private fun writeRecord(file: File, json: JSONObject) {
        val temporary = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(temporary).use { it.write(json.toString().toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun displayName(uri: Uri): String? = resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
        ?.use { if (it.moveToFirst()) it.getString(0) else null }
    private fun verifyGuard(file: File, expected: ByteArray) {
        Files.newInputStream(file.toPath(), NOFOLLOW_LINKS).use { require(MessageDigest.isEqual(expected, it.readBytesBounded())) { "Recovery identity guard changed" } }
    }
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val bytes = ByteArray(33); var count = 0
        while (count < bytes.size) { val read = read(bytes, count, bytes.size - count); if (read < 0) break; if (read > 0) count += read }
        return bytes.copyOf(count)
    }
    private companion object {
        const val AUTHORITY = "com.android.externalstorage.documents"
        const val MAX_RECORDS = 128
        val recoveryMutex = Mutex()
    }
}
