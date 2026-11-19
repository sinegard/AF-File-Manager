package com.affilemanager.app.transfer

import com.affilemanager.app.ui.localization.appString
import com.affilemanager.app.ui.localization.appLanguageContext

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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.affilemanager.app.MainActivity
import com.affilemanager.app.R
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections
import kotlinx.coroutines.flow.update

enum class LanTransferStatus { STOPPED, STARTING, RUNNING, ERROR }
enum class LanTransferProtocol { WEB, FTP, WEBDAV }

data class LanTransferState(
    val status: LanTransferStatus = LanTransferStatus.STOPPED,
    val rootPath: String? = null,
    val rootName: String? = null,
    val url: String? = null,
    val code: String? = null,
    val username: String? = null,
    val protocol: LanTransferProtocol = LanTransferProtocol.WEB,
    val readOnly: Boolean = false,
    val anonymous: Boolean = false,
    val expiresAtMillis: Long? = null,
    val message: String? = null,
    val incomingUpload: LanUploadProgress? = null,
    val groupMode: Boolean = false,
    val discoveryError: String? = null,
    val phoneReceiver: Boolean = false,
    val pendingSenders: List<NearbySenderRequest> = emptyList(),
    val receiveSessionId: String? = null,
)

object LanTransferController {
    private val _state = MutableStateFlow(LanTransferState())
    val state: StateFlow<LanTransferState> = _state.asStateFlow()
    @Volatile private var receiveStore: NearbyReceiveStorage? = null
    @Synchronized internal fun attachReceiver(store: NearbyReceiveStorage) { receiveStore = store }
    @Synchronized internal fun detachReceiver(store: NearbyReceiveStorage?) { if (receiveStore === store) receiveStore = null }

    /** Validate on IO; the returned no-IO commit is applied with the corresponding UI preference. */
    internal fun prepareReceivePathChange(context: Context, path: String, config: NearbyReceiveConfiguration): () -> Unit {
        val app = context.applicationContext as AFFileManagerApplication
        val status = state.value.status
        require(status != LanTransferStatus.STARTING) { "Įkeliama…" }
        val store = receiveStore
        if (status != LanTransferStatus.RUNNING) {
            NearbyReceiveStorage(app, app.graph.nearbySourceReader, path, config)
            return {}
        }
        require(store != null) { "Pirmiausia sustabdykite kitą bendrinimo sesiją" }
        val apply = store.prepareUpdate(path, config)
        return { synchronized(this) {
            require(receiveStore === store && state.value.status == LanTransferStatus.RUNNING) { "Gavimo sesija nepatvirtinta" }
            apply()
            _state.update { it.copy(rootPath = path, rootName = store.label) }
        } }
    }

    fun start(
        context: Context,
        rootPath: String,
        durationMinutes: Int = 15,
        protocol: LanTransferProtocol = LanTransferProtocol.WEB,
        options: LanTransferOptions = LanTransferOptions(),
        bindAddress: String? = null,
        groupMode: Boolean = false,
        receiverName: String = "AF File Manager",
        groupName: String = "AF group",
        groupOrganizer: Boolean = true,
        receiveConfiguration: NearbyReceiveConfiguration = NearbyReceiveConfiguration(),
        groupBanner: ByteArray? = null,
        groupBannerHigh: ByteArray? = null,
        phoneReceiver: Boolean = false,
    ) {
        if (bindAddress == null) WifiDirectController.stop(context)
        if (!groupMode) NearbyGroupController.sessionStopped()
        val validatedOptions = options.validated(protocol)
        val intent = Intent(context, LanTransferService::class.java)
            .setAction(LanTransferService.ACTION_START)
            .putExtra(LanTransferService.EXTRA_ROOT, rootPath)
            .putExtra(LanTransferService.EXTRA_DURATION_MINUTES, LanSessionDuration.normalize(durationMinutes))
            .putExtra(LanTransferService.EXTRA_PROTOCOL, protocol.name)
            .putExtra(LanTransferService.EXTRA_PORT, validatedOptions.port)
            .putExtra(LanTransferService.EXTRA_USERNAME, validatedOptions.username)
            .putExtra(LanTransferService.EXTRA_PASSWORD, validatedOptions.password)
            .putExtra(LanTransferService.EXTRA_READ_ONLY, validatedOptions.readOnly)
            .putExtra(LanTransferService.EXTRA_ANONYMOUS, validatedOptions.anonymous)
            .putExtra(LanTransferService.EXTRA_GROUP_MODE, groupMode)
            .putExtra(LanTransferService.EXTRA_RECEIVER_NAME, receiverName.take(NearbyPairing.MAX_NAME_LENGTH))
            .putExtra(LanTransferService.EXTRA_GROUP_NAME, groupName.take(NearbyGroupInvite.MAX_GROUP_NAME_LENGTH))
            .putExtra("bind_address", bindAddress)
            .putExtra("group_organizer", groupOrganizer)
            .putExtra("concurrent_uploads", receiveConfiguration.concurrentUploads)
            .putExtra("type_destinations", receiveConfiguration.pathsJson())
            .putStringArrayListExtra("receive_allowed_roots", ArrayList(receiveConfiguration.approvedRootPaths))
            .putExtra("group_banner_bytes", groupBanner?.also { NearbyGroupBanner.validate(it) })
            .putExtra("group_banner_high_bytes", groupBannerHigh?.also { NearbyGroupBanner.validate(it, true) })
            .putExtra("phone_receiver", phoneReceiver)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        context.startService(Intent(context, LanTransferService::class.java).setAction(LanTransferService.ACTION_STOP))
        WifiDirectController.stop(context)
    }

    /** Explicit local owner action; the target service is not exported. No network counterpart. */
    fun decideSender(context: Context, sessionId: String, id: String, allow: Boolean) {
        context.startService(Intent(context, LanTransferService::class.java).setAction(LanTransferService.ACTION_DECIDE_SENDER)
            .putExtra("receive_session_id", sessionId).putExtra("sender_request_id", id).putExtra("allow_sender", allow))
    }

    internal fun publishSenderRequests(sessionId: String, requests: List<NearbySenderRequest>) {
        _state.update { if (it.status == LanTransferStatus.RUNNING && it.receiveSessionId == sessionId)
            it.copy(pendingSenders = requests) else it }
    }

    internal fun updateGroupBanner(context: Context, bytes: ByteArray?, highQuality: ByteArray? = null) {
        bytes?.let { NearbyGroupBanner.validate(it) }
        highQuality?.let { NearbyGroupBanner.validate(it, true) }
        context.startService(Intent(context, LanTransferService::class.java).setAction("com.affilemanager.app.action.GROUP_BANNER")
            .putExtra("group_banner_bytes", bytes).putExtra("group_banner_high_bytes", highQuality))
    }

    fun cancelIncomingFile(context: Context, batchId: String, fileIndex: Int) {
        require(batchId.isNotBlank() && fileIndex in 1..NearbySourcePreparer.MAX_FILES) {
            "Siuntimo rinkinio keliai nesutampa"
        }
        context.startService(
            Intent(context, LanTransferService::class.java)
                .setAction(LanTransferService.ACTION_CANCEL_NEARBY_FILE)
                .putExtra(LanTransferService.EXTRA_BATCH_ID, batchId)
                .putExtra(LanTransferService.EXTRA_FILE_INDEX, fileIndex),
        )
    }

    fun removeGroupMember(context: Context, pairing: NearbyPairing) {
        context.startService(
            Intent(context, LanTransferService::class.java)
                .setAction(LanTransferService.ACTION_REMOVE_GROUP_MEMBER)
                .putExtra(LanTransferService.EXTRA_MEMBER_PAIRING, pairing.encoded()),
        )
    }

    fun setGroupMessagesBlocked(context: Context, pairing: NearbyPairing, blocked: Boolean) {
        context.startService(Intent(context, LanTransferService::class.java)
            .setAction(LanTransferService.ACTION_GROUP_MESSAGES)
            .putExtra(LanTransferService.EXTRA_MEMBER_PAIRING, pairing.encoded())
            .putExtra("blocked", blocked))
    }

    internal fun publish(state: LanTransferState) {
        _state.value = state
    }

    internal fun publishStopped(reason: String, interrupted: Boolean = false) {
        _state.update { previous ->
            val incoming = previous.incomingUpload?.let { progress ->
                progress.copy(files = progress.files.map { file ->
                    if (file.status in setOf(TransferFileStatus.WAITING, TransferFileStatus.TRANSFERRING))
                        file.copy(status = TransferFileStatus.CANCELLED, localPath = null) else file
                })
            }
            // Credentials/endpoints belong to the ended session; its bounded file results
            // stay available until a new receive session replaces them. No disk history.
            LanTransferState(
                status = if (interrupted) LanTransferStatus.ERROR else LanTransferStatus.STOPPED,
                message = reason,
                incomingUpload = incoming,
            )
        }
    }

    internal fun publishUpload(progress: LanUploadProgress) {
        _state.update { current ->
            if (current.status == LanTransferStatus.RUNNING) current.copy(incomingUpload = progress) else current
        }
        NearbyTransferHistoryController.recordReceive(
            progress,
            NearbyTransferController.connectedPairing()?.receiverName,
        )
    }
}

class LanTransferService : Service() {
    companion object {
        const val ACTION_START = "com.affilemanager.app.action.START_LAN_TRANSFER"
        const val ACTION_STOP = "com.affilemanager.app.action.STOP_LAN_TRANSFER"
        const val ACTION_CANCEL_NEARBY_FILE = "com.affilemanager.app.action.CANCEL_NEARBY_FILE"
        const val ACTION_REMOVE_GROUP_MEMBER = "com.affilemanager.app.action.REMOVE_GROUP_MEMBER"
        const val ACTION_GROUP_MESSAGES = "com.affilemanager.app.action.GROUP_MESSAGES"
        const val ACTION_DECIDE_SENDER = "com.affilemanager.app.action.DECIDE_NEARBY_SENDER"
        const val EXTRA_ROOT = "root"
        const val EXTRA_DURATION_MINUTES = "duration_minutes"
        const val EXTRA_PROTOCOL = "protocol"
        const val EXTRA_PORT = "port"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"
        const val EXTRA_READ_ONLY = "read_only"
        const val EXTRA_ANONYMOUS = "anonymous"
        const val EXTRA_GROUP_MODE = "group_mode"
        const val EXTRA_RECEIVER_NAME = "receiver_name"
        const val EXTRA_GROUP_NAME = "group_name"
        const val EXTRA_BATCH_ID = "batch_id"
        const val EXTRA_FILE_INDEX = "file_index"
        const val EXTRA_MEMBER_PAIRING = "member_pairing"
        private const val CHANNEL_ID = "lan_transfer"
        private const val NOTIFICATION_ID = 41
    }

    @Volatile private var server: TemporaryLanServer? = null
    private var advertiser: NearbyDeviceAdvertiser? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startup: Job? = null
    private val serverLock = Any()
    private val generation = java.util.concurrent.atomic.AtomicLong()
    private var receiverStore: NearbyReceiveStorage? = null
    private var receiveNamespace: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        TransferDiagnostics.initialize(this)
        NearbyTransferHistoryController.initialize(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DECIDE_SENDER) {
            synchronized(serverLock) {
                val state = LanTransferController.state.value
                if (state.status == LanTransferStatus.RUNNING && state.phoneReceiver && state.receiveSessionId != null &&
                    state.receiveSessionId == intent.getStringExtra("receive_session_id")) {
                    server?.decideNearbySender(intent.getStringExtra("sender_request_id").orEmpty(), intent.getBooleanExtra("allow_sender", false))
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == "com.affilemanager.app.action.GROUP_BANNER") {
            runCatching { server?.setGroupBanner(intent.getByteArrayExtra("group_banner_bytes"), intent.getByteArrayExtra("group_banner_high_bytes")) }
                .onFailure { LanTransferController.publish(LanTransferController.state.value.copy(message = "Grupės paveikslėlio pakeisti nepavyko")) }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            stopServer("Sustabdyta naudotojo")
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL_NEARBY_FILE) {
            val id = intent.getStringExtra(EXTRA_BATCH_ID).orEmpty()
            val index = intent.getIntExtra(EXTRA_FILE_INDEX, 0)
            if (id.isNotBlank() && index in 1..NearbySourcePreparer.MAX_FILES) {
                server?.cancelNearbyFile(id, index)
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REMOVE_GROUP_MEMBER) {
            val pairing = intent.getStringExtra(EXTRA_MEMBER_PAIRING)
                ?.let { runCatching { NearbyPairing.parse(it) }.getOrNull() }
            if (pairing != null && LanTransferController.state.value.groupMode) {
                runCatching { server?.removeGroupMember(pairing) }.onFailure { failure ->
                    LanTransferController.publish(LanTransferController.state.value.copy(message = failure.message ?: "Veiksmas nepavyko"))
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_GROUP_MESSAGES) {
            val pairing = intent.getStringExtra(EXTRA_MEMBER_PAIRING)
                ?.let { runCatching { NearbyPairing.parse(it) }.getOrNull() }
            if (pairing != null && LanTransferController.state.value.groupMode) {
                server?.setGroupMessagesBlocked(pairing, intent.getBooleanExtra("blocked", false))
            }
            return START_NOT_STICKY
        }
        if (intent?.action != ACTION_START) return START_NOT_STICKY
        if (server != null || startup?.isActive == true) {
            LanTransferController.publish(LanTransferController.state.value.copy(message = "LAN sesija jau veikia"))
            return START_NOT_STICKY
        }

        val rootPath = intent.getStringExtra(EXTRA_ROOT).orEmpty()
        val duration = LanSessionDuration.normalize(intent.getIntExtra(EXTRA_DURATION_MINUTES, 15))
        val protocol = runCatching {
            LanTransferProtocol.valueOf(intent.getStringExtra(EXTRA_PROTOCOL).orEmpty())
        }.getOrDefault(LanTransferProtocol.WEB)
        val phoneReceiver = intent.getBooleanExtra("phone_receiver", false) && protocol == LanTransferProtocol.WEB
        startAsForeground(startingNotification(), phoneReceiver)
        val rawOptions = LanTransferOptions(
            port = intent.getIntExtra(EXTRA_PORT, 0),
            username = intent.getStringExtra(EXTRA_USERNAME).orEmpty(),
            password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty(),
            readOnly = intent.getBooleanExtra(EXTRA_READ_ONLY, false),
            anonymous = intent.getBooleanExtra(EXTRA_ANONYMOUS, false),
        )
        val groupMode = intent.getBooleanExtra(EXTRA_GROUP_MODE, false) && protocol == LanTransferProtocol.WEB
        val receiverName = intent.getStringExtra(EXTRA_RECEIVER_NAME).orEmpty()
            .filterNot(Char::isISOControl)
            .take(NearbyPairing.MAX_NAME_LENGTH)
            .ifBlank { "AF File Manager" }
        val groupName = intent.getStringExtra(EXTRA_GROUP_NAME).orEmpty()
            .filterNot(Char::isISOControl)
            .take(NearbyGroupInvite.MAX_GROUP_NAME_LENGTH)
            .ifBlank { "AF group" }
        val options = runCatching { rawOptions.validated(protocol) }.getOrElse { error ->
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.ERROR,
                    rootPath = rootPath,
                    rootName = File(rootPath).name,
                    protocol = protocol,
                    readOnly = rawOptions.readOnly,
                    anonymous = rawOptions.anonymous,
                    groupMode = groupMode,
                    message = error.message ?: "Netinkami bendrinimo nustatymai",
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        LanTransferController.publish(
            LanTransferState(
                status = LanTransferStatus.STARTING,
                rootPath = rootPath,
                rootName = File(rootPath).name,
                protocol = protocol,
                readOnly = options.readOnly,
                anonymous = options.anonymous,
                groupMode = groupMode,
                message = "Ieškomas privatus vietinio tinklo adresas",
            ),
        )
        val requestGeneration = synchronized(serverLock) { generation.incrementAndGet() }
        val receiveSessionId = java.util.UUID.randomUUID().toString()
        val startWork: () -> Unit = {
        var candidate: TemporaryLanServer? = null
        var preparedNamespace: File? = null
        var preparedStore: NearbyReceiveStorage? = null
        runCatching {
            val receiveConfiguration = NearbyReceiveConfiguration.parse(intent.getIntExtra("concurrent_uploads", 1), intent.getStringExtra("type_destinations").orEmpty(), intent.getStringArrayListExtra("receive_allowed_roots").orEmpty())
            receiveConfiguration.checkMemory()
            val graph = (application as AFFileManagerApplication).graph
            preparedStore = if (phoneReceiver) NearbyReceiveStorage(application, graph.nearbySourceReader, rootPath, receiveConfiguration) { message ->
                LanTransferController.publish(LanTransferController.state.value.copy(message = message))
            } else null
            val root = if (preparedStore?.usesDocuments == true) File(cacheDir, "nearby-receive-${java.util.UUID.randomUUID()}").also {
                require(it.mkdir()) { "Gavimo katalogas nepasiekiamas" }; preparedNamespace = it
            } else File(rootPath).canonicalFile
            // These paths come only from the non-exported service's app-owned start request, never a peer.
            val approvedRoots = receiveConfiguration.approvedRootPaths.map(::File).ifEmpty { listOf(root) }
            val typeDestinations = if (phoneReceiver) emptyMap() else receiveConfiguration.approvedDestinations(approvedRoots)
            require(root.isDirectory && root.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
            val preferred = intent.getStringExtra("bind_address")
            val address = if (preferred == null) privateLanAddress() else {
                require(NearbyPairing.isPrivateIpv4(preferred)) { "Gavimo adresas turi būti privatus IPv4 adresas" }
                val requested = InetAddress.getByName(preferred)
                require(NetworkInterface.getByInetAddress(requested)?.isUp == true) { "Privatus Wi-Fi arba Ethernet IPv4 adresas nerastas" }
                requested
            }
            requireNotNull(address) { "Privatus Wi-Fi arba Ethernet IPv4 adresas nerastas" }
            val stopped: (String) -> Unit = { reason ->
                synchronized(serverLock) { if (generation.get() == requestGeneration) {
                NearbyTransferController.connection.clear()
                NearbyGroupController.sessionStopped()
                LanTransferController.publishStopped(reason)
                WifiDirectController.stop(this)
                stopSelf()
                } }
            }
            val publishFiles: (List<File>) -> Unit = { files ->
                // Called on a server worker only, never on the service's main thread.
                runCatching {
                    runBlocking(Dispatchers.IO) { (application as AFFileManagerApplication).graph.sharedStorageIndex.changed(files) }
                }.onFailure { error ->
                    LanTransferController.publish(LanTransferController.state.value.copy(
                        message = "Could not update Android's file index",
                    ))
                    android.util.Log.w("AFLanIndex", "Could not update Android's file index", error)
                }
            }
            when (protocol) {
                LanTransferProtocol.WEB -> LanHttpServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    concurrentUploads = receiveConfiguration.concurrentUploads,
                    receiveDestinations = typeDestinations,
                    receiveStorage = preparedStore,
                    requireSenderApproval = phoneReceiver,
                    approvalNowMillis = android.os.SystemClock::elapsedRealtime,
                    onSenderRequests = { LanTransferController.publishSenderRequests(receiveSessionId, it) },
                    groupBanner = intent.getByteArrayExtra("group_banner_bytes"),
                    groupBannerHigh = intent.getByteArrayExtra("group_banner_high_bytes"),
                    language = appLanguageContext().resources.configuration.locales[0].language,
                    onNearbyPeer = { peer, expiry ->
                        NearbyChatController.beginSession(peer)
                        NearbyTransferController.connection.remember(peer, expires = expiry)
                    },
                    onNearbyNamedMessage = { groupSender, message ->
                        val sender = groupSender ?: NearbyTransferController.connectedPairing()?.receiverName ?: "Phone"
                        NearbyChatController.received(sender, message)
                    },
                    onNearbyDisconnect = { NearbyTransferController.peerDisconnected(this) },
                    groupMode = groupMode,
                    organizerName = receiverName,
                    groupName = groupName,
                    onGroupMembers = NearbyGroupController::hostMembers,
                    onUploadProgress = LanTransferController::publishUpload,
                    onMutation = publishFiles,
                    onDiagnostic = TransferDiagnostics::record,
                    diagnosticsEnabled = { TransferDiagnostics.enabled.value },
                    systemErrorNumber = TransferDiagnostics::errno,
                    storageKind = TransferDiagnostics.storage(root),
                    onStopped = stopped,
                )
                LanTransferProtocol.FTP -> LanFtpServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedUsername = options.username.ifBlank { null },
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    anonymous = options.anonymous,
                    onStopped = stopped,
                    onMutation = publishFiles,
                )
                LanTransferProtocol.WEBDAV -> LanWebDavServer(
                    rootDirectory = root,
                    bindAddress = address,
                    durationMinutes = duration,
                    requestedPort = options.port,
                    requestedUsername = options.username.ifBlank { null },
                    requestedCode = options.password.ifBlank { null },
                    readOnly = options.readOnly,
                    anonymous = options.anonymous,
                    onStopped = stopped,
                    onMutation = publishFiles,
                )
            }.also { candidate = it }.let { created -> synchronized(serverLock) {
                check(generation.get() == requestGeneration) { "Siuntimas atšauktas" }
                server = created
                receiverStore = preparedStore
                receiveNamespace = preparedNamespace
                preparedStore?.let(LanTransferController::attachReceiver)
                created.start()
            } }
        }.mapCatching { session -> synchronized(serverLock) {
            if (generation.get() != requestGeneration) { candidate?.stop("Sustabdyta naudotojo"); return@synchronized }
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.RUNNING,
                    rootPath = rootPath,
                    rootName = session.rootName,
                    url = session.url,
                    code = session.code.takeUnless { session.anonymous || it.isBlank() },
                    username = session.username,
                    protocol = protocol,
                    readOnly = session.readOnly,
                    anonymous = session.anonymous,
                    groupMode = groupMode,
                    expiresAtMillis = session.expiresAtMillis,
                    phoneReceiver = phoneReceiver,
                    receiveSessionId = receiveSessionId.takeIf { phoneReceiver },
                    pendingSenders = candidate?.pendingNearbySenders().orEmpty(),
                    message = "Serveris pasiekiamas tik pasirinktame privačiame tinkle",
                ),
            )
            val groupOrganizer = intent.getBooleanExtra("group_organizer", true)
            if (groupMode && groupOrganizer) {
                val organizer = NearbyPairing.create(session.address, session.port, session.code, receiverName)
                NearbyGroupController.host(NearbyGroupInvite(organizer, groupName))
            }
            if (phoneReceiver && !session.readOnly) {
                advertiser = NearbyDeviceAdvertiser(this) { error ->
                    LanTransferController.publish(LanTransferController.state.value.copy(discoveryError = error))
                }.also { it.start(NearbyPairing.create(session.address, session.port, session.code, receiverName),
                    receiverName, groupName.takeIf { groupMode && groupOrganizer }) }
            }
            startAsForeground(runningNotification(session), phoneReceiver)
        } }.onFailure { error -> synchronized(serverLock) {
            candidate?.stop("Gavimo sesijos paleisti nepavyko")
            preparedNamespace?.delete()
            if (generation.get() != requestGeneration) return@synchronized
            LanTransferController.detachReceiver(preparedStore)
            receiverStore = null
            server = null
            WifiDirectController.stop(this)
            NearbyGroupController.sessionStopped()
            LanTransferController.publish(
                LanTransferState(
                    status = LanTransferStatus.ERROR,
                    rootPath = rootPath,
                    rootName = File(rootPath).name,
                    protocol = protocol,
                    readOnly = options.readOnly,
                    anonymous = options.anonymous,
                    groupMode = groupMode,
                    message = error.message ?: "LAN serverio paleisti nepavyko",
                ),
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } }
        }
        if (phoneReceiver) startup = serviceScope.launch { kotlinx.coroutines.runInterruptible { startWork() } } else startWork()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val interrupted = synchronized(serverLock) {
            generation.incrementAndGet()
            startup?.cancel()
            LanTransferController.state.value.takeIf {
                it.status == LanTransferStatus.STARTING || it.status == LanTransferStatus.RUNNING
            }
        }
        serviceScope.cancel()
        advertiser?.close()
        advertiser = null
        synchronized(serverLock) {
            server?.stop("LAN paslauga sustabdyta"); server = null
            LanTransferController.detachReceiver(receiverStore); receiverStore = null
            receiveNamespace?.delete(); receiveNamespace = null
            // The generation fence suppresses callbacks from the closed server.
            // If Android destroyed the service first, clear the published endpoint here.
            if (interrupted != null) {
                if (interrupted.phoneReceiver) NearbyTransferController.connection.clear()
                LanTransferController.publishStopped("LAN paslauga sustabdyta", interrupted = true)
            }
        }
        WifiDirectController.stop(this)
        NearbyGroupController.sessionStopped()
        super.onDestroy()
    }

    private fun stopServer(reason: String) {
        synchronized(serverLock) { generation.incrementAndGet(); startup?.cancel() }
        advertiser?.close()
        advertiser = null
        synchronized(serverLock) {
            server?.stop(reason); server = null
            LanTransferController.detachReceiver(receiverStore); receiverStore = null
            receiveNamespace?.delete(); receiveNamespace = null
        }
        NearbyGroupController.sessionStopped()
        LanTransferController.publishStopped(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startAsForeground(notification: Notification, phoneReceiver: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // A user-started peer listener waits for another device, not a batch sync.
            val type = if (phoneReceiver) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            startForeground(NOTIFICATION_ID, notification, type)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, appString(R.string.lan_transfer_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
            description = appString(R.string.lan_transfer_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startingNotification(): Notification = notificationBuilder()
        .setContentTitle(appString(R.string.lan_transfer_starting_title))
        .setContentText(appString(R.string.lan_transfer_starting_text))
        .build()

    private fun runningNotification(session: LanServerSession): Notification = notificationBuilder()
        .setContentTitle(appString(R.string.lan_transfer_running_title))
        .setContentText(session.url)
        .addAction(0, appString(R.string.stop), stopPendingIntent())
        .build()

    private fun notificationBuilder(): NotificationCompat.Builder {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_app)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openIntent)
    }

    private fun stopPendingIntent(): PendingIntent = PendingIntent.getService(
        this,
        1,
        Intent(this, LanTransferService::class.java).setAction(ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun privateLanAddress(): InetAddress? {
        val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .sortedBy { network ->
                when {
                    network.name.startsWith("wlan", true) || network.name.startsWith("wifi", true) -> 0
                    network.name.startsWith("eth", true) -> 1
                    else -> 2
                }
            }
        return interfaces.asSequence()
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull(InetAddress::isSiteLocalAddress)
    }
}
