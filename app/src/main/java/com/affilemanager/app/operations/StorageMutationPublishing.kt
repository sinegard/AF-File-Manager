package com.affilemanager.app.operations

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/** Publishes only paths this operation changed, including a committed partial result. */
internal suspend fun <T> publishingStorageChanges(
    onMutation: suspend (List<File>) -> Unit,
    changedPaths: () -> List<File>,
    block: suspend () -> T,
): T {
    var primaryFailure: Throwable? = null
    try {
        return block()
    } catch (error: Throwable) {
        primaryFailure = error
        throw error
    } finally {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val paths = changedPaths()
                if (paths.isNotEmpty()) onMutation(paths)
            } catch (indexFailure: Throwable) {
                val failure = primaryFailure
                if (failure != null) failure.addSuppressed(indexFailure)
                else throw java.io.IOException("Could not update Android's file index", indexFailure)
            }
        }
    }
}
