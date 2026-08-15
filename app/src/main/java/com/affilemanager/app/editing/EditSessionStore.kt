package com.affilemanager.app.editing

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

class EditSessionStore(cacheDirectory: File) {
    private val root = File(cacheDirectory, "edit-sessions")
    private class OriginChangedDuringSave(val revision: FileRevision?) : IllegalStateException()

    init {
        // Editable copies are session-scoped. Remove anything left behind by a
        // process death before accepting a new editing session.
        resetRoot()
    }

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

    fun readText(session: EditSession): String = readTextDocument(session).text

    fun readTextDocument(session: EditSession, forcedEncoding: TextEncoding? = null): TextDocument {
        require(session.workingFile.length() <= EditLimits.MAX_TEXT_BYTES) { "File is too large for the built-in editor" }
        val output = ByteArrayOutputStream(session.workingFile.length().toInt().coerceAtLeast(8_192))
        session.workingFile.inputStream().buffered().use { input ->
            copyBounded(input, output, EditLimits.MAX_TEXT_BYTES.toLong(), null)
        }
        val bytes = output.toByteArray()
        return try {
            TextDocumentCodec.decode(bytes, forcedEncoding)
        } catch (error: Throwable) {
            throw IllegalArgumentException("Text could not be decoded using the selected encoding", error)
        } finally {
            bytes.fill(0)
        }
    }

    fun stageText(session: EditSession, text: String): EditSession {
        return stageText(session, TextDocument(text, TextEncoding.UTF8, LineEnding.LF))
    }

    fun stageText(
        session: EditSession,
        text: String,
        encoding: TextEncoding,
        lineEnding: LineEnding,
    ): EditSession = stageText(session, TextDocument(text, encoding, lineEnding))

    private fun stageText(session: EditSession, document: TextDocument): EditSession {
        val bytes = TextDocumentCodec.encode(document)
        try {
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

    fun saveLocalAs(
        session: EditSession,
        directoryPath: String,
        requestedName: String,
        policy: EditExistingPolicy,
    ): EditSaveAsResult {
        val directory = File(directoryPath).canonicalFile
        require(directory.isDirectory && directory.canWrite()) { "Destination folder is not writable" }
        val safeName = EditDestinationRules.validateFileName(requestedName)
        val requestedTarget = containedChild(directory, safeName)
        require(!requestedTarget.isDirectory) { "A folder already uses this name" }

        if (policy == EditExistingPolicy.ASK && requestedTarget.exists()) {
            return EditSaveAsResult.Conflict(localDestinationConflict(requestedTarget))
        }

        val target = when (policy) {
            EditExistingPolicy.KEEP_BOTH -> saveLocalWithUniqueName(session, directory, safeName)
            EditExistingPolicy.REPLACE -> {
                atomicReplace(requestedTarget) { output -> copyWorking(session, output) }
                requestedTarget
            }
            EditExistingPolicy.ASK -> {
                if (!atomicCreate(requestedTarget) { output -> copyWorking(session, output) }) {
                    return EditSaveAsResult.Conflict(localDestinationConflict(requestedTarget))
                }
                requestedTarget
            }
        }
        val verified = revisionOf(target)
        require(session.workingRevision.hasSameContent(verified)) { "Saved file verification failed" }
        return EditSaveAsResult.Saved(
            destination = EditDestination.Local(target.absolutePath),
            revision = verified,
        )
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

    fun rebaseOrigin(
        session: EditSession,
        destination: EditDestination,
        savedRevision: FileRevision,
    ): EditSession {
        val current = revisionOf(session.workingFile)
        require(current.hasSameContent(savedRevision)) { "Saved destination does not match the editable copy" }
        val origin = when (destination) {
            is EditDestination.Local -> EditOrigin.Local(destination.path, canWrite = true)
            is EditDestination.Content -> EditOrigin.Content(destination.uri, canWrite = true)
            is EditDestination.Remote -> EditOrigin.Remote(
                profileId = destination.profileId,
                connectionName = destination.connectionName,
                path = destination.path,
            )
        }
        return session.copy(
            displayName = sanitizeName(destination.displayName),
            origin = origin,
            originRevision = savedRevision,
            workingRevision = current,
            lastSavedRevision = current,
        )
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

    fun discard(session: EditSession?): Boolean {
        if (session == null) return true
        val directory = runCatching { requireSessionDirectory(session) }.getOrNull() ?: return false
        if (!directory.exists()) return true
        return directory.deleteRecursively()
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

    private fun atomicCreate(target: File, write: (OutputStream) -> Unit): Boolean {
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
            return try {
                try {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath())
                }
                true
            } catch (_: FileAlreadyExistsException) {
                false
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun saveLocalWithUniqueName(session: EditSession, directory: File, requestedName: String): File {
        val reserved = (directory.list() ?: throw IllegalStateException("Destination folder could not be read"))
            .also { require(it.size <= MAX_DESTINATION_ENTRIES) { "Destination folder contains too many entries" } }
            .mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
        for (attempt in 0 until MAX_NAME_ATTEMPTS) {
            val candidateName = keepBothName(requestedName, attempt)
            if (candidateName.lowercase(Locale.ROOT) in reserved) continue
            val candidate = containedChild(directory, candidateName)
            if (atomicCreate(candidate) { output -> copyWorking(session, output) }) return candidate
            reserved += candidateName.lowercase(Locale.ROOT)
        }
        throw IllegalStateException("A free destination name could not be found")
    }

    private fun localDestinationConflict(target: File): EditSaveAsConflict {
        require(target.isFile && target.canRead()) { "Existing destination is not a readable file" }
        val revision = revisionOf(target)
        return EditSaveAsConflict(
            destination = EditDestination.Local(target.absolutePath),
            existing = EditDestinationSnapshot(
                sizeBytes = revision.sizeBytes,
                modifiedAtMillis = revision.modifiedAtMillis,
                sha256 = revision.sha256,
            ),
        )
    }

    private fun copyWorking(session: EditSession, output: OutputStream) {
        session.workingFile.inputStream().buffered().use { input -> input.copyTo(output, DEFAULT_BUFFER_SIZE) }
    }

    private fun containedChild(directory: File, name: String): File {
        val safeName = EditDestinationRules.validateFileName(name)
        val parent = directory.canonicalFile
        val child = File(parent, safeName).canonicalFile
        require(child.parentFile == parent) { "Destination path escaped the selected folder" }
        return child
    }

    private fun keepBothName(name: String, attempt: Int): String {
        if (attempt == 0) return name
        val dot = name.lastIndexOf('.')
        val hasExtension = dot > 0 && dot < name.lastIndex
        val stem = if (hasExtension) name.substring(0, dot) else name
        val extension = if (hasExtension) name.substring(dot) else ""
        val suffix = " ($attempt)$extension"
        return stem.take((255 - suffix.length).coerceAtLeast(1)) + suffix
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

    private companion object {
        const val MAX_DESTINATION_ENTRIES = 100_000
        const val MAX_NAME_ATTEMPTS = 10_000
    }
}
