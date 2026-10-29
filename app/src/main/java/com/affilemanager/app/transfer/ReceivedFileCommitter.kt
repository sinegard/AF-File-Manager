package com.affilemanager.app.transfer

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Storage boundary for a fully received, synced file. */
class ReceivedFileCommitter internal constructor(private val moveFile: (File, File) -> Unit) {
    constructor() : this({ source, target -> Files.move(source.toPath(), target.toPath()); Unit })

    internal fun commit(
        staging: File,
        target: File,
        expectedBytes: Long,
        checkpoint: () -> Unit,
        onFallback: (IOException) -> Unit,
    ) = commitWithCopyDiagnostics(staging, target, expectedBytes, checkpoint, onFallback, {})

    internal fun commitWithCopyDiagnostics(
        staging: File,
        target: File,
        expectedBytes: Long,
        checkpoint: () -> Unit,
        onFallback: (IOException) -> Unit,
        onCopyPhase: (TransferPhase) -> Unit,
    ) {
        require(expectedBytes >= 0 && staging != target)
        require(staging.parentFile.canonicalFile == target.parentFile.canonicalFile)
        checkpoint()
        requireCompleteStage(staging, expectedBytes)
        try {
            // Keep the fast path. No REPLACE_EXISTING: known collisions are errors.
            moveFile(staging, target)
            return
        } catch (renameFailure: IOException) {
            if (renameFailure is FileAlreadyExistsException) throw renameFailure
            // A failed move has unspecified side effects. Never adopt or overwrite
            // a destination it may have created, even if its length looks right.
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) throw renameFailure
            try {
                requireCompleteStage(staging, expectedBytes)
            } catch (invalidStage: IOException) {
                renameFailure.addSuppressed(invalidStage)
                throw renameFailure
            }
            checkpoint()
            onFallback(renameFailure)
            try {
                copyNewAndVerify(staging, target, expectedBytes, checkpoint, onCopyPhase)
            } catch (copyFailure: Exception) {
                copyFailure.addSuppressed(renameFailure)
                throw copyFailure
            }
        }
        // The caller owns staging cleanup. A cleanup/notification failure must
        // not turn an already verified destination into a failed upload retry.
    }

    private fun requireCompleteStage(staging: File, expectedBytes: Long) {
        val attributes = Files.readAttributes(staging.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        if (!attributes.isRegularFile || attributes.size() != expectedBytes) throw IOException(TransferFailure.STORAGE.code)
    }

    private fun copyNewAndVerify(staging: File, target: File, expectedBytes: Long, checkpoint: () -> Unit,
        onCopyPhase: (TransferPhase) -> Unit) {
        val buffer = ByteArray(NearbyTransferTuning.IO_BUFFER_BYTES)
        var created = false
        var targetKey: Any? = null
        var verified = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            onCopyPhase(TransferPhase.COMMIT_COPY_OPEN)
            FileChannel.open(target.toPath(), CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { output ->
                created = true
                targetKey = attributes(target).fileKey()
                onCopyPhase(TransferPhase.COMMIT_COPY_READ)
                Files.newInputStream(staging.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                    var remaining = expectedBytes
                    while (remaining > 0) {
                        checkpoint()
                        onCopyPhase(TransferPhase.COMMIT_COPY_READ)
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count <= 0) throw IOException(TransferFailure.STORAGE.code)
                        digest.update(buffer, 0, count)
                        val bytes = ByteBuffer.wrap(buffer, 0, count)
                        onCopyPhase(TransferPhase.COMMIT_COPY_WRITE)
                        while (bytes.hasRemaining()) {
                            checkpoint()
                            if (output.write(bytes) <= 0) throw IOException(TransferFailure.STORAGE.code)
                        }
                        remaining -= count
                        checkpoint()
                    }
                    if (input.read() != -1) throw IOException(TransferFailure.STORAGE.code)
                }
                onCopyPhase(TransferPhase.COMMIT_COPY_SYNC)
                output.force(true)
            }
            checkpoint()
            onCopyPhase(TransferPhase.COMMIT_COPY_VERIFY)
            val writtenDigest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(target.toPath(), READ, NOFOLLOW_LINKS).use { input ->
                var remaining = expectedBytes
                while (remaining > 0) {
                    checkpoint()
                    val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (count <= 0) throw IOException(TransferFailure.STORAGE.code)
                    writtenDigest.update(buffer, 0, count)
                    remaining -= count
                }
                if (input.read() != -1) throw IOException(TransferFailure.STORAGE.code)
            }
            val current = attributes(target)
            if (!current.isRegularFile || current.size() != expectedBytes || current.fileKey() != targetKey ||
                !MessageDigest.isEqual(digest.digest(), writtenDigest.digest())) throw IOException(TransferFailure.STORAGE.code)
            checkpoint()
            verified = true
        } catch (failure: Exception) {
            if (created && !verified && targetKey != null) {
                try {
                    // Delete only the inode created here, not a concurrent replacement.
                    if (attributes(target).fileKey() == targetKey) Files.delete(target.toPath())
                } catch (cleanupFailure: IOException) {
                    failure.addSuppressed(cleanupFailure)
                }
            }
            throw failure
        } finally {
            buffer.fill(0)
        }
    }

    private fun attributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
}
