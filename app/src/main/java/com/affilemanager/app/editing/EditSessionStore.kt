package com.affilemanager.app.editing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.UUID

class EditSessionStore(cacheDirectory: File) {
    private val root = File(cacheDirectory, "edit-sessions")
    private class OriginChangedDuringSave(val revision: FileRevision?) : IllegalStateException()

    fun prepareFromFile(
        sourceKey: String,
        displayName: String,
        mimeType: String,
        sourceFile: File,
        origin: EditOrigin,
        modifiedAtMillis: Long?,
        internalTextEditor: Boolean,
    ): EditSession {
        require(sourceFile.isFile && sourceFile.canRead()) { "Source file is not readable" }
        val beforeSize = sourceFile.length().coerceAtLeast(0)
        val beforeModified = sourceFile.lastModified().takeIf { it > 0 } ?: modifiedAtMillis
        require(beforeSize <= EditLimits.MAX_FILE_BYTES) { "File is too large to edit" }
        val session = prepare(
            sourceKey = sourceKey,
            displayName = displayName,
            mimeType = mimeType,
            origin = origin,
            expectedSizeBytes = beforeSize,
            modifiedAtMillis = beforeModified,
            internalTextEditor = internalTextEditor,
            openSource = sourceFile::inputStream,
        )
        val afterSize = sourceFile.length().coerceAtLeast(0)
        val afterModified = sourceFile.lastModified().takeIf { it > 0 } ?: modifiedAtMillis
        require(beforeSize == afterSize && beforeModified == afterModified) {
            discard(session)
            "Source changed while the editable copy was being prepared"
        }
        return session
    }

    fun prepareFromStream(
        sourceKey: String,
        displayName: String,
        mimeType: String,
        origin: EditOrigin,
        expectedSizeBytes: Long?,
        modifiedAtMillis: Long?,
        internalTextEditor: Boolean,
        openSource: () -> InputStream,
    ): EditSession = prepare(
        sourceKey = sourceKey,
        displayName = displayName,
        mimeType = mimeType,
        origin = origin,
        expectedSizeBytes = expectedSizeBytes,
        modifiedAtMillis = modifiedAtMillis,
        internalTextEditor = internalTextEditor,
        openSource = openSource,
    )

    fun readText(session: EditSession): String {
        require(session.workingFile.length() <= EditLimits.MAX_TEXT_BYTES) { "File is too large for the built-in editor" }
        val output = ByteArrayOutputStream(session.workingFile.length().toInt().coerceAtLeast(8_192))
        session.workingFile.inputStream().buffered().use { input ->
            copyBounded(input, output, EditLimits.MAX_TEXT_BYTES.toLong(), null)
        }
        val bytes = output.toByteArray()
        return try {
            try {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            } catch (_: CharacterCodingException) {
                throw IllegalArgumentException("Failas nėra tinkamas UTF-8 tekstas; naudokite kitą redaktorių")
            }
        } finally {
            bytes.fill(0)
        }
    }

    fun stageText(session: EditSession, text: String): EditSession {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try {
            require(bytes.size <= EditLimits.MAX_TEXT_BYTES) { "Text exceeds the 2 MB built-in editor limit" }
            atomicReplace(session.workingFile) { output -> output.write(bytes) }
        } finally {
            bytes.fill(0)
        }
        return refreshWorking(session)
    }

    fun refreshWorking(session: EditSession): EditSession {
        require(session.workingFile.isFile && session.workingFile.canRead()) { "Editable copy is no longer available" }
        val revision = revisionOf(session.workingFile)
        return session.copy(workingRevision = revision)
    }

    fun currentLocalRevision(path: String): FileRevision? {
        val file = File(path)
        if (!file.exists()) return null
        require(file.isFile && file.canRead()) { "Original file is not readable" }
        return revisionOf(file)
    }

    fun saveLocal(session: EditSession, force: Boolean): EditSaveResult {
        val origin = session.origin as? EditOrigin.Local ?: error("Edit session is not local")
        val target = File(origin.path)
        val current = currentLocalRevision(origin.path)
        if (!force && !session.originRevision.hasSameContent(current)) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, current))
        }
        require(origin.canWrite && (target.canWrite() || (!target.exists() && target.parentFile?.canWrite() == true))) {
            "Original file is read-only; use Save as"
        }
        try {
            atomicReplace(
                target = target,
                beforeReplace = {
                    if (!force) {
                        val latest = currentLocalRevision(origin.path)
                        if (!session.originRevision.hasSameContent(latest)) throw OriginChangedDuringSave(latest)
                    }
                },
            ) { output ->
                session.workingFile.inputStream().buffered().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            }
        } catch (changed: OriginChangedDuringSave) {
            return EditSaveResult.Conflict(EditConflict(origin.label, session.originRevision, changed.revision))
        }
        val verified = revisionOf(target)
        require(session.workingRevision.hasSameContent(verified)) { "Saved file verification failed" }
        return EditSaveResult.Saved(verified)
    }

    fun revisionFromStream(modifiedAtMillis: Long?, openSource: () -> InputStream): FileRevision {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        openSource().buffered().use { input ->
            val sink = object : OutputStream() {
                override fun write(value: Int) {
                    digest.update(value.toByte())
                    size += 1
                }

                override fun write(buffer: ByteArray, offset: Int, length: Int) {
                    digest.update(buffer, offset, length)
                    size = Math.addExact(size, length.toLong())
                }
            }
            copyBounded(input, sink, EditLimits.MAX_FILE_BYTES, null)
        }
        return FileRevision(size, modifiedAtMillis, digest.hex())
    }

    fun writeWorkingCopy(session: EditSession, openDestination: () -> OutputStream): FileRevision {
        openDestination().buffered().use { output ->
            session.workingFile.inputStream().buffered().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
            output.flush()
        }
        return session.workingRevision
    }

    fun markOriginSaved(session: EditSession, originRevision: FileRevision): EditSession = session.copy(
        originRevision = originRevision,
        workingRevision = revisionOf(session.workingFile),
        lastSavedRevision = revisionOf(session.workingFile),
    )

    fun markSavedElsewhere(session: EditSession): EditSession {
        val current = revisionOf(session.workingFile)
        return session.copy(workingRevision = current, lastSavedRevision = current)
    }

    fun verificationFile(session: EditSession): File {
        val directory = requireSessionDirectory(session)
        return File(directory, "verify-${UUID.randomUUID()}.tmp").also { candidate ->
            requireContained(directory, candidate)
            if (candidate.exists()) require(candidate.delete()) { "Could not reset verification file" }
        }
    }

    fun discardVerification(file: File?) {
        if (file == null) return
        val canonicalRoot = root.canonicalFile
        val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return
        if (canonicalFile.toPath().startsWith(canonicalRoot.toPath()) && canonicalFile.isFile) canonicalFile.delete()
    }

    fun discard(session: EditSession?) {
        if (session == null) return
        val directory = runCatching { requireSessionDirectory(session) }.getOrNull() ?: return
        directory.deleteRecursively()
    }

    private fun prepare(
        sourceKey: String,
        displayName: String,
        mimeType: String,
        origin: EditOrigin,
        expectedSizeBytes: Long?,
        modifiedAtMillis: Long?,
        internalTextEditor: Boolean,
        openSource: () -> InputStream,
    ): EditSession {
        expectedSizeBytes?.let {
            require(it in 0..EditLimits.MAX_FILE_BYTES) { "File is too large to edit" }
        }
        resetRoot()
        require(root.mkdirs() || root.isDirectory) { "Could not create the edit cache" }
        val requiredSpace = Math.addExact(expectedSizeBytes ?: EditLimits.MIN_FREE_BYTES, EditLimits.MIN_FREE_BYTES)
        require(root.usableSpace >= requiredSpace) { "Not enough free space for a safe editable copy" }

        val id = UUID.randomUUID().toString()
        val directory = File(root, id)
        requireContained(root, directory)
        require(directory.mkdir()) { "Could not create the edit session" }
        val safeName = sanitizeName(displayName)
        val working = File(directory, safeName)
        requireContained(directory, working)
        val digest = MessageDigest.getInstance("SHA-256")
        var copied = 0L
        try {
            openSource().buffered().use { input ->
                working.outputStream().buffered().use { output ->
                    copied = copyBounded(input, output, EditLimits.MAX_FILE_BYTES, digest)
                }
            }
            if (expectedSizeBytes != null) require(copied == expectedSizeBytes) {
                "Source size changed while the editable copy was being prepared"
            }
            val revision = FileRevision(copied, modifiedAtMillis, digest.hex())
            working.setLastModified(modifiedAtMillis ?: System.currentTimeMillis())
            return EditSession(
                id = id,
                sourceKey = sourceKey,
                displayName = safeName,
                mimeType = mimeType,
                workingFile = working,
                origin = origin,
                originRevision = revision,
                workingRevision = revision,
                lastSavedRevision = revision,
                usesInternalTextEditor = internalTextEditor,
            )
        } catch (error: Throwable) {
            directory.deleteRecursively()
            throw error
        }
    }

    private fun resetRoot() {
        if (root.exists()) root.deleteRecursively()
    }

    private fun revisionOf(file: File): FileRevision {
        require(file.length() <= EditLimits.MAX_FILE_BYTES) { "File is too large to edit" }
        return revisionFromStream(file.lastModified().takeIf { it > 0 }, file::inputStream)
    }

    private fun atomicReplace(
        target: File,
        beforeReplace: () -> Unit = {},
        write: (OutputStream) -> Unit,
    ) {
        val parent = requireNotNull(target.parentFile) { "Target has no parent folder" }
        require(parent.isDirectory) { "Target folder is not available" }
        val temporary = File(parent, ".${target.name}.af-edit-${UUID.randomUUID()}.tmp")
        requireContained(parent, temporary)
        try {
            temporary.outputStream().use { output ->
                write(output)
                output.flush()
                output.fd.sync()
            }
            beforeReplace()
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun requireSessionDirectory(session: EditSession): File {
        val directory = File(root, session.id).canonicalFile
        requireContained(root, directory)
        require(session.workingFile.canonicalFile.toPath().startsWith(directory.toPath())) { "Invalid edit session path" }
        return directory
    }

    private fun requireContained(parent: File, child: File) {
        val parentPath = parent.canonicalFile.toPath()
        val childPath = child.canonicalFile.toPath()
        require(childPath.startsWith(parentPath) && childPath != parentPath) { "Edit cache path escaped its boundary" }
    }

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\p{Cc}\\p{Cf}/\\\\]"), "_").trim().take(220)
        return cleaned.ifBlank { "editable-file" }
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long,
        digest: MessageDigest?,
    ): Long {
        val buffer = ByteArray(64 * 1_024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total = Math.addExact(total, read.toLong())
            require(total <= maxBytes) { "File is too large to edit" }
            output.write(buffer, 0, read)
            digest?.update(buffer, 0, read)
        }
        return total
    }

    private fun MessageDigest.hex(): String = digest().joinToString("") { "%02x".format(it) }
}
