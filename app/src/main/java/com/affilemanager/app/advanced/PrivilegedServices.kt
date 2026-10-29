package com.affilemanager.app.advanced

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import androidx.annotation.Keep
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager

@Keep
class ShizukuFileService : IPrivilegedFileService.Stub {
    private val terminalHost = PrivilegedTerminalHost()
    private val writeHost = PrivilegedWriteHost()

    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getFileSystemService(): IBinder = FileSystemManager.getService()

    override fun getProcessUid(): Int = Process.myUid()

    override fun openTerminal(workingDirectory: String, rows: Int, columns: Int): Long =
        terminalHost.open(workingDirectory, rows, columns)

    override fun readTerminal(handle: Long, destination: ByteArray): Int = terminalHost.read(handle, destination)

    override fun writeTerminal(handle: Long, source: ByteArray, offset: Int, length: Int): Int =
        terminalHost.write(handle, source, offset, length)

    override fun resizeTerminal(handle: Long, rows: Int, columns: Int) =
        terminalHost.resize(handle, rows, columns)

    override fun closeTerminal(handle: Long) = terminalHost.close(handle)

    override fun listRunningAppMemory(): Array<String> = PrivilegedAppProcessTools.listRunningAppMemory()

    override fun forceStopPackage(packageName: String): Boolean = PrivilegedAppProcessTools.forceStopPackage(packageName)

    override fun getFileMetadataVersion(): Int = PrivilegedFileMetadata.VERSION
    override fun readFileMetadata(path: String): Array<String> = PrivilegedMetadataOperations.read(path)
    override fun applyFileMetadata(path: String, metadata: Array<String>) = PrivilegedMetadataOperations.apply(path, metadata)
    override fun createPrivateEntry(path: String, directory: Boolean) = PrivilegedMetadataOperations.create(path, directory)
    override fun openPrivateWrite(path: String): Long = writeHost.open(path)
    override fun writePrivateFile(handle: Long, source: ByteArray, length: Int): Int = writeHost.write(handle, source, length)
    override fun finishPrivateWrite(handle: Long) = writeHost.finish(handle)
    override fun abortPrivateWrite(handle: Long) = writeHost.abort(handle)
    override fun getFileReadVersion(): Int = PrivilegedFileServiceProtocol.READ_VERSION
    override fun openReadOnlyFile(path: String): android.os.ParcelFileDescriptor = PrivilegedMetadataOperations.openReadOnly(path)

    override fun destroy() {
        terminalHost.closeAll()
        writeHost.closeAll()
        System.exit(0)
    }
}

@Keep
class RootFileService : RootService() {
    private val terminalHost = PrivilegedTerminalHost()
    private val writeHost = PrivilegedWriteHost()
    private val binder = object : IPrivilegedFileService.Stub() {
        override fun getFileSystemService(): IBinder = FileSystemManager.getService()

        override fun getProcessUid(): Int = Process.myUid()

        override fun openTerminal(workingDirectory: String, rows: Int, columns: Int): Long =
            terminalHost.open(workingDirectory, rows, columns)

        override fun readTerminal(handle: Long, destination: ByteArray): Int = terminalHost.read(handle, destination)

        override fun writeTerminal(handle: Long, source: ByteArray, offset: Int, length: Int): Int =
            terminalHost.write(handle, source, offset, length)

        override fun resizeTerminal(handle: Long, rows: Int, columns: Int) =
            terminalHost.resize(handle, rows, columns)

        override fun closeTerminal(handle: Long) = terminalHost.close(handle)

        override fun listRunningAppMemory(): Array<String> = PrivilegedAppProcessTools.listRunningAppMemory()

        override fun forceStopPackage(packageName: String): Boolean = PrivilegedAppProcessTools.forceStopPackage(packageName)

        override fun getFileMetadataVersion(): Int = PrivilegedFileMetadata.VERSION
        override fun readFileMetadata(path: String): Array<String> = PrivilegedMetadataOperations.read(path)
        override fun applyFileMetadata(path: String, metadata: Array<String>) = PrivilegedMetadataOperations.apply(path, metadata)
        override fun createPrivateEntry(path: String, directory: Boolean) = PrivilegedMetadataOperations.create(path, directory)
        override fun openPrivateWrite(path: String): Long = writeHost.open(path)
        override fun writePrivateFile(handle: Long, source: ByteArray, length: Int): Int = writeHost.write(handle, source, length)
        override fun finishPrivateWrite(handle: Long) = writeHost.finish(handle)
        override fun abortPrivateWrite(handle: Long) = writeHost.abort(handle)
        override fun getFileReadVersion(): Int = PrivilegedFileServiceProtocol.READ_VERSION
        override fun openReadOnlyFile(path: String): android.os.ParcelFileDescriptor = PrivilegedMetadataOperations.openReadOnly(path)

        override fun destroy() { terminalHost.closeAll(); writeHost.closeAll() }
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        terminalHost.closeAll()
        writeHost.closeAll()
        super.onDestroy()
    }
}
