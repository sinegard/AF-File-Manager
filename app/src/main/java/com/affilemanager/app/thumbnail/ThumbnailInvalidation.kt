package com.affilemanager.app.thumbnail

import java.io.File

/** One process-owned subscriber, also used by repositories created by background workers. */
internal object ThumbnailInvalidation {
    @Volatile private var localListener: suspend (List<File>) -> Unit = {}
    @Volatile private var documentListener: suspend (List<String>) -> Unit = {}
    @Volatile private var directoryListener: () -> Unit = {}

    fun install(local: suspend (List<File>) -> Unit, documents: suspend (List<String>) -> Unit, opened: () -> Unit) {
        localListener = local
        documentListener = documents
        directoryListener = opened
    }

    suspend fun local(paths: List<File>) { localListener(paths) }
    suspend fun documents(uris: List<String>) { documentListener(uris) }
    fun directoryOpened() { directoryListener() }
}
