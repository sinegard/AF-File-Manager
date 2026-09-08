package com.affilemanager.app.apk

import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import com.affilemanager.app.ui.theme.AfSurface as Surface
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.affilemanager.app.ui.localization.LText
import com.affilemanager.app.ui.theme.AFFileManagerTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import java.io.File

/** Android alone verifies certificates, package/version compatibility and asks for installation consent. */
class SplitApkInstallActivity : ComponentActivity() {
    @Volatile private var sessionId = -1
    private var stagingJob: Job? = null
    private val message = mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = savedInstanceState?.getInt("session", -1) ?: intent.getIntExtra("expected_session", -1)
        setContent {
            val appearance by (application as com.affilemanager.app.AFFileManagerApplication).graph.appearance.settings.collectAsStateWithLifecycle()
            AFFileManagerTheme(settings = appearance) { com.affilemanager.app.ui.theme.AppearancePage(Modifier.fillMaxSize()) {
            Column(Modifier.padding(24.dp)) {
                LText("APK rinkinys")
                message.value?.let { LText(it) } ?: CircularProgressIndicator()
                TextButton(onClick = { cancelAndFinish() }) { LText("Uždaryti") }
            }
        } } }
        if (intent.action == RESULT) handleResult(intent)
        else if (savedInstanceState != null) {
            // Never resume a half-written install after process death. The user can retry
            // from the original archive; Android remains responsible for committed sessions.
            val session = packageManager.packageInstaller.getSessionInfo(sessionId)
            if (session != null && !session.isSealed) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
            message.value = "Diegti nepavyko"
        } else stagingJob = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(stagingLock.tryLock()) { "Diegti nepavyko" }
                    try {
                    val archive = File(requireNotNull(intent.getStringExtra("archive")))
                    val plan = SplitApkArchive.plan(archive)
                    val selected = intent.getStringArrayListExtra("parts").orEmpty().toSet()
                    val parts = plan.parts.filter { it.name in selected }
                    require(plan.base in parts && parts.size == selected.size) { "Netinkamas APK rinkinys" }
                    val installer = packageManager.packageInstaller
                    val parameters = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                        setSize(parts.sumOf { it.bytes })
                        if (android.os.Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                    }
                    ensureActive()
                    sessionId = installer.createSession(parameters)
                    ensureActive()
                    val stage = File(cacheDir, "split-apk-install").apply { check(isDirectory || mkdirs()) }
                    // This lock owns staging, so only abandoned private part files are swept.
                    stage.listFiles()?.filter { it.isFile && it.name.startsWith("part-") && it.extension == "apk" }?.forEach { it.delete() }
                    val temp = File.createTempFile("part-", ".apk", stage)
                    try {
                        installer.openSession(sessionId).use { session ->
                            parts.forEachIndexed { index, part ->
                                ensureActive()
                                SplitApkArchive.copyPart(archive, part, temp, checkActive = { ensureActive() })
                                session.openWrite("part-$index.apk", 0, part.bytes).use { output ->
                                    temp.inputStream().use { input ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            output.write(buffer, 0, count)
                                        }
                                    }
                                    session.fsync(output)
                                }
                            }
                            ensureActive()
                            val callback = Intent(this@SplitApkInstallActivity, SplitApkInstallActivity::class.java)
                                .setAction(RESULT).putExtra("expected_session", sessionId)
                                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            val pending = PendingIntent.getActivity(this@SplitApkInstallActivity, sessionId, callback,
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
                            session.commit(pending.intentSender)
                        }
                    } finally { temp.delete() }
                    } finally { stagingLock.unlock() }
                }
            } catch (error: Exception) {
                if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
                if (error is kotlinx.coroutines.CancellationException) throw error
                message.value = error.message ?: "Diegti nepavyko"
            }
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleResult(intent) }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("session", sessionId); super.onSaveInstanceState(outState) }
    private fun handleResult(result: Intent) {
        if (result.action != RESULT || result.getIntExtra("expected_session", -2) != sessionId) return
        when (result.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirmation = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmation != null) runCatching { startActivity(confirmation) }.onFailure { message.value = "Diegti nepavyko" }
                else message.value = "Diegti nepavyko"
            }
            PackageInstaller.STATUS_SUCCESS -> finish()
            else -> message.value = result.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(512) ?: "Diegti nepavyko"
        }
    }
    private fun cancelAndFinish() {
        stagingJob?.cancel()
        if (sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
        finish()
    }
    override fun onDestroy() {
        if (isFinishing) stagingJob?.cancel()
        if (isFinishing && sessionId >= 0) runCatching { packageManager.packageInstaller.abandonSession(sessionId) }
        super.onDestroy()
    }
    companion object {
        private const val RESULT = "com.affilemanager.app.SPLIT_INSTALL_RESULT"
        private val stagingLock = Mutex()
    }
}
