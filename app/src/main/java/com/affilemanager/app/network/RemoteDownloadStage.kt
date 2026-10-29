package com.affilemanager.app.network

import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/** Local staging ownership and publication are identical for every network protocol. */
internal class RemoteDownloadStage(private val destination: File) : Closeable {
    val file: File
    private var opened = false

    init {
        val parent = requireNotNull(destination.absoluteFile.parentFile)
        require(parent.isDirectory) { "Vietinis paskirties aplankas nepasiekiamas" }
        validateDestination()
        file = File.createTempFile(".af-download-", ".partial", parent)
    }

    fun outputStream(): OutputStream {
        check(!opened) { "Download staging has already been opened" }
        opened = true
        val channel = FileChannel.open(file.toPath(), StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
        return Channels.newOutputStream(channel).buffered()
    }

    fun publish() {
        validateDestination()
        require(!Files.isSymbolicLink(file.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
        // Do not delete a previous result before the verified replacement is ready.
        Files.move(file.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    private fun validateDestination() {
        require(!Files.isSymbolicLink(destination.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
        require(!Files.isDirectory(destination.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Esamo failo pakeisti nepavyko" }
    }

    override fun close() {
        Files.deleteIfExists(file.toPath())
    }
}
