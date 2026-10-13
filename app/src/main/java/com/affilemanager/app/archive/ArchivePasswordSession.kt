package com.affilemanager.app.archive

import java.io.File
import java.io.IOException

class ArchivePasswordRequiredException : IOException("Archyvui reikia slaptažodžio")

/** One preview only. No preferences, serialization, logs or clipboard ownership. */
class ArchivePasswordSession {
    private data class Revision(val path: String, val size: Long, val modified: Long)
    private var revision: Revision? = null
    private var secret: CharArray? = null
    private fun revision(file: File) = Revision(file.absolutePath, file.length(), file.lastModified())

    @Synchronized fun remember(file: File, password: CharArray) {
        clear()
        require(password.isNotEmpty() && password.size <= 1024) { "Netinkamas slaptažodis" }
        revision = revision(file)
        secret = password.copyOf()
    }

    @Synchronized fun borrow(file: File): CharArray? {
        val requested = revision(file)
        if (revision?.path != requested.path) return null
        if (revision != requested) { clear(); return null }
        return secret?.copyOf()
    }

    @Synchronized fun rebindAfterOwnedMutation(file: File) { if (secret != null) revision = revision(file) }
    @Synchronized fun clear() { secret?.fill('\u0000'); secret = null; revision = null }
}
