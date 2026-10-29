package com.affilemanager.app.network

import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException

/** A language-invariant diagnostic/path, not a claim that an unavailable server was recovered. */
internal class RemoteUploadRecoveryException(val recoveryPath: String, cause: Throwable) :
    IOException("AF-RECOVERY: $recoveryPath", cause)

/** FTP/SFTP cannot assume their server implements atomic replacement. Never pre-delete an original. */
internal object RemoteUploadPublication {
    suspend fun publish(
        stagedPath: String,
        targetPath: String,
        operation: OperationContext?,
        exists: suspend (String) -> Boolean,
        rename: suspend (String, String) -> Unit,
        remove: suspend (String) -> Unit,
    ) {
        suspend fun checkpoint() {
            currentCoroutineContext().ensureActive()
            operation?.checkpoint()
        }
        var backup: String? = null
        try {
            checkpoint()
            if (exists(targetPath)) {
                val reserved = RemotePath.temporarySibling(targetPath, "af-upload-backup")
                check(!exists(reserved)) { "Toks pavadinimas jau naudojamas" }
                // Record the intent before sending: a lost reply may follow a
                // completed server rename. Inspect this owned name during recovery.
                backup = reserved
                rename(targetPath, reserved)
            }
            checkpoint()
            rename(stagedPath, targetPath)
        } catch (failure: Throwable) {
            backup?.let { saved ->
                withContext(NonCancellable) {
                    try {
                        if (exists(saved)) {
                            // Another client may have created a new destination.
                            // Preserve both it and the previous version in that case.
                            check(!exists(targetPath)) { "Toks pavadinimas jau naudojamas" }
                            rename(saved, targetPath)
                        }
                    } catch (recovery: Throwable) {
                        if (recovery !== failure) failure.addSuppressed(recovery)
                        throw RemoteUploadRecoveryException(saved, failure)
                    }
                }
            }
            throw failure
        }
        backup?.let { saved ->
            // Publication is complete. A cleanup failure remains visible and the
            // recovery copy is retained; callers must not silently report success.
            try { withContext(NonCancellable) { remove(saved) } }
            catch (cleanup: Throwable) { throw RemoteUploadRecoveryException(saved, cleanup) }
        }
    }
}
