package com.affilemanager.app.transfer

import java.io.File
import java.io.InputStream
import java.io.OutputStream

/** An app-owned destination, never a peer-provided URI or absolute path. */
interface LanReceiveStorage {
    val label: String
    fun list(relativeDirectory: String): List<LanSharedEntry>
    fun read(relativePath: String): LanSharedRead
    fun createDirectory(relativePath: String)
    /** Captures one immutable target before this file starts writing. */
    fun directory(relativeDirectory: String, fileName: String, routeByType: Boolean): LanReceiveDirectory
}

data class LanSharedEntry(val name: String, val directory: Boolean, val size: Long)
data class LanSharedRead(val name: String, val size: Long, val input: InputStream)

sealed interface LanReceiveDirectory {
    data class Local(val root: File, val directory: File) : LanReceiveDirectory
    fun interface Documents : LanReceiveDirectory {
        fun open(name: String): LanDocumentUpload
    }
}

/** Publication must be create-new and verified; closing only removes an unchanged owned stage. */
interface LanDocumentUpload : AutoCloseable {
    val output: OutputStream
    fun sync()
    fun commit(expectedSize: Long, checkpoint: () -> Unit): LanPublishedDocument
}

data class LanPublishedDocument(val uri: String, val name: String, val modifiedAtMillis: Long)
