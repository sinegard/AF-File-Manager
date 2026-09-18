package com.affilemanager.app.transfer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.localization.UiTranslator
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ensureActive
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
import java.io.InputStream
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
    val bytesPerSecond: Long = 0L,
    val remainingMillis: Long? = null,
)

object NearbyTransferController {
    internal val connection = NearbyConnection()
    fun connectedPairing(): NearbyPairing? = connection.pairing()
    suspend fun prepareReturnPairing(
        context: Context,
        root: String,
        name: String,
        peer: NearbyPairing,
        durationMinutes: Int,
    ): NearbyPairing {
        var current = LanTransferController.state.value
        if (current.status !in setOf(LanTransferStatus.RUNNING, LanTransferStatus.STARTING)) {
            val before = current
            val address = kotlinx.coroutines.withContext(Dispatchers.IO) {
                java.net.DatagramSocket().use { socket ->
                    socket.connect(java.net.InetAddress.getByName(peer.host), peer.port)
                    socket.localAddress.hostAddress
                }
            }
            LanTransferController.start(
                context = context,
                rootPath = root,
                durationMinutes = durationMinutes,
                bindAddress = address,
            )
            current = withTimeout(8_000L) { LanTransferController.state.first {
                it !== before && it.status in setOf(LanTransferStatus.RUNNING, LanTransferStatus.ERROR)
            } }
        }
        require(current.status == LanTransferStatus.RUNNING && current.protocol == LanTransferProtocol.WEB &&
            !current.readOnly && current.rootPath == root) { "Pirmiausia sustabdykite kitą bendrinimo sesiją" }
        val url = java.net.URI(requireNotNull(current.url))
        return NearbyPairing.create(url.host, url.port, requireNotNull(current.code), name)
    }
    fun disconnect(context: Context) {
        NearbyTransferHistoryController.initialize(context)
        context.startService(Intent(context, NearbyTransferService::class.java).setAction(NearbyTransferService.ACTION_DISCONNECT))
    }
    internal fun peerDisconnected(context: Context) {
        connection.clear()
        NearbyChatController.endSession()
        context.startService(Intent(context, NearbyTransferService::class.java).setAction(NearbyTransferService.ACTION_DISCONNECT)
            .putExtra("notify_peer", false))
    }
    private val _state = MutableStateFlow(NearbyTransferState())
    val state: StateFlow<NearbyTransferState> = _state.asStateFlow()

    internal val queue = NearbySendQueue {
        _state.value = it
        NearbyTransferHistoryController.recordTransfer(it, outgoing = true)
    }

    @Synchronized
    fun start(context: Context, pairing: NearbyPairing, prepared: PreparedNearbyTransfer, returnPairing: NearbyPairing? = null) {
        NearbyTransferHistoryController.initialize(context)
        val validated = NearbyPairing.parse(pairing.encoded())
        val batch = queue.enqueue(validated, prepared, returnPairing)
        try {
            ContextCompat.startForegroundService(context, Intent(context, NearbyTransferService::class.java)
                .setAction(NearbyTransferService.ACTION_START).putExtra("batch_id", batch.id))
        } catch (failure: Exception) {
            queue.rollback(batch.id)
            throw failure // The picker still owns its temporary sources if launch was rejected.
        }
    }

    fun cancel(context: Context) {
        context.startService(Intent(context, NearbyTransferService::class.java).setAction(NearbyTransferService.ACTION_CANCEL))
    }

    fun cancelFile(context: Context, batchId: String, fileIndex: Int) {
        require(batchId.isNotBlank() && fileIndex in 1..NearbySourcePreparer.MAX_FILES) {
            "Siuntimo rinkinio keliai nesutampa"
        }
        context.startService(
            Intent(context, NearbyTransferService::class.java)
                .setAction(NearbyTransferService.ACTION_CANCEL_FILE)
                .putExtra(NearbyTransferService.EXTRA_BATCH_ID, batchId)
                .putExtra(NearbyTransferService.EXTRA_FILE_INDEX, fileIndex),
        )
    }

    fun sendMessage(context: Context, body: String, senderName: String) {
        val validated = NearbyChatController.validate(body)
        require(connection.pairing() != null) { "Telefonas nebeprisijungęs" }
        context.startService(Intent(context, NearbyTransferService::class.java)
            .setAction(NearbyTransferService.ACTION_SEND_MESSAGE)
            .putExtra(NearbyTransferService.EXTRA_MESSAGE, validated)
            .putExtra(NearbyTransferService.EXTRA_SENDER_NAME, senderName.take(NearbyPairing.MAX_NAME_LENGTH)))
    }

    fun clearFinished() {
        if (_state.value.status in setOf(NearbyTransferStatus.COMPLETED, NearbyTransferStatus.CANCELLED, NearbyTransferStatus.ERROR)) {
            queue.clearFinished()
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
        const val ACTION_CANCEL_FILE = "com.affilemanager.app.action.CANCEL_NEARBY_FILE"
        const val ACTION_DISCONNECT = "com.affilemanager.app.action.DISCONNECT_NEARBY_TRANSFER"
        const val ACTION_SEND_MESSAGE = "com.affilemanager.app.action.SEND_NEARBY_MESSAGE"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_SENDER_NAME = "sender_name"
        const val EXTRA_PAIRING = "pairing"
        const val EXTRA_PATHS = "paths"
        const val EXTRA_RELATIVE_PATHS = "relative_paths"
        const val EXTRA_DIRECTORIES = "directories"
        const val EXTRA_CLEANUP_ROOT = "cleanup_root"
        const val EXTRA_BATCH_ID = "batch_id"
        const val EXTRA_FILE_INDEX = "file_index"
        private const val CHANNEL_ID = "nearby_transfer"
        private const val NOTIFICATION_ID = 42
        private const val PROGRESS_INTERVAL_MILLIS = NearbyTransferTuning.PROGRESS_INTERVAL_MILLIS
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var transferJob: Job? = null
    @Volatile private var activeCall: Call? = null
    @Volatile private var cancelledByUser = false
    @Volatile private var activeFileKey: String? = null
    private val cancelledFileKeys = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val preparationJobs = mutableMapOf<String, Job>()
    private val metadataLock = kotlinx.coroutines.sync.Mutex()
    private var currentBatchId: String? = null
    private var lastStartId = 0
    private var disconnecting = false
    private var controlJobs = 0
    private val ownedIds = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(10, TimeUnit.MINUTES)
        .followRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    override fun onCreate() {
        super.onCreate()
        NearbyTransferHistoryController.initialize(this)
        createChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> cancelTransfer()
            ACTION_CANCEL_FILE -> cancelFile(
                intent.getStringExtra(EXTRA_BATCH_ID).orEmpty(),
                intent.getIntExtra(EXTRA_FILE_INDEX, 0),
            )
            ACTION_DISCONNECT -> disconnectPeer(intent.getBooleanExtra("notify_peer", true))
            ACTION_SEND_MESSAGE -> sendMessage(
                intent.getStringExtra(EXTRA_MESSAGE).orEmpty(),
                intent.getStringExtra(EXTRA_SENDER_NAME).orEmpty(),
            )
            ACTION_START -> {
                val id = intent.getStringExtra("batch_id")
                val batch = id?.let(NearbyTransferController.queue::get)
                if (batch == null || disconnecting) { finishIfIdle(); return START_NOT_STICKY }
                startAsForeground(progressNotification(NearbyTransferController.state.value))
                prepareBatch(batch)
            }
        }
        return START_NOT_STICKY
    }

    @Synchronized private fun prepareBatch(batch: NearbySendBatch) {
        if (preparationJobs.isNotEmpty() || disconnecting) return
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
            while (true) {
                val batch = NearbyTransferController.queue.nextToPrepare() ?: break
                ownedIds.add(batch.id)
            try {
                val files = validateFiles(
                    batch.sources,
                    allowEmpty = batch.sources.directories.isNotEmpty() || batch.sources.paths.isEmpty(),
                )
                val details = files.map { TransferFileProgress(
                    it.relativePath,
                    it.length,
                    localPath = it.localPath,
                    modifiedAtMillis = it.modifiedAtMillis,
                ) }
                metadataLock.lock()
                val announced: Boolean
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val cookie = NearbyTransferController.connection.cookieFor(batch.pairing) ?: login(batch.pairing)
                    batch.returnPairing?.let { announceReturnPeer(batch.pairing, cookie, it) }
                    announced = NearbyTransferController.connection.supportsQueue(batch.pairing)
                    if (announced) announceFiles(batch.pairing, cookie, details, batch.id)
                    details.forEachIndexed { index, file ->
                        if (file.status == TransferFileStatus.CANCELLED || fileKey(batch.id, index + 1) in cancelledFileKeys) {
                            runCatching { sendFileControl(batch.pairing, cookie, "/nearby/cancel-file", batch.id, index + 1) }
                        }
                    }
                } finally { metadataLock.unlock() }
                NearbyTransferController.queue.ready(batch.id, details, announced)
            } catch (failure: Exception) {
                val current = NearbyTransferController.queue.get(batch.id)?.state ?: batch.state
                val status = if (failure is CancellationException || current.status == NearbyTransferStatus.CANCELLED) NearbyTransferStatus.CANCELLED else NearbyTransferStatus.ERROR
                batch.sources.cleanupRootPath?.let(::safeDeleteStage)
                // The receiver may have accepted the manifest before its reply was lost.
                // Retire that exact batch before announcing another one, without replaying files.
                val cookie = NearbyTransferController.connection.cookieFor(batch.pairing)
                if (cookie != null && NearbyTransferController.connection.supportsQueue(batch.pairing)) {
                    runCatching { sendControl(batch.pairing, cookie, "/nearby/cancel", batch.id) }
                }
                NearbyTransferController.queue.update(batch.id, current.copy(status = status,
                    message = if (status == NearbyTransferStatus.CANCELLED) "Siuntimas atšauktas" else (failure.message ?: "Siuntimas nepavyko").take(240),
                    files = current.files.map { it.copy(status = if (status == NearbyTransferStatus.CANCELLED) TransferFileStatus.CANCELLED else TransferFileStatus.FAILED,
                        localPath = if (batch.sources.cleanupRootPath != null) null else it.localPath) }))
            }
            startNext()
            }
            } finally {
                synchronized(this@NearbyTransferService) {
                    preparationJobs.clear()
                    NearbyTransferController.queue.nextToPrepare()?.let(::prepareBatch)
                }
                finishIfIdle()
            }
        }
        preparationJobs[batch.id] = job
        job.start()
    }

    @Synchronized private fun startNext() {
        if (disconnecting || transferJob != null) return
        val batch = NearbyTransferController.queue.takeNext() ?: return
        cancelledByUser = false
        currentBatchId = batch.id
        transferJob = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            runTransfer(batch)
        }
        transferJob?.start()
    }

    @Synchronized private fun finishIfIdle() {
        if (disconnecting || controlJobs > 0 || transferJob != null || preparationJobs.isNotEmpty() || NearbyTransferController.queue.hasPending()) return
        finishForeground()
        stopSelf(lastStartId)
    }

    override fun onDestroy() {
        disconnecting = true
        client.dispatcher.cancelAll()
        scope.cancel()
        // Process-local queued metadata never restores a transfer after service/process death.
        NearbyTransferController.queue.cancel(ownedIds).filter { it.id != currentBatchId }.forEach { it.sources.cleanupRootPath?.let(::safeDeleteStage) }
        super.onDestroy()
    }

    private suspend fun runTransfer(batch: NearbySendBatch) {
        val pairing = batch.pairing
        val directories = batch.sources.directories
        val cleanupRoot = batch.sources.cleanupRootPath
        var terminalState: NearbyTransferState? = null
        try {
            val validatedDirectories = directories.map(::validateRelativePath).distinct()
            require(validatedDirectories.size <= NearbySourcePreparer.MAX_DIRECTORIES) {
                "Siunčiamame rinkinyje per daug aplankų"
            }
            val files = validateFiles(batch.sources, allowEmpty = validatedDirectories.isNotEmpty() || batch.sources.paths.isEmpty())
            var details = files.mapIndexed { index, file ->
                TransferFileProgress(
                    file.relativePath,
                    file.length,
                    status = batch.state.files.getOrNull(index)?.status
                        ?.takeIf { it == TransferFileStatus.CANCELLED }
                        ?: TransferFileStatus.WAITING,
                    localPath = file.localPath,
                    modifiedAtMillis = file.modifiedAtMillis,
                )
            }
            val totalBytes = files.sumOf(TransferFile::length)
            require(files.map(TransferFile::length) == batch.state.files.map { it.sizeBytes }) { "Failas pasikeitė po peržiūros" }
            var completedBytes = 0L
            var completedFiles = 0
            publish(
                NearbyTransferState(
                    status = NearbyTransferStatus.RUNNING,
                    receiverName = pairing.receiverName,
                    fileCount = files.size,
                    totalBytes = totalBytes,
                    message = if (files.isEmpty()) "Siejami telefonai" else "Siunčiama tame pačiame privačiame tinkle",
                    files = details,
                ),
            )
            val cookie = NearbyTransferController.connection.cookieFor(pairing) ?: login(pairing)
            val batchId = batch.id
            if (!batch.announced) announceFiles(pairing, cookie, details, batchId)
            validatedDirectories.sortedBy { it.count { char -> char == '/' } }
                .forEach { relative -> createRemoteDirectory(pairing, cookie, relative, batchId) }
            val payloadStartedAt = SystemClock.elapsedRealtime()
            files.forEachIndexed { index, transferFile ->
                if (cancelledByUser) throw CancellationException("Siuntimas atšauktas")
                val key = fileKey(batchId, index + 1)
                if (key in cancelledFileKeys || details[index].status == TransferFileStatus.CANCELLED) {
                    cancelledFileKeys += key
                    if (batch.announced) {
                        runCatching { sendFileControl(pairing, cookie, "/nearby/cancel-file", batchId, index + 1) }
                    }
                    details = details.toMutableList().apply {
                        this[index] = this[index].copy(status = TransferFileStatus.CANCELLED)
                    }
                    publish(requireNotNull(NearbyTransferController.queue.get(batch.id)).state.copy(
                        completedFiles = completedFiles,
                        sentBytes = completedBytes,
                        files = details,
                    ))
                    return@forEachIndexed
                }
                var lastPublished = 0L
                activeFileKey = key
                try {
                    upload(
                        pairing = pairing,
                        batchId = batchId,
                        cookie = cookie,
                        source = transferFile,
                        fileIndex = index + 1,
                        fileCount = files.size,
                        totalBytes = totalBytes,
                        completedBytes = completedBytes,
                    ) { fileBytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastPublished >= PROGRESS_INTERVAL_MILLIS || fileBytes == transferFile.length) {
                            lastPublished = now
                            details = details.toMutableList().apply {
                                this[index] = this[index].copy(transferredBytes = fileBytes, status = TransferFileStatus.TRANSFERRING)
                            }
                            val metrics = TransferProgressEstimator.calculate(
                                transferredBytes = (completedBytes + fileBytes).coerceAtMost(totalBytes),
                                totalBytes = totalBytes,
                                elapsedMillis = (now - payloadStartedAt).coerceAtLeast(0L),
                            )
                            publish(
                                NearbyTransferState(
                                    status = NearbyTransferStatus.RUNNING,
                                    receiverName = pairing.receiverName,
                                    fileCount = files.size,
                                    completedFiles = completedFiles,
                                    totalBytes = totalBytes,
                                    sentBytes = (completedBytes + fileBytes).coerceAtMost(totalBytes),
                                    currentFile = transferFile.name,
                                    message = "Siunčiama tame pačiame privačiame tinkle",
                                    files = details,
                                    bytesPerSecond = metrics.bytesPerSecond,
                                    remainingMillis = metrics.remainingMillis,
                                ),
                            )
                        }
                    }
                } catch (failure: Throwable) {
                    val cancelled = key in cancelledFileKeys || runCatching {
                        remoteFileStatus(pairing, cookie, batchId, index + 1) == TransferFileStatus.CANCELLED
                    }.getOrDefault(false)
                    if (!cancelled) throw failure
                    cancelledFileKeys += key
                    details = details.toMutableList().apply {
                        this[index] = this[index].copy(status = TransferFileStatus.CANCELLED)
                    }
                    publish(requireNotNull(NearbyTransferController.queue.get(batch.id)).state.copy(
                        completedFiles = completedFiles,
                        sentBytes = completedBytes,
                        files = details,
                    ))
                    return@forEachIndexed
                } finally {
                    activeFileKey = null
                }
                completedBytes = Math.addExact(completedBytes, transferFile.length)
                completedFiles += 1
                details = details.toMutableList().apply {
                    this[index] = this[index].copy(transferredBytes = transferFile.length, status = TransferFileStatus.COMPLETED)
                }
                val progressState = requireNotNull(NearbyTransferController.queue.get(batch.id)).state
                publish(progressState.copy(
                    completedFiles = completedFiles,
                    sentBytes = completedBytes,
                    files = details,
                    remainingMillis = if (completedBytes >= totalBytes) 0L else
                        TransferProgressEstimator.remainingMillis(
                            (totalBytes - completedBytes).coerceAtLeast(0L),
                            progressState.bytesPerSecond,
                        ),
                ))
            }
            val allCancelled = details.isNotEmpty() && details.all { it.status == TransferFileStatus.CANCELLED }
            val completed = NearbyTransferState(
                status = if (allCancelled) NearbyTransferStatus.CANCELLED else NearbyTransferStatus.COMPLETED,
                receiverName = pairing.receiverName,
                fileCount = files.size,
                completedFiles = completedFiles,
                totalBytes = totalBytes,
                sentBytes = completedBytes,
                message = when {
                    files.isEmpty() -> "Telefonai susieti"
                    allCancelled -> "Siuntimas atšauktas"
                    details.any { it.status == TransferFileStatus.CANCELLED } -> "Siuntimas baigtas, dalis failų atšaukta"
                    else -> "Siuntimas baigtas"
                },
                files = details,
                remainingMillis = 0L,
            )
            terminalState = completed
        } catch (cancelled: CancellationException) {
            val state = requireNotNull(NearbyTransferController.queue.get(batch.id)).state.copy(
                status = NearbyTransferStatus.CANCELLED,
                message = "Siuntimas atšauktas",
                files = stoppedFiles(TransferFileStatus.CANCELLED),
            )
            terminalState = state
        } catch (error: Throwable) {
            val state = requireNotNull(NearbyTransferController.queue.get(batch.id)).state.copy(
                status = if (cancelledByUser) NearbyTransferStatus.CANCELLED else NearbyTransferStatus.ERROR,
                message = if (cancelledByUser) "Siuntimas atšauktas" else (error.message ?: "Siuntimas nepavyko").take(240),
                files = stoppedFiles(if (cancelledByUser) TransferFileStatus.CANCELLED else TransferFileStatus.FAILED),
            )
            terminalState = state
        } finally {
            activeCall = null
            activeFileKey = null
            if (terminalState?.status in setOf(NearbyTransferStatus.ERROR, NearbyTransferStatus.CANCELLED) &&
                NearbyTransferController.connection.supportsQueue(pairing)) {
                val cookie = NearbyTransferController.connection.cookieFor(pairing)
                if (cookie != null) runCatching { sendControl(pairing, cookie, "/nearby/cancel", batch.id) }
            }
            cleanupRoot?.let(::safeDeleteStage)
            cancelledFileKeys.removeIf { it.startsWith("${batch.id}:") }
            terminalState?.let { state -> NearbyTransferController.queue.update(batch.id,
                if (cleanupRoot == null) state else state.copy(files = state.files.map { it.copy(localPath = null) })) }
            synchronized(this@NearbyTransferService) { transferJob = null; currentBatchId = null }
            startNext()
            finishIfIdle()
        }
    }

    private fun stoppedFiles(status: TransferFileStatus): List<TransferFileProgress> =
        NearbyTransferController.queue.get(requireNotNull(currentBatchId))?.state?.files.orEmpty().map {
            if (it.status == TransferFileStatus.COMPLETED) it else it.copy(status = status)
        }

    private fun announceFiles(pairing: NearbyPairing, cookie: String, files: List<TransferFileProgress>, batchId: String) {
        val url = "http://${pairing.host}:${pairing.port}/nearby/manifest".toHttpUrl()
        val request = Request.Builder().url(url)
            .post(NearbyTransferManifest.encode(files).toRequestBody("application/json".toMediaType()))
            .header("Cookie", cookie).header("X-AF-Batch-ID", batchId).build()
        val call = client.newCall(request)
        if (request.url.encodedPath != "/upload") call.timeout().timeout(15, TimeUnit.SECONDS)
        activeCall = call
        call.execute().use { response ->
            checkSessionResponse(pairing, response.code)
            // Older AF receivers do not support metadata but still accept files.
            require(response.isSuccessful || response.code == 404) { "Siuntimo rinkinio keliai nesutampa" }
        }
    }

    private fun login(pairing: NearbyPairing, rememberSession: Boolean = true): String {
        val url = "http://${pairing.host}:${pairing.port}/login".toHttpUrl()
        val request = Request.Builder()
            .url(url)
            .post(FormBody.Builder().add("code", pairing.code).build())
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        if (request.url.encodedPath != "/upload") call.timeout().timeout(15, TimeUnit.SECONDS)
        activeCall = call
        call.execute().use { response ->
            checkSessionResponse(pairing, response.code)
            require(response.isSuccessful) { "Gavęs telefonas atmetė susiejimo kodą (${response.code})" }
            val cookie = response.headers.values("Set-Cookie")
                .asSequence()
                .map { it.substringBefore(';').trim() }
                .firstOrNull { it.startsWith("af_session=") }
            require(!cookie.isNullOrBlank()) { "Gavimo sesija nepatvirtinta" }
            val expires = response.header("X-AF-Session-Expires")?.toLongOrNull() ?: (System.currentTimeMillis() + 15 * 60_000L)
            if (rememberSession) {
                NearbyChatController.beginSession(pairing)
                NearbyTransferController.connection.remember(pairing, cookie, expires)
                NearbyTransferController.connection.setQueueSupported(pairing, response.header("X-AF-Queue-Version") == "1")
            }
            return cookie
        }
    }

    private fun announceReturnPeer(pairing: NearbyPairing, cookie: String, returnPairing: NearbyPairing) {
        val request = Request.Builder().url("http://${pairing.host}:${pairing.port}/nearby/peer")
            .header("Cookie", cookie).post(returnPairing.encoded().toRequestBody("text/plain".toMediaType())).build()
        val call = client.newCall(request)
        if (request.url.encodedPath != "/upload") call.timeout().timeout(15, TimeUnit.SECONDS)
        activeCall = call
        call.execute().use { response ->
            checkSessionResponse(pairing, response.code)
            // Previous AF versions remain usable for one-way transfers.
            require(response.isSuccessful || response.code == 404) { "Gavimo sesija nepatvirtinta" }
        }
    }

    private fun upload(
        pairing: NearbyPairing,
        batchId: String,
        cookie: String,
        source: TransferFile,
        fileIndex: Int,
        fileCount: Int,
        totalBytes: Long,
        completedBytes: Long,
        onProgress: (Long) -> Unit,
    ) {
        val relativePath = source.relativePath
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
            .post(ProgressSourceRequestBody(source, onProgress))
            .header("Cookie", cookie)
            .header("X-AF-Batch-ID", batchId)
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        if (request.url.encodedPath != "/upload") call.timeout().timeout(15, TimeUnit.SECONDS)
        activeCall = call
        call.execute().use { response ->
            checkSessionResponse(pairing, response.code)
            require(response.isSuccessful) {
                response.body?.string()?.take(200)?.ifBlank { null } ?: "Gavęs telefonas atmetė failą (${response.code})"
            }
        }
    }

    private fun createRemoteDirectory(pairing: NearbyPairing, cookie: String, relativePath: String, batchId: String) {
        val url = "http://${pairing.host}:${pairing.port}/".toHttpUrl().newBuilder()
            .addPathSegment("mkdir")
            .addQueryParameter("path", relativePath)
            .build()
        val request = Request.Builder()
            .url(url)
            .post(ByteArray(0).toRequestBody("application/octet-stream".toMediaType()))
            .header("Cookie", cookie)
            .header("X-AF-Batch-ID", batchId)
            .header("User-Agent", "AF-File-Manager/Nearby")
            .build()
        val call = client.newCall(request)
        if (request.url.encodedPath != "/upload") call.timeout().timeout(15, TimeUnit.SECONDS)
        activeCall = call
        call.execute().use { response ->
            checkSessionResponse(pairing, response.code)
            require(response.isSuccessful) {
                response.body?.string()?.take(200)?.ifBlank { null }
                    ?: "Gavęs telefonas neatvėrė aplanko (${response.code})"
            }
        }
    }

    private fun checkSessionResponse(pairing: NearbyPairing, status: Int) {
        check(!NearbyTransferController.connection.clearIfRejected(pairing, status)) { "Gavimo sesija nepatvirtinta" }
    }

    private fun validateFiles(sources: PreparedNearbyTransfer, allowEmpty: Boolean): List<TransferFile> {
        val paths = sources.paths
        val relativePaths = sources.relativePaths
        val sourceUris = sources.sourceUris
        require(
            paths.size <= NearbySourcePreparer.MAX_FILES &&
                paths.size == relativePaths.size &&
                paths.size == sourceUris.size &&
                (sources.fileSizes.isEmpty() || sources.fileSizes.size == paths.size) &&
                (paths.isNotEmpty() || allowEmpty),
        ) {
            "Netinkamas siunčiamų failų skaičius"
        }
        var total = 0L
        val seenSources = HashSet<String>()
        return relativePaths.indices.map { index ->
            val relativePath = validateRelativePath(relativePaths[index])
            val uriValue = sourceUris[index]
            val transfer = if (uriValue == null) {
                val file = File(paths[index]).canonicalFile
                require(file.isFile && file.canRead()) { "Failas nepasiekiamas: ${file.name}" }
                require(seenSources.add("file:${file.absolutePath}")) { "Tas pats failas siuntimo rinkinyje kartojasi" }
                TransferFile(
                    file = file,
                    contentUri = null,
                    relativePath = relativePath,
                    length = file.length(),
                    modifiedAtMillis = file.lastModified(),
                )
            } else {
                require(paths[index].isBlank()) { "Siuntimo rinkinio keliai nesutampa" }
                val uri = Uri.parse(uriValue)
                require(uri.scheme == "content" && seenSources.add("uri:$uri")) {
                    "Palaikomos tik Android dokumentų nuorodos"
                }
                val expected = sources.fileSizes.getOrNull(index)
                    ?: throw IllegalArgumentException("Failo dydis nepasiekiamas: ${relativePath.substringAfterLast('/')}")
                val measured = contentLength(uri) ?: expected
                require(measured == expected) { "Failas pasikeitė po peržiūros" }
                contentResolver.openInputStream(uri)?.use { Unit }
                    ?: throw IllegalArgumentException("Failo srautas nepasiekiamas: ${relativePath.substringAfterLast('/')}")
                TransferFile(
                    file = null,
                    contentUri = uri,
                    relativePath = relativePath,
                    length = measured,
                    modifiedAtMillis = 0L,
                )
            }
            require(transfer.length in 0..LanHttpServer.MAX_UPLOAD_BYTES) {
                "Failas viršija saugyklos ribą"
            }
            total = Math.addExact(total, transfer.length)
            require(total <= NearbySourcePreparer.MAX_TOTAL_BYTES) { "Siuntimo rinkinys viršija 60 GB ribą" }
            transfer
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

    private fun contentLength(uri: Uri): Long? {
        val descriptorSize = runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.length.takeIf { it >= 0L }
            }
        }.getOrNull()
        val statSize = runCatching {
            contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize.takeIf { it >= 0L }
            }
        }.getOrNull()
        var providerSize: Long? = null
        runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)
        }.getOrNull()?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(android.provider.OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                    ?.let { index -> providerSize = cursor.getLong(index).takeIf { it >= 0L } }
            }
        }
        return descriptorSize ?: statSize ?: providerSize
    }

    private inner class TransferFile(
        val file: File?,
        val contentUri: Uri?,
        val relativePath: String,
        val length: Long,
        val modifiedAtMillis: Long,
    ) {
        val name: String get() = relativePath.substringAfterLast('/')
        val localPath: String? get() = file?.absolutePath
        fun openInput(): InputStream = file?.let(::FileInputStream)
            ?: contentResolver.openInputStream(requireNotNull(contentUri))
            ?: throw IOException("Failo srautas nepasiekiamas: $name")
    }

    private fun cancelTransfer() {
        cancelledByUser = true
        val abandoned = NearbyTransferController.queue.cancel()
        ownedIds.addAll(abandoned.map { it.id })
        client.dispatcher.cancelAll()
        synchronized(this) { preparationJobs.values.forEach { it.cancel() } }
        transferJob?.cancel(CancellationException("Siuntimas atšauktas"))
        synchronized(this) { controlJobs++ }
        scope.launch {
            try {
            // Local temporary copies do not wait for a slow/unreachable peer's acknowledgement.
            abandoned.filter { it.id != currentBatchId }.forEach { it.sources.cleanupRootPath?.let(::safeDeleteStage) }
            abandoned.forEach { batch ->
                val cookie = NearbyTransferController.connection.cookieFor(batch.pairing)
                if (cookie != null && NearbyTransferController.connection.supportsQueue(batch.pairing)) {
                    runCatching { sendControl(batch.pairing, cookie, "/nearby/cancel", batch.id) }
                }
            }
            } finally {
                synchronized(this@NearbyTransferService) { controlJobs-- }
                finishIfIdle()
            }
        }
    }

    private fun cancelFile(batchId: String, fileIndex: Int) {
        if (!NearbyTransferController.queue.cancelFile(batchId, fileIndex)) return
        val key = fileKey(batchId, fileIndex)
        cancelledFileKeys += key
        if (activeFileKey == key) activeCall?.cancel()
        val batch = NearbyTransferController.queue.get(batchId) ?: return
        val cookie = NearbyTransferController.connection.cookieFor(batch.pairing) ?: return
        if (!NearbyTransferController.connection.supportsQueue(batch.pairing)) return
        synchronized(this) { controlJobs++ }
        scope.launch {
            try {
                runCatching { sendFileControl(batch.pairing, cookie, "/nearby/cancel-file", batchId, fileIndex) }
            } finally {
                synchronized(this@NearbyTransferService) { controlJobs-- }
                finishIfIdle()
            }
        }
    }

    private fun disconnectPeer(notifyPeer: Boolean) {
        if (disconnecting) return
        disconnecting = true
        val peer = NearbyTransferController.connectedPairing()
        val cookie = peer?.let(NearbyTransferController.connection::cookieFor)
        cancelTransfer()
        NearbyTransferController.connection.clear()
        NearbyChatController.endSession()
        scope.launch {
            try {
                if (notifyPeer && peer != null) {
                    // A receive-only peer may not have used the return one-time code yet.
                    val accepted = cookie ?: login(peer, rememberSession = false)
                    sendControl(peer, accepted, "/nearby/disconnect")
                }
            } catch (_: Exception) {
                // Local stop is unconditional, even when the other phone is already gone.
            } finally {
                NearbyTransferController.connection.clear()
                LanTransferController.stop(this@NearbyTransferService)
                finishForeground()
                stopSelf(lastStartId)
            }
        }
    }

    private fun sendMessage(raw: String, senderName: String) {
        val message = runCatching { NearbyChatController.validate(raw) }.getOrElse {
            NearbyChatController.failed(it.message ?: "Žinutės išsiųsti nepavyko")
            finishIfIdle()
            return
        }
        val peer = NearbyTransferController.connectedPairing()
        if (peer == null) {
            NearbyChatController.failed("Telefonas nebeprisijungęs")
            finishIfIdle()
            return
        }
        synchronized(this) { controlJobs++ }
        NearbyChatController.sending(true)
        scope.launch {
            try {
                val cookie = NearbyTransferController.connection.cookieFor(peer) ?: login(peer)
                val request = Request.Builder().url("http://${peer.host}:${peer.port}/nearby/message")
                    .header("Cookie", cookie)
                    .post(message.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                client.newCall(request).apply { timeout().timeout(15, TimeUnit.SECONDS) }.execute().use { response ->
                    checkSessionResponse(peer, response.code)
                    require(response.isSuccessful) { "Žinutės išsiųsti nepavyko (${response.code})" }
                }
                NearbyChatController.sent(senderName.ifBlank { "Šis telefonas" }, message)
            } catch (failure: Exception) {
                NearbyChatController.failed(
                    if (failure is IllegalArgumentException || failure is IllegalStateException) {
                        failure.message?.take(240) ?: "Žinutės išsiųsti nepavyko"
                    } else {
                        // Socket/provider messages are platform copy and may use another language.
                        "Žinutės išsiųsti nepavyko"
                    },
                )
            } finally {
                synchronized(this@NearbyTransferService) { controlJobs-- }
                finishIfIdle()
            }
        }
    }

    private fun sendControl(peer: NearbyPairing, cookie: String, path: String, id: String? = null) {
        val request = Request.Builder().url("http://${peer.host}:${peer.port}$path").header("Cookie", cookie)
            .post(ByteArray(0).toRequestBody(null)).apply { if (id != null) header("X-AF-Batch-ID", id) }.build()
        val call = client.newCall(request)
        call.timeout().timeout(3, TimeUnit.SECONDS)
        call.execute().use { require(it.isSuccessful || it.code == 404) { "Gavimo sesija nepatvirtinta" } }
    }

    private fun sendFileControl(
        peer: NearbyPairing,
        cookie: String,
        path: String,
        batchId: String,
        fileIndex: Int,
    ) {
        val url = "http://${peer.host}:${peer.port}$path".toHttpUrl().newBuilder()
            .addQueryParameter("fileIndex", fileIndex.toString())
            .build()
        val request = Request.Builder().url(url).header("Cookie", cookie).header("X-AF-Batch-ID", batchId)
            .post(ByteArray(0).toRequestBody(null)).build()
        client.newCall(request).apply { timeout().timeout(3, TimeUnit.SECONDS) }.execute().use {
            require(it.isSuccessful || it.code == 404) { "Gavimo sesija nepatvirtinta" }
        }
    }

    private fun remoteFileStatus(
        peer: NearbyPairing,
        cookie: String,
        batchId: String,
        fileIndex: Int,
    ): TransferFileStatus? {
        if (!NearbyTransferController.connection.supportsQueue(peer)) return null
        val url = "http://${peer.host}:${peer.port}/nearby/file-status".toHttpUrl().newBuilder()
            .addQueryParameter("fileIndex", fileIndex.toString())
            .build()
        val request = Request.Builder().url(url).header("Cookie", cookie).header("X-AF-Batch-ID", batchId).get().build()
        return client.newCall(request).apply { timeout().timeout(3, TimeUnit.SECONDS) }.execute().use { response ->
            checkSessionResponse(peer, response.code)
            if (!response.isSuccessful) return@use null
            runCatching { TransferFileStatus.valueOf(response.body?.string().orEmpty()) }.getOrNull()
        }
    }

    private fun fileKey(batchId: String, fileIndex: Int): String = "$batchId:$fileIndex"

    private fun publish(state: NearbyTransferState) {
        NearbyTransferController.queue.update(requireNotNull(currentBatchId), state)
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
            .setContentText(
                nearbyTransferNotificationText(
                    state,
                    resources.configuration.locales[0].language,
                    getString(R.string.nearby_transfer_starting_text),
                ),
            )
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

    private inner class ProgressSourceRequestBody(
        private val source: TransferFile,
        private val onProgress: (Long) -> Unit,
    ) : RequestBody() {
        override fun isOneShot() = true
        override fun contentType() = "application/octet-stream".toMediaType()
        override fun contentLength(): Long = source.length

        override fun writeTo(sink: BufferedSink) {
            source.openInput().use { input ->
                val buffer = ByteArray(NearbyTransferTuning.IO_BUFFER_BYTES)
                var sent = 0L
                try {
                    while (sent < source.length) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), source.length - sent).toInt())
                        if (read < 0) throw IOException("Failo srautas nutrūko: ${source.name}")
                        sink.write(buffer, 0, read)
                        sent = Math.addExact(sent, read.toLong())
                        onProgress(sent)
                    }
                } finally {
                    buffer.fill(0)
                }
            }
        }
    }
}

/** Keeps user file names untouched while localizing only app-owned notification status copy. */
internal fun nearbyTransferNotificationText(
    state: NearbyTransferState,
    language: String,
    startingText: String,
): String = state.currentFile
    ?: state.message?.let { UiTranslator.translate(it, language) }
    ?: startingText
