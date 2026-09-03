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

    override fun destroy() {
        terminalHost.closeAll()
        System.exit(0)
    }
}

@Keep
class RootFileService : RootService() {
    private val terminalHost = PrivilegedTerminalHost()
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

        override fun destroy() = terminalHost.closeAll()
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onDestroy() {
        terminalHost.closeAll()
        super.onDestroy()
    }
}
