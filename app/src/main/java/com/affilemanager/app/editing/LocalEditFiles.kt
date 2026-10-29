package com.affilemanager.app.editing

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.affilemanager.app.advanced.PrivilegedFileMetadata
import com.affilemanager.app.advanced.PrivilegedMetadataOperations
import java.io.Closeable
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.OutputStream

internal const val LOCAL_EDIT_ACCESS_ERROR = "Failo prieigos teisių išlaikyti nepavyko"

/** The Android implementation is the default; host doubles exist only in src/test. */
internal interface LocalEditFiles {
    fun metadata(file: File): PrivilegedFileMetadata?
    fun openStage(file: File): LocalEditStage
}

internal interface LocalEditStage : Closeable {
    val output: OutputStream
    fun prepare(original: PrivilegedFileMetadata?): PrivilegedFileMetadata
    fun requireNamed(expected: PrivilegedFileMetadata)
}

internal object AndroidLocalEditFiles : LocalEditFiles {
    // O_CLOEXEC is supported by the API26 kernel; its SDK field became public in27.
    private const val CLOSE_ON_EXEC = 0x80000

    override fun metadata(file: File): PrivilegedFileMetadata? = localCall {
        val named = try { Os.lstat(file.path) }
        catch (error: ErrnoException) { if (error.errno == OsConstants.ENOENT) return@localCall null else throw error }
        check(OsConstants.S_ISREG(named.st_mode))
        val fd = Os.open(file.path,OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or
            OsConstants.O_NONBLOCK or CLOSE_ON_EXEC,0)
        try {
            val opened = PrivilegedMetadataOperations.readOpenEntry(fd)
            val after = Os.lstat(file.path)
            check(opened.device == named.st_dev && opened.inode == named.st_ino &&
                opened.device == after.st_dev && opened.inode == after.st_ino && OsConstants.S_ISREG(after.st_mode))
            opened
        } finally { Os.close(fd) }
    }

    override fun openStage(file: File): LocalEditStage = localCall {
        val fd=Os.open(file.path,OsConstants.O_RDWR or OsConstants.O_CREAT or OsConstants.O_EXCL or
            OsConstants.O_NOFOLLOW or CLOSE_ON_EXEC,0x180)
        try { AndroidStage(file,fd) }
        catch (error: Throwable) {
            runCatching { discardOwned(file,fd) }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { if(fd.valid())Os.close(fd) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private class AndroidStage(private val file: File,private val fd: FileDescriptor) : LocalEditStage {
        private val initial=PrivilegedMetadataOperations.readOpenEntry(fd)
        override val output=FileOutputStream(fd)
        private var closed=false

        override fun prepare(original: PrivilegedFileMetadata?): PrivilegedFileMetadata = localCall {
            check(!closed)
            output.flush()
            if(original != null) PrivilegedMetadataOperations.applyOpenEntry(fd,original)
            else Os.fsync(fd)
            PrivilegedMetadataOperations.readOpenEntry(fd).also { current ->
                check(initial.sameEntry(current) && (original == null || original.sameSecurity(current)))
            }
        }

        override fun requireNamed(expected: PrivilegedFileMetadata) = localCall {
            check(!closed)
            val current=checkNotNull(metadata(file))
            check(expected.sameEntry(current) && expected.sameSecurity(current))
        }

        override fun close() {
            if(closed)return
            closed=true
            var failure: Throwable?=null
            fun attempt(action:()->Unit) {
                try { action() } catch(error:Throwable) {
                    if(failure==null)failure=error else failure!!.addSuppressed(error)
                }
            }
            attempt { discardOwned(file,fd) }
            attempt { output.close() }
            // Android's FileOutputStream(FileDescriptor) does not own every supplied FD.
            attempt { if(fd.valid())Os.close(fd) }
            failure?.let { throw IllegalStateException(LOCAL_EDIT_ACCESS_ERROR,it) }
        }
    }

    private fun discardOwned(file: File,fd: FileDescriptor) {
        val owned=Os.fstat(fd)
        val named=try { Os.lstat(file.path) }
        catch(error:ErrnoException) { if(error.errno==OsConstants.ENOENT)return else throw error }
        // A changed name belongs to somebody else. Never follow/delete that replacement.
        if(OsConstants.S_ISREG(named.st_mode) && owned.st_dev==named.st_dev && owned.st_ino==named.st_ino) check(file.delete())
    }

    private inline fun <T> localCall(block:()->T):T = try { block() }
    catch(error:Exception) { throw IllegalStateException(LOCAL_EDIT_ACCESS_ERROR,error) }
}
