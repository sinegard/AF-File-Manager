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
    constructor()

    @Keep
    constructor(@Suppress("UNUSED_PARAMETER") context: Context)

    override fun getFileSystemService(): IBinder = FileSystemManager.getService()

    override fun getProcessUid(): Int = Process.myUid()

    override fun destroy() {
        System.exit(0)
    }
}

@Keep
class RootFileService : RootService() {
    override fun onBind(intent: Intent): IBinder = FileSystemManager.getService()
}
