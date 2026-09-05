package com.affilemanager.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.core.FileSystemRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.BufferedSink
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class NearbyTransferStatus { IDLE, STARTING, RUNNING, COMPLETED, CANCELLED, ERROR }

data class NearbyTransferState(
    val status: NearbyTransferStatus = NearbyTransferStatus.IDLE,
    val receiverName: String? = null,
    val fileCount: Int = 0,
    val completedFiles: Int = 0,
    val totalBytes: Long = 0,
    val sentBytes: Long = 0,
    val currentFile: String? = null,
    val message: String? = null,
    val files: List<TransferFileProgress> = emptyList(),
)

object NearbyTransferController {
    private val _state = MutableStateFlow(NearbyTransferState())
    val state: StateFlow<NearbyTransferState> = _state.asStateFlow()

    fun start(context: Context, pairing: NearbyPairing, prepared: PreparedNearbyTransfer) {
        val validatedPairing = NearbyPairing.parse(pairing.encoded())
        require(
            prepared.paths.size <= NearbySourcePreparer.MAX_FILES &&
                prepared.directories.size <= NearbySourcePreparer.MAX_DIRECTORIES &&
                (prepared.paths.isNotEmpty() || prepared.directories.isNotEmpty()),
        ) {
            "Netinkamas siunčiamų failų skaičius"
        }
        check(_state.value.status !in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) { "Ruošiamas siuntimas" }
        require(prepared.relativePaths.size == prepared.paths.size) { "Siuntimo rinkinio keliai nesutampa" }
        val starting =
            NearbyTransferState(
                status = NearbyTransferStatus.STARTING,
                receiverName = validatedPairing.receiverName,
                fileCount = prepared.paths.size,
                message = "Ruošiamas tiesioginis vietinis siuntimas",
            )
        val intent = Intent(context, NearbyTransferService::class.java)
            .setAction(NearbyTransferService.ACTION_START)
            .putExtra(NearbyTransferService.EXTRA_PAIRING, validatedPairing.encoded())
            .putStringArrayListExtra(NearbyTransferService.EXTRA_PATHS, ArrayList(prepared.paths))
            .putStringArrayListExtra(NearbyTransferService.EXTRA_RELATIVE_PATHS, ArrayList(prepared.relativePaths))
            .putStringArrayListExtra(NearbyTransferService.EXTRA_DIRECTORIES, ArrayList(prepared.directories))
            .putExtra(NearbyTransferService.EXTRA_CLEANUP_ROOT, prepared.cleanupRootPath)
        val previous = _state.value
        publish(starting)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (failure: Exception) {
            publish(previous)
            throw failure
        }
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, NearbyTransferService::class.java).setAction(NearbyTransferService.ACTION_CANCEL))
    }

    fun clearFinished() {
        if (_state.value.status in setOf(NearbyTransferStatus.COMPLETED, NearbyTransferStatus.CANCELLED, NearbyTransferStatus.ERROR)) {
            _state.value = NearbyTransferState()
        }
    }

    internal fun publish(state: NearbyTransferState) {
        _state.value = state
    }
}

class NearbyTransferService : Service() {
    companion object {
        const val ACTION_START = "com.affilemanager.app.action.START_NEARBY_TRANSFER"
        const val ACTION_CANCEL = "com.affilemanager.app.action.CANCEL_NEARBY_TRANSFER"
        const val EXTRA_PAIRING = "pairing"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_RELATIVE_PATHS = "relative_paths"
        const val EXTRA_DIRECTORIES = "directories"
        const val EXTRA_CLEANUP_ROOT = "cleanup_root"
        private const val CHANNEL_ID = "nearby_transfer"
        private const val NOTIFICATION_ID = 42
        private const val PROGRESS_INTERVAL_MILLIS = 150L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var transferJob: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelledByUser = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            cancelTransfer()
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        startAsForeground(progressNotification(NearbyTransferController.state.value))
        if (transferJob?.isActive == true) return START_NOT_STICKY

        val pairingPayload = intent.getStringExtra(EXTRA_PAIRING).orEmpty()
        val paths = intent.getStringArrayListExtra(EXTRA_PATHS)?.toList().orEmpty()
        val relativePaths = intent.getStringArrayListExtra(EXTRA_RELATIVE_PATHS)?.toList().orEmpty()
        val directories = intent.getStringArrayListExtra(EXTRA_DIRECTORIES)?.toList().orEmpty()
        val cleanupRoot = intent.getStringExtra(EXTRA_CLEANUP_ROOT)
        intent.removeExtra(EXTRA_PAIRING)
        intent.removeExtra(EXTRA_PATHS)
        intent.removeExtra(EXTRA_RELATIVE_PATHS)
        intent.removeExtra(EXTRA_DIRECTORIES)
        intent.removeExtra(EXTRA_CLEANUP_ROOT)
        cancelledByUser = false
        transferJob = scope.launch { runTransfer(pairingPayload, paths, relativePaths, directories, cleanupRoot) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        activeCall?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runTransfer(
        pairingPayload: String,
        paths: List<String>,
        relativePaths: List<String>,
        directories: List<String>,
        cleanupRoot: String?,
    ) {
        try {
            val pairing = NearbyPairing.parse(pairingPayload)
            val validatedDirectories = directories.map(::validateRelativePath).distinct()
            require(validatedDirectories.size <= NearbySourcePreparer.MAX_DIRECTORIES) {
                "Siunčiamame rinkinyje per daug aplankų"
            }
            val files = validateFiles(paths, relativePaths, allowEmpty = validatedDirectories.isNotEmpty())
            var details = files.map { TransferFileProgress(it.relativePath, it.file.length(),
                localPath = it.file.absolutePath, modifiedAtMillis = it.file.lastModified()) }
            val totalBytes = files.sumOf { it.file.length() }
            var completedBytes = 0L
            var completedFiles = 0
            publish(
                NearbyTransferState(
                    status = NearbyTransferStatus.RUNNING,
                    receiverName = pairing.receiverName,
                    fileCount = files.size,
                    totalBytes = totalBytes,
                    message = "Siunčiama tame pačiame privačiame tinkle",
                    files = details,
                ),
            )
            val cookie = login(pairing)
            announceFiles(pairing, cookie, details)
            validatedDirectories.sortedBy { it.count { char -> char == '/' } }
                .forEach { relative -> createRemoteDirectory(pairing, cookie, relative) }
            files.forEachIndexed { index, transferFile ->
                val file = transferFile.file
                if (cancelledByUser) throw CancellationException("Siuntimas atšauktas")
                var lastPublished = 0L
                upload(
                    pairing = pairing,
                    cookie = cookie,
                    file = file,
                    relativePath = transferFile.relativePath,
                    fileIndex = index + 1,
                    fileCount = files.size,
                    totalBytes = totalBytes,
                    completedBytes = completedBytes,
                ) { fileBytes ->
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPublished >= PROGRESS_INTERVAL_MILLIS || fileBytes == file.length()) {
                        lastPublished = now
                        details = details.toMutableList().apply {
                            this[index] = this[index].copy(transferredBytes = fileBytes, status = TransferFileStatus.TRANSFERRING)
                        }
                        publish(
                            NearbyTransferState(
                                status = NearbyTransferStatus.RUNNING,
                                receiverName = pairing.receiverName,
                                fileCount = files.size,
                                completedFiles = completedFiles,
                                totalBytes = totalBytes,
                                sentBytes = (completedBytes + fileBytes).coerceAtMost(totalBytes),
                                currentFile = file.name,
                                message = "Siunčiama tame pačiame privačiame tinkle",
                                files = details,
                            ),
                        )
                    }
                }
                completedBytes = Math.addExact(completedBytes, file.length())
                completedFiles += 1
                details = details.toMutableList().apply {
                    this[index] = this[index].copy(transferredBytes = file.length(), status = TransferFileStatus.COMPLETED)
                }
                publish(NearbyTransferController.state.value.copy(
                    completedFiles = completedFiles, sentBytes = completedBytes, files = details,
                ))
            }
            val completed = NearbyTransferState(
                status = NearbyTransferStatus.COMPLETED,
                receiverName = pairing.receiverName,
                fileCount = files.size,
                completedFiles = files.size,
                totalBytes = totalBytes,
                sentBytes = totalBytes,
                message = "Siuntimas baigtas",
                files = details,
            )
            publish(completed)
            finishForeground()
        } catch (cancelled: CancellationException) {
            val state = NearbyTransferController.state.value.copy(
                status = NearbyTransferStatus.CANCELLED,
                message = "Siuntimas atšauktas",
                files = stoppedFiles(TransferFileStatus.CANCELLED),
            )
            publish(state)
            finishForeground()
        } catch (error: Throwable) {
            val state = NearbyTransferController.state.value.copy(
                status = if (cancelledByUser) NearbyTransferStatus.CANCELLED else NearbyTransferStatus.ERROR,
                message = if (cancelledByUser) "Siuntimas atšauktas" else (error.message ?: "Siuntimas nepavyko").take(240),
                files = stoppedFiles(if (cancelledByUser) TransferFileStatus.CANCELLED else TransferFileStatus.FAILED),
            )
            publish(state)
            finishForeground()
        } finally {
            activeCall = null
            if (cleanupRoot != null) {
                // The private forwarding copy is deliberately not retained as history.
                val state = NearbyTransferController.state.value
                NearbyTransferController.publish(state.copy(files = state.files.map { it.copy(localPath = null) }))
            }
            cleanupRoot?.let(::safeDeleteStage)
            transferJob = null
            stopSelf()
        }
    }

    private fun stoppedFiles(status: TransferFileStatus): List<TransferFileProgress> =
        NearbyTransferController.state.value.files.map {
            if (it.status == TransferFileStatus.COMPLETED) it else it.copy(status = status)
        }

    private fun announceFiles(pairing: NearbyPairing, cookie: String, files: List<TransferFileProgress>) {
        val url = "http://${pairing.host}:${pairing.port}/nearby/manifest".toHttpUrl()
        val request = Request.Builder().url(url)
            .post(NearbyTransferManifest.encode(files).toRequestBody("application/json".toMediaType()))
            .header("Cookie", cookie).build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            // Older AF receivers do not support metadata but still accept files.
            require(response.isSuccessful || response.code == 404) { "Siuntimo rinkinio keliai nesutampa" }
        }
    }

    private fun login(pairing: NearbyPairing): String {
        val url = "http://${pairing.host}:${pairing.port}/login".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("code", pairing.code).build())
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            require(response.isSuccessful) { "Gavęs telefonas atmetė susiejimo kodą (${response.code})" }
            val cookie = response.headers.values("Set-Cookie")
                .asSequence()
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("af_session=") }
            require(!cookie.isNullOrBlank()) { "Gavimo sesija nepatvirtinta" }
            return cookie
        }
    }

    private fun upload(
        pairing: NearbyPairing,
        cookie: String,
        file: File,
        relativePath: String,
        fileIndex: Int,
        fileCount: Int,
        totalBytes: Long,
        completedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        val directory = relativePath.substringBeforeLast('/', "")
        val name = relativePath.substringAfterLast('/')
        val url = "http://${pairing.host}:${pairing.port}/".toHttpUrl().newBuilder()
            .addPathSegment("upload")
            .addQueryParameter("dir", directory)
            .addQueryParameter("name", name)
            .addQueryParameter("fileIndex", fileIndex.toString())
            .addQueryParameter("fileCount", fileCount.toString())
            .addQueryParameter("batchBytes", totalBytes.toString())
            .addQueryParameter("batchOffset", completedBytes.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ProgressFileRequestBody(file, onProgress))
            .header("Cookie", cookie)
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            require(response.isSuccessful) {
                response.body?.string()?.take(200)?.ifBlank { null } ?: "Gavęs telefonas atmetė failą (${response.code})"
            }
        }
    }

    private fun createRemoteDirectory(pairing: NearbyPairing, cookie: String, relativePath: String) {
        val url = "http://${pairing.host}:${pairing.port}/".toHttpUrl().newBuilder()
            .addPathSegment("mkdir")
            .addQueryParameter("path", relativePath)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
            .header("Cookie", cookie)
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        activeCall = call
        call.execute().use { response ->
            require(response.isSuccessful) {
                response.body?.string()?.take(200)?.ifBlank { null }
                    ?: "Gavęs telefonas neatvėrė aplanko (${response.code})"
            }
        }
    }

    private fun validateFiles(
        paths: List<String>,
        relativePaths: List<String>,
        allowEmpty: Boolean,
    ): List<TransferFile> {
        require(
            paths.size <= NearbySourcePreparer.MAX_FILES &&
                paths.size == relativePaths.size &&
                (paths.isNotEmpty() || allowEmpty),
        ) {
            "Netinkamas siunčiamų failų skaičius"
        }
        var total = 0L
        val seenSources = HashSet<String>()
        return paths.zip(relativePaths).map { (path, relativePath) ->
            val file = File(path).canonicalFile
            require(file.isFile && file.canRead()) { "Failas nepasiekiamas: ${file.name}" }
            require(seenSources.add(file.absolutePath)) { "Tas pats failas siuntimo rinkinyje kartojasi" }
            require(file.length() in 0..LanHttpServer.MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą: ${file.name}" }
            total = Math.addExact(total, file.length())
            require(total <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 5 GB ribą" }
            TransferFile(file, validateRelativePath(relativePath))
        }
    }

    private fun validateRelativePath(value: String): String {
        require(value.length in 1..4_096 && '\u0000' !in value) { "Netinkamas santykinis kelias" }
        val normalized = value.replace('\\', '/')
        require(!normalized.startsWith('/') && !normalized.endsWith('/')) { "Netinkamas santykinis kelias" }
        val parts = normalized.split('/')
        require(parts.none(String::isBlank)) { "Netinkamas santykinis kelias" }
        require(parts.isNotEmpty() && parts.size <= 65) { "Netinkamas santykinis kelias" }
        return parts.joinToString("/") { FileSystemRules.validateFileName(it).getOrThrow() }
    }

    private data class TransferFile(val file: File, val relativePath: String)

    private fun cancelTransfer() {
        cancelledByUser = true
        activeCall?.cancel()
        transferJob?.cancel(CancellationException("Siuntimas atšauktas"))
        if (transferJob == null) stopSelf()
    }

    private fun publish(state: NearbyTransferState) {
        NearbyTransferController.publish(state)
        // Updating an active foreground notification does not require the optional Android 13
        // notification-drawer permission. The transfer remains visible in Android's foreground
        // service UI even when that permission is unavailable.
        startAsForeground(progressNotification(state))
    }

    private fun safeDeleteStage(path: String) {
        val root = runCatching { File(cacheDir, "nearby-send-staging").canonicalFile }.getOrNull() ?: return
        val candidate = runCatching { File(path).canonicalFile }.getOrNull() ?: return
        if (candidate.parentFile == root) candidate.deleteRecursively()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.nearby_transfer_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startAsForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun progressNotification(state: NearbyTransferState): Notification {
        val max = state.totalBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val progress = if (state.totalBytes <= 0) 0 else
            ((state.sentBytes.toDouble() / state.totalBytes.toDouble()) * max).toInt().coerceIn(0, max)
        return notificationBuilder()
            .setContentTitle(getString(R.string.nearby_transfer_notification_title))
            .setContentText(state.currentFile ?: state.message ?: getString(R.string.nearby_transfer_starting_text))
            .setProgress(max.coerceAtLeast(1), progress, state.totalBytes <= 0)
            .setOngoing(state.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING))
            .addAction(0, getString(R.string.stop), cancelPendingIntent())
            .build()
    }

    private fun finishForeground() = stopForeground(STOP_FOREGROUND_REMOVE)

    private fun notificationBuilder(): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openIntent)
    }

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        2,
        Intent(this, NearbyTransferService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private class ProgressFileRequestBody(
        private val file: File,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = file.length()

        override fun writeTo(sink: BufferedSink) {
            FileInputStream(file).use { input ->
                val buffer = ByteArray(256 * 1_024)
                var sent = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sink.write(buffer, 0, read)
                    sent = Math.addExact(sent, read.toLong())
                    onProgress(sent)
                }
                buffer.fill(0)
            }
        }
    }
}
