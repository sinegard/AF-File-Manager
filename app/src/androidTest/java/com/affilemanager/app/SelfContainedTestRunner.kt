package com.affilemanager.app

import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.test.runner.AndroidJUnitRunner
import java.io.File

/** Restores the permissions and optional media fixture after Orchestrator clears app data. */
class SelfContainedTestRunner : AndroidJUnitRunner() {
    override fun onStart() {
        try {
            val packageName = targetContext.packageName
            shell("appops set $packageName MANAGE_EXTERNAL_STORAGE allow")
            val appOp = shell("appops get $packageName MANAGE_EXTERNAL_STORAGE")
            check(appOp.contains("allow", ignoreCase = true)) {
                "MANAGE_EXTERNAL_STORAGE app-op was not granted: ${appOp.trim()}"
            }
            copyOptionalVideoFixture()
        } catch (error: Throwable) {
            throw IllegalStateException("Connected test setup failed: ${error.message}", error)
        }
        super.onStart()
    }

    private fun copyOptionalVideoFixture() {
        val source = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AFFileManagerTest/video-gesture.mp4",
        )
        if (!source.isFile || source.length() <= 0L) return
        val target = File(requireNotNull(targetContext.getExternalFilesDir("validation")), source.name)
        source.copyTo(target, overwrite = true)
        check(target.isFile && target.length() == source.length()) { "Video fixture copy was incomplete" }
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
