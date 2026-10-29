package com.affilemanager.app.data

import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.TransferFailurePolicy
import com.affilemanager.app.operations.TransferVerification

/** Choices only: file paths and operation plans must never be reused as defaults. */
data class TransferOptions(
    val conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val verification: TransferVerification = TransferVerification.SIZE,
    val failurePolicy: TransferFailurePolicy = TransferFailurePolicy.STOP,
    val askBeforeTransfer: Boolean = true,
) {
    fun normalized(moving: Boolean): TransferOptions = copy(
        conflictPolicy = conflictPolicy.takeUnless { it == ConflictPolicy.ASK } ?: ConflictPolicy.KEEP_BOTH,
        failurePolicy = if (moving) TransferFailurePolicy.STOP else failurePolicy,
        // Remembering an overwrite/merge choice is not permission to apply it silently elsewhere.
        askBeforeTransfer = askBeforeTransfer || !canSkipConfirmation,
    )

    val canSkipConfirmation: Boolean
        get() = conflictPolicy == ConflictPolicy.KEEP_BOTH || conflictPolicy == ConflictPolicy.SKIP

    companion object {
        fun defaults(moving: Boolean) = TransferOptions(
            verification = if (moving) TransferVerification.SHA256 else TransferVerification.SIZE,
        )
    }
}

data class TransferPreferences(
    val copy: TransferOptions = TransferOptions.defaults(moving = false),
    val move: TransferOptions = TransferOptions.defaults(moving = true),
) {
    fun optionsFor(moving: Boolean): TransferOptions = (if (moving) move else copy).normalized(moving)

    fun withOptions(moving: Boolean, options: TransferOptions): TransferPreferences =
        if (moving) copy(move = options.normalized(true)) else copy(copy = options.normalized(false))
}
