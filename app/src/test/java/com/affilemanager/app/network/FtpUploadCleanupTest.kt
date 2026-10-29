package com.affilemanager.app.network

import kotlinx.coroutines.runBlocking
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

class FtpUploadCleanupTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun failedPartialCleanupDoesNotHideThePublicationFailureOrLoseTheRestoredOriginal() = runBlocking {
        val files = linkedMapOf("/target.txt" to "old".toByteArray())
        val ftp = object : FTPClient() {
            override fun getReplyCode() = 226
            override fun sendNoOp() = true
            override fun completePendingCommand() = true
            override fun storeFileStream(path: String): OutputStream = object : ByteArrayOutputStream() {
                override fun close() { files[path] = toByteArray(); super.close() }
            }
            override fun mlistFile(path: String): FTPFile? = files[path]?.let { bytes ->
                FTPFile().apply { name = path.substringAfterLast('/'); size = bytes.size.toLong(); type = FTPFile.FILE_TYPE }
            }
            override fun listFiles(path: String): Array<FTPFile> = files.keys.mapNotNull(::mlistFile).toTypedArray()
            override fun rename(from: String, to: String): Boolean {
                if (from.startsWith("/.af-partial-")) throw IOException("publication denied")
                check(!files.containsKey(to))
                files[to] = checkNotNull(files.remove(from))
                return true
            }
            override fun deleteFile(path: String): Boolean {
                if (path.startsWith("/.af-partial-")) throw IOException("cleanup denied")
                return files.remove(path) != null
            }
        }
        val client = FtpRemoteClient::class.java.getDeclaredConstructor(FTPClient::class.java)
            .apply { isAccessible = true }.newInstance(ftp)
        val source = temporary.newFile("source.txt").apply { writeText("new") }
        val failure = runCatching { client.upload(source, "/target.txt") }.exceptionOrNull()
        assertEquals("publication denied", failure?.message)
        // Coroutine stack recovery may wrap/copy IOException across Dispatchers.IO;
        // the original and its suppressed cleanup error remain in its cause chain.
        assertTrue("Cleanup failure missing from ${failure!!.stackTraceToString()}",
            generateSequence(failure as Throwable) { it.cause }.take(8)
                .flatMap { it.suppressed.asSequence() }.any { it.message == "cleanup denied" })
        assertEquals("old", files.getValue("/target.txt").toString(Charsets.UTF_8))
        assertEquals("new", source.readText())
    }
}
