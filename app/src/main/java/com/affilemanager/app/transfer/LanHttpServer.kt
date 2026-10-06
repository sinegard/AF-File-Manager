package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.ui.localization.UiTranslator
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.URLConnection
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

data class LanServerSession(
    val address: String,
    val port: Int,
    val code: String,
    val expiresAtMillis: Long,
    val rootName: String,
    val scheme: String = "http",
    val username: String? = null,
    val readOnly: Boolean = false,
    val anonymous: Boolean = false,
) {
    val url: String get() = "$scheme://$address:$port/"
}

data class LanUploadProgress(
    val currentFile: String,
    val currentFileIndex: Int,
    val totalFiles: Int,
    val currentFileBytes: Long,
    val currentFileSize: Long,
    val receivedBytes: Long,
    val totalBytes: Long,
    val completed: Boolean = false,
    val files: List<TransferFileProgress> = emptyList(),
    val bytesPerSecond: Long = 0L,
    val remainingMillis: Long? = null,
)

internal interface TemporaryLanServer : AutoCloseable {
    fun start(): LanServerSession
    fun stop(reason: String)
    fun cancelNearbyFile(batchId: String, fileIndex: Int): Boolean = false
    fun removeGroupMember(pairing: NearbyPairing): Boolean = false
    fun setGroupMessagesBlocked(pairing: NearbyPairing, blocked: Boolean): Boolean = false
}

class LanHttpServer(
    rootDirectory: File,
    private val bindAddress: InetAddress,
    durationMinutes: Int = 15,
    private val requestedPort: Int = 0,
    private val requestedCode: String? = null,
    private val readOnly: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val language: String = AppLanguageManager.ENGLISH,
    private val onUploadProgress: (LanUploadProgress) -> Unit = {},
    private val onNearbyPeer: (NearbyPairing, Long) -> Unit = { _, _ -> },
    private val onNearbyMessage: (String) -> Unit = {},
    private val onNearbyDisconnect: () -> Unit = {},
    private val groupMode: Boolean = false,
    private val organizerName: String = "AF File Manager",
    private val groupName: String = "AF group",
    private val onGroupMembers: (NearbyGroupInvite, List<NearbyGroupMember>) -> Unit = { _, _ -> },
    private val onStopped: (String) -> Unit = {},
    private val onNearbyNamedMessage: (String?, String) -> Unit = { _, message -> onNearbyMessage(message) },
    private val onMutation: (List<File>) -> Unit = {},
    private val onDiagnostic: (TransferDiagnosticEvent) -> Unit = {},
    private val diagnosticsEnabled: () -> Boolean = { false },
    private val systemErrorNumber: (Throwable) -> Int = { 0 },
    private val storageKind: TransferStorage = TransferStorage.UNKNOWN,
) : TemporaryLanServer {
    private val nearbyFiles = NearbyReceiveFiles()
    private val nearbyProgressLock = Any()
    private val clients = java.util.concurrent.ConcurrentHashMap.newKeySet<Socket>()
    private data class NearbyUploadKey(val batchId: String?, val fileIndex: Int)
    private val uploadClients = java.util.concurrent.ConcurrentHashMap<Socket, NearbyUploadKey>()
    private val uploadPeers = java.util.concurrent.ConcurrentHashMap<Socket, NearbyPairing>()
    private val groupDirectory = NearbyGroupDirectory(nowMillis)
    private data class GroupSession(val host: String, @Volatile var peer: NearbyPairing? = null)
    private val groupSessions = java.util.concurrent.ConcurrentHashMap<String, GroupSession>()

    override fun removeGroupMember(pairing: NearbyPairing): Boolean {
        if (!groupMode || !running.get()) return false
        val active = session ?: return false
        val removed = groupDirectory.remove(pairing)
        if (removed) {
            uploadPeers.entries.filter { it.value == pairing }.forEach { runCatching { it.key.close() } }
            publishGroupMembers(active)
        }
        return removed
    }

    override fun setGroupMessagesBlocked(pairing: NearbyPairing, blocked: Boolean): Boolean {
        val active = session ?: return false
        if (!groupMode || !running.get()) return false
        val changed = groupDirectory.setMessagesBlocked(pairing, blocked)
        if (changed) publishGroupMembers(active)
        return changed
    }
    companion object {
        const val ERROR_CODE_HEADER = "X-AF-Error-Code"
        const val ERROR_PHASE_HEADER = "X-AF-Error-Phase"
        const val RECEIVED_BYTES_HEADER = "X-AF-Received-Bytes"
        const val MAX_SESSION_MINUTES = LanSessionDuration.MAX_TIMED_MINUTES
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_QUEUED_REQUESTS = 16
        const val MAX_REQUESTS_PER_SESSION = 10_000
        const val MAX_AUTH_FAILURES = 20
        const val MAX_HEADER_BYTES = 16 * 1_024
        const val MAX_UPLOAD_BYTES = 32L * 1_024 * 1_024 * 1_024
        const val MAX_NEARBY_MESSAGE_BYTES = NearbyChatController.MAX_MESSAGE_BYTES
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val UPLOAD_SOCKET_TIMEOUT_MILLIS = 120_000
    }

    private val root = rootDirectory.canonicalFile.also {
        require(it.isDirectory && it.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
    }
    private val normalizedDurationMinutes = LanSessionDuration.normalize(durationMinutes)
    private val running = AtomicBoolean(false)
    private val requests = AtomicInteger(0)
    private val authFailures = AtomicInteger(0)
    private val codeConsumed = AtomicBoolean(false)
    private val cookieToken = randomToken(24)
    private val executor = ThreadPoolExecutor(
        MAX_CONCURRENT_REQUESTS,
        MAX_CONCURRENT_REQUESTS,
        10L,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUED_REQUESTS),
        { task -> Thread(task, "af-lan-request").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var session: LanServerSession? = null

    override fun start(): LanServerSession {
        check(running.compareAndSet(false, true)) { "LAN serveris jau veikia" }
        require(bindAddress is Inet4Address && (bindAddress.isSiteLocalAddress || bindAddress.isLoopbackAddress)) {
            "Serveris gali klausytis tik privačiame IPv4 tinkle"
        }
        val socket = ServerSocket().apply {
            reuseAddress = false
            soTimeout = 1_000
            bind(InetSocketAddress(bindAddress, validateRequestedPort(requestedPort)), MAX_QUEUED_REQUESTS)
        }
        serverSocket = socket
        val code = validateRequestedSecret(requestedCode) ?: randomCode()
        val created = LanServerSession(
            address = bindAddress.hostAddress ?: error("Tinklo adresas nepasiekiamas"),
            port = socket.localPort,
            code = code,
            expiresAtMillis = LanSessionDuration.expiresAt(nowMillis(), normalizedDurationMinutes),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
            readOnly = readOnly,
        )
        session = created
        acceptThread = Thread({ acceptLoop(created) }, "af-lan-accept").apply {
            isDaemon = true
            start()
        }
        return created
    }

    fun currentSession(): LanServerSession? = session

    override fun close() = stop("Serveris sustabdytas")

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        groupSessions.clear()
        session = null
        onStopped(t(reason).take(200))
    }

    override fun cancelNearbyFile(batchId: String, fileIndex: Int): Boolean {
        val status = nearbyFiles.status(batchId, fileIndex) ?: return false
        if (status == TransferFileStatus.COMPLETED) return false
        nearbyFiles.cancelFile(batchId, fileIndex)
        uploadClients.entries
            .filter { it.value == NearbyUploadKey(batchId, fileIndex) }
            .forEach { runCatching { it.key.close() } }
        publishNearbyFiles()
        return true
    }

    private fun acceptLoop(activeSession: LanServerSession) {
        try {
            while (running.get()) {
                if (groupMode) publishGroupMembers(activeSession)
                if (LanSessionDuration.isExpired(nowMillis(), activeSession.expiresAtMillis)) {
                    stop("LAN sesijos laikas baigėsi")
                    break
                }
                if (requests.get() >= MAX_REQUESTS_PER_SESSION) {
                    stop("LAN sesijos užklausų riba pasiekta")
                    break
                }
                try {
                    val client = serverSocket?.accept() ?: break
                    runCatching { client.receiveBufferSize = NearbyTransferTuning.SOCKET_BUFFER_BYTES }
                    client.soTimeout = SOCKET_TIMEOUT_MILLIS
                    clients.add(client)
                    requests.incrementAndGet()
                    runCatching { executor.execute { try { client.use(::handle) } finally { clients.remove(client) } } }
                        .onFailure { clients.remove(client); client.close() }
                } catch (_: SocketTimeoutException) {
                    // Periodically re-check bounded session lifetime.
                }
            }
        } catch (_: Throwable) {
            if (running.get()) stop("LAN serverio ryšys nutrūko")
        }
    }

    private fun handle(socket: Socket) {
        val input: BufferedInputStream
        val output: BufferedOutputStream
        try {
            // stop() can close an accepted client before its queued worker runs.
            input = BufferedInputStream(socket.getInputStream(), NearbyTransferTuning.IO_BUFFER_BYTES)
            output = BufferedOutputStream(socket.getOutputStream(), 64 * 1_024)
        } catch (error: java.io.IOException) {
            // No response stream exists, so the client observes connection loss,
            // never a successful upload. Intentional shutdown is not an error.
            runCatching {
                if (running.get() && diagnosticsEnabled()) onDiagnostic(TransferDiagnosticEvent(
                    role = TransferRole.RECEIVE,
                    phase = TransferPhase.OPEN,
                    failure = TransferFailure.CONNECTION,
                    errno = systemErrorNumber(error).coerceIn(0, 4096),
                ))
            }
            return
        }
        try {
            handleRequest(input, output, socket)
        } catch (error: Throwable) {
            val clientError = error is IllegalArgumentException || error is SecurityException
            val uploadError = error as? ReceiverUploadException
            runCatching {
                writeText(
                    output,
                    when {
                        uploadError?.failure in setOf(TransferFailure.TIMEOUT, TransferFailure.CONNECTION) -> 408
                        uploadError?.failure == TransferFailure.INVALID || clientError -> 400
                        else -> 500
                    },
                    if (uploadError != null) "${t("Serverio klaida")} (${uploadError.failure.code})"
                    else if (clientError) t(error.message ?: "Užklausa atmesta").take(200) else t("Serverio klaida"),
                    "text/plain; charset=utf-8",
                    if (uploadError == null) emptyList() else listOf(
                        "$ERROR_CODE_HEADER: ${uploadError.failure.code}",
                        "$ERROR_PHASE_HEADER: ${uploadError.phase.code}",
                    ),
                )
            }
        }
    }

    private fun handleRequest(input: BufferedInputStream, output: BufferedOutputStream, socket: Socket) {
        val remoteAddress = socket.inetAddress.hostAddress.orEmpty()
        val request = readRequest(input)
        if (request == null) {
            writeText(output, 400, t("Bloga užklausa"), "text/plain; charset=utf-8")
            return
        }
        val active = session
        if (!running.get() || active == null || LanSessionDuration.isExpired(nowMillis(), active.expiresAtMillis)) {
            writeText(output, 410, t("Sesija baigėsi"), "text/plain; charset=utf-8")
            return
        }

        if (request.path == "/login" && request.method == "POST") {
            handleLogin(request, input, output, active, remoteAddress)
            return
        }
        if (!isAuthenticated(request, remoteAddress)) {
            writeText(output, 401, loginPage(active), "text/html; charset=utf-8")
            return
        }
        val boundPeer = requestToken(request)?.let { groupSessions[it]?.peer }
        if (groupMode && boundPeer != null && groupDirectory.isRemoved(boundPeer) &&
            request.path !in setOf("/nearby/group/leave", "/nearby/disconnect")) {
            writeText(output, 403, t("Organizatorius pašalino šį telefoną iš grupės"),
                "text/plain; charset=utf-8", listOf("X-AF-Group-State: removed"))
            return
        }
        when {
            request.method == "POST" && request.path == "/nearby/disconnect" -> {
                require(!readOnly && request.contentLength == 0L) { "Užklausa atmesta" }
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
                if (groupMode) {
                    // A member's receiver belongs to that member, not to the whole group.
                    val token = requestToken(request)
                    val peer = token?.let { groupSessions[it]?.peer }
                    if (peer != null) groupDirectory.leave(peer)
                    if (token != null) groupSessions.remove(token)
                    publishGroupMembers(active)
                } else {
                    onNearbyDisconnect()
                    stop("Serveris sustabdytas")
                }
            }
            request.method == "POST" && request.path == "/nearby/cancel" -> {
                require(!readOnly && request.contentLength == 0L) { "Užklausa atmesta" }
                nearbyFiles.cancel(request.headers["x-af-batch-id"])
                val cancelledId = request.headers["x-af-batch-id"]
                uploadClients.entries.filter { it.value.batchId == cancelledId }.forEach { runCatching { it.key.close() } }
                publishNearbyFiles()
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "POST" && request.path == "/nearby/cancel-file" -> {
                require(!readOnly && request.contentLength == 0L) { "Užklausa atmesta" }
                val batchId = request.headers["x-af-batch-id"]
                    ?: throw IllegalArgumentException("Gavimo sesija nepatvirtinta")
                val fileIndex = request.query["fileIndex"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Siuntimo rinkinio keliai nesutampa")
                nearbyFiles.cancelFile(batchId, fileIndex)
                uploadClients.entries
                    .filter { it.value == NearbyUploadKey(batchId, fileIndex) }
                    .forEach { runCatching { it.key.close() } }
                publishNearbyFiles()
                writeText(output, 200, "CANCELLED", "text/plain; charset=utf-8")
            }
            request.method == "GET" && request.path == "/nearby/file-status" -> {
                val batchId = request.headers["x-af-batch-id"]
                    ?: throw IllegalArgumentException("Gavimo sesija nepatvirtinta")
                val fileIndex = request.query["fileIndex"]?.toIntOrNull()
                    ?: throw IllegalArgumentException("Siuntimo rinkinio keliai nesutampa")
                val detail = nearbyFiles.detail(batchId, fileIndex)
                    ?: throw IllegalArgumentException("Siuntimo rinkinio keliai nesutampa")
                writeText(output, 200, detail.status.name, "text/plain; charset=utf-8", buildList {
                    add("$RECEIVED_BYTES_HEADER: ${detail.transferredBytes}")
                    detail.failure?.let { add("$ERROR_CODE_HEADER: ${it.code}") }
                })
            }
            request.method == "POST" && request.path == "/nearby/peer" -> {
                require(!readOnly && request.contentLength in 1..NearbyPairing.MAX_PAYLOAD_LENGTH.toLong()) { "Užklausa atmesta" }
                val peer = NearbyPairing.parse(readExactly(input, request.contentLength.toInt()).toString(StandardCharsets.UTF_8))
                require(peer.host == remoteAddress) { "Užklausa atmesta" }
                bindGroupPeer(request, peer)
                // Record only. The recipient must select files and explicitly start the reverse send.
                onNearbyPeer(peer, active.expiresAtMillis)
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "POST" && request.path == "/nearby/group/join" -> {
                require(groupMode && !readOnly && request.contentLength in 1..NearbyPairing.MAX_PAYLOAD_LENGTH.toLong()) {
                    "Užklausa atmesta"
                }
                val peer = readGroupPairing(request, input, remoteAddress)
                if (groupDirectory.isRemoved(peer)) {
                    writeText(output, 403, t("Organizatorius pašalino šį telefoną iš grupės"),
                        "text/plain; charset=utf-8", listOf("X-AF-Group-State: removed"))
                    return
                }
                groupDirectory.join(peer)
                bindGroupPeer(request, peer)
                publishGroupMembers(active)
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "POST" && request.path == "/nearby/group/heartbeat" -> {
                require(groupMode && !readOnly && request.contentLength in 1..NearbyPairing.MAX_PAYLOAD_LENGTH.toLong()) {
                    "Užklausa atmesta"
                }
                val peer = readGroupPairing(request, input, remoteAddress)
                if (groupDirectory.isRemoved(peer)) {
                    writeText(output, 403, t("Organizatorius pašalino šį telefoną iš grupės"),
                        "text/plain; charset=utf-8", listOf("X-AF-Group-State: removed"))
                    return
                }
                require(groupDirectory.heartbeat(peer)) { "Dalyvis nebepriklauso grupei" }
                publishGroupMembers(active)
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "POST" && request.path == "/nearby/group/leave" -> {
                require(groupMode && !readOnly && request.contentLength in 1..NearbyPairing.MAX_PAYLOAD_LENGTH.toLong()) {
                    "Užklausa atmesta"
                }
                val peer = readGroupPairing(request, input, remoteAddress)
                groupDirectory.leave(peer)
                publishGroupMembers(active)
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "GET" && request.path == "/nearby/group/members" -> {
                require(groupMode && !readOnly && request.contentLength == 0L) { "Užklausa atmesta" }
                val members = groupMembers(active)
                writeText(
                    output,
                    200,
                    NearbyGroupCodec.encode(members).toString(StandardCharsets.UTF_8),
                    "application/json; charset=utf-8",
                )
            }
            request.method == "POST" && request.path == "/nearby/message" -> {
                require(!readOnly && request.contentLength in 1..MAX_NEARBY_MESSAGE_BYTES.toLong()) { "Užklausa atmesta" }
                val sender = requestToken(request)?.let { groupSessions[it]?.peer } ?: groupDirectory.memberAt(remoteAddress)
                val messagesAllowed = sender?.let(groupDirectory::messagesAllowed)
                    ?: groupDirectory.messagesAllowed(remoteAddress)
                if (groupMode && !messagesAllowed) {
                    writeText(output, 403, t("Žinutės iš šio telefono užblokuotos"), "text/plain; charset=utf-8",
                        extraHeaders = listOf("X-AF-Message-Blocked: 1"))
                    return
                }
                val message = NearbyChatController.validate(
                    readExactly(input, request.contentLength.toInt()).toString(StandardCharsets.UTF_8),
                )
                onNearbyNamedMessage(sender?.receiverName, message)
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "POST" && request.path == "/nearby/manifest" && readOnly ->
                writeText(output, 403, t("Ši sesija leidžia tik skaityti"), "text/plain; charset=utf-8")
            request.method == "POST" && request.path == "/nearby/manifest" -> {
                require(request.contentLength in 1..NearbyTransferManifest.MAX_BYTES.toLong()) { "Siuntimo rinkinio kelių aprašas per didelis" }
                nearbyFiles.announce(NearbyTransferManifest.decode(readExactly(input, request.contentLength.toInt())), request.headers["x-af-batch-id"])
                publishNearbyFiles()
                writeText(output, 200, "OK", "text/plain; charset=utf-8")
            }
            request.method == "GET" && request.path == "/" -> showDirectory(output, "")
            request.method == "GET" && request.path == "/list" -> showDirectory(output, request.query["path"].orEmpty())
            request.method == "GET" && request.path == "/download" -> download(output, request.query["path"].orEmpty())
            request.method == "POST" && request.path == "/upload" && readOnly ->
                writeText(output, 403, t("Ši sesija leidžia tik skaityti"), "text/plain; charset=utf-8")
            request.method == "POST" && request.path == "/mkdir" && readOnly ->
                writeText(output, 403, t("Ši sesija leidžia tik skaityti"), "text/plain; charset=utf-8")
            request.method == "POST" && request.path == "/mkdir" -> {
                nearbyFiles.requireBatch(request.headers["x-af-batch-id"])
                createDirectory(request, output)
            }
            request.method == "POST" && request.path == "/upload" -> {
                socket.soTimeout = UPLOAD_SOCKET_TIMEOUT_MILLIS
                val fileIndex = request.query["fileIndex"]?.toIntOrNull() ?: 1
                uploadClients[socket] = NearbyUploadKey(request.headers["x-af-batch-id"], fileIndex)
                if (boundPeer != null) uploadPeers[socket] = boundPeer
                try { upload(request, input, output, boundPeer) } finally {
                    uploadClients.remove(socket)
                    uploadPeers.remove(socket)
                }
            }
            else -> writeText(output, 404, t("Nerasta"), "text/plain; charset=utf-8")
        }
    }

    private fun publishNearbyFiles(
        bytesPerSecond: Long = 0L,
        remainingMillis: Long? = null,
    ) = synchronized(nearbyProgressLock) {
        val files = nearbyFiles.snapshot()
        val active = files.firstOrNull { it.status == TransferFileStatus.TRANSFERRING }
        onUploadProgress(LanUploadProgress(active?.name.orEmpty(), 0, files.size,
            active?.transferredBytes ?: 0L, active?.sizeBytes ?: 0L, files.sumOf { it.transferredBytes },
            files.sumOf { it.sizeBytes }, completed = files.all { it.status.isTerminal() }, files = files,
            bytesPerSecond = bytesPerSecond, remainingMillis = remainingMillis))
    }

    private fun readGroupPairing(request: Request, input: BufferedInputStream, remoteAddress: String): NearbyPairing {
        val peer = NearbyPairing.parse(readExactly(input, request.contentLength.toInt()).toString(StandardCharsets.UTF_8))
        require(peer.host == remoteAddress) { "Užklausa atmesta" }
        return peer
    }

    private fun organizerPairing(active: LanServerSession): NearbyPairing =
        NearbyPairing.create(active.address, active.port, active.code, organizerName)

    private fun groupMembers(active: LanServerSession): List<NearbyGroupMember> =
        groupDirectory.snapshot(organizerPairing(active))

    private fun publishGroupMembers(active: LanServerSession) {
        val organizer = organizerPairing(active)
        onGroupMembers(NearbyGroupInvite(organizer, groupName), groupDirectory.snapshot(organizer))
    }

    private fun handleLogin(request: Request, input: BufferedInputStream, output: BufferedOutputStream, active: LanServerSession, remoteAddress: String) {
        if ((!groupMode && codeConsumed.get()) || authFailures.get() >= MAX_AUTH_FAILURES) {
            writeText(output, 403, t("Kodas nebegalioja. Sustabdykite ir paleiskite naują sesiją."), "text/plain; charset=utf-8")
            return
        }
        val length = request.contentLength
        if (length !in 1..1_024) {
            writeText(output, 400, t("Netinkamas prisijungimo dydis"), "text/plain; charset=utf-8")
            return
        }
        val body = readExactly(input, length.toInt()).toString(StandardCharsets.UTF_8)
        val code = parseQuery(body)["code"]
        if (code != active.code) {
            authFailures.incrementAndGet()
            writeText(output, 403, loginPage(active, t("Neteisingas kodas")), "text/html; charset=utf-8")
            return
        }
        if (!groupMode && !codeConsumed.compareAndSet(false, true)) {
            writeText(output, 403, t("Kodas nebegalioja. Sustabdykite ir paleiskite naują sesiją."), "text/plain; charset=utf-8")
            return
        }
        val token = if (groupMode) synchronized(groupSessions) {
            require(groupSessions.size < 128) { "Grupė pilna" }
            randomToken(24).also { groupSessions[it] = GroupSession(remoteAddress) }
        } else cookieToken
        val page = "<html lang='${html(language)}'><head><meta http-equiv='refresh' content='0;url=/'></head><body>${html(t("Prisijungta"))}.</body></html>"
        writeText(
            output,
            200,
            page,
            "text/html; charset=utf-8",
            extraHeaders = listOf("Set-Cookie: af_session=$token; HttpOnly; SameSite=Strict; Path=/",
                "X-AF-Session-Expires: ${active.expiresAtMillis}", "X-AF-Queue-Version: 1"),
        )
    }

    private fun showDirectory(output: BufferedOutputStream, relativePath: String) {
        val directory = resolveRelative(relativePath, requireDirectory = true)
        val relative = root.toPath().relativize(directory.toPath()).toString().replace(File.separatorChar, '/')
        val entries = directory.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) })
            ?: throw SecurityException("Katalogo perskaityti nepavyko")
        require(entries.size <= 100_000) { "Kataloge per daug elementų interneto peržiūrai" }
        val rows = buildString {
            if (relative.isNotEmpty()) {
                val parent = relative.substringBeforeLast('/', "")
                append("<li><a href='/list?path=${url(parent)}'>⬆ ${html(t("Aukštyn"))}</a></li>")
            }
            entries.forEach { entry ->
                val childRelative = listOf(relative, entry.name).filter(String::isNotEmpty).joinToString("/")
                if (entry.isDirectory) {
                    append("<li>📁 <a href='/list?path=${url(childRelative)}'>${html(entry.name)}</a></li>")
                } else {
                    append("<li>📄 <a href='/download?path=${url(childRelative)}'>${html(entry.name)}</a> · ${html(FileSystemRules.humanBytes(entry.length()))}</li>")
                }
            }
        }
        val uploadControls = if (readOnly) {
            "<hr><p>${html(t("Ši sesija leidžia tik skaityti"))}</p>"
        } else {
            """<hr><h2>${html(t("Įkelti failą"))}</h2><input id="file" type="file"><button onclick="upload()">${html(t("Įkelti"))}</button><pre id="status"></pre>
            <script>async function upload(){let f=document.getElementById('file').files[0];if(!f)return;let u='/upload?dir=${url(relative)}&name='+encodeURIComponent(f.name);let r=await fetch(u,{method:'POST',body:f,headers:{'Content-Type':'application/octet-stream'}});document.getElementById('status').textContent=await r.text();if(r.ok)location.reload();}</script>"""
        }
        val page = """
            <!doctype html><html lang="${html(language)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AF File Manager</title><style>body{font-family:system-ui;max-width:900px;margin:auto;padding:20px;background:#111;color:#eee}a{color:#80cbc4}li{padding:8px}input,button{padding:10px;margin:4px}</style></head>
            <body><h1>${html(session?.rootName.orEmpty())}</h1><p>${html(t("Vieta"))}: /${html(relative)}</p><ul>$rows</ul>
            $uploadControls
            </body></html>
        """.trimIndent()
        writeText(output, 200, page, "text/html; charset=utf-8")
    }

    private fun download(output: BufferedOutputStream, relativePath: String) {
        val file = resolveRelative(relativePath, requireDirectory = false)
        require(file.isFile && file.canRead()) { "Failas nepasiekiamas" }
        val mime = URLConnection.guessContentTypeFromName(file.name) ?: "application/octet-stream"
        val encodedName = URLEncoder.encode(file.name, StandardCharsets.UTF_8.name()).replace("+", "%20")
        writeHeaders(
            output,
            200,
            mime,
            file.length(),
            listOf("Content-Disposition: attachment; filename*=UTF-8''$encodedName"),
        )
        file.inputStream().use { input -> input.copyTo(output, 256 * 1_024) }
        output.flush()
    }

    private fun upload(request: Request, input: BufferedInputStream, output: BufferedOutputStream, boundPeer: NearbyPairing?) {
        val length = request.contentLength
        val batchId = request.headers["x-af-batch-id"]
        val totalFiles = request.query["fileCount"]?.toIntOrNull()
            ?.coerceIn(1, NearbySourcePreparer.MAX_FILES) ?: 1
        val fileIndex = request.query["fileIndex"]?.toIntOrNull()?.coerceIn(1, totalFiles) ?: 1
        var phase = TransferPhase.VALIDATE
        var received = 0L
        var committed = false
        var validated = false
        var partial: File? = null
        var progress: ((Boolean, TransferFailure?) -> Unit)? = null
        fun diagnostic(failure: TransferFailure? = null, errno: Int = 0) {
            if (!diagnosticsEnabled()) return
            // Diagnostics must never change the outcome of a file operation.
            runCatching { onDiagnostic(TransferDiagnosticEvent(
                TransferRole.RECEIVE, phase, batchId, fileIndex, received, length,
                failure = failure, errno = errno, freeBytes = root.usableSpace, storage = storageKind,
            )) }
        }
        try {
            require(length in 0..MAX_UPLOAD_BYTES) { "Failas viršija saugyklos ribą" }
            val directory = resolveRelative(request.query["dir"].orEmpty(), requireDirectory = true)
            if (!directory.canWrite()) throw java.nio.file.AccessDeniedException(directory.path)
            val name = FileSystemRules.validateFileName(request.query["name"].orEmpty()).getOrThrow()
            val requested = File(directory, name)
            val target = FileSystemRules.keepBothTarget(requested)
            require(FileSystemRules.isContained(root, target)) { "Tikslas išeina už pasirinkto katalogo" }
            val staging = File(directory, ".af-upload-${UUID.randomUUID()}.partial")
            partial = staging
            val relativePath = directory.relativeTo(root).invariantSeparatorsPath
                .takeIf(String::isNotEmpty)?.let { "$it/$name" } ?: name
            nearbyFiles.validate(fileIndex, relativePath, length, batchId)
            validated = true
            val totalBytes = request.query["batchBytes"]?.toLongOrNull()
                ?.coerceIn(length, NearbySourcePreparer.MAX_TOTAL_BYTES) ?: length
            val batchOffset = request.query["batchOffset"]?.toLongOrNull()?.coerceIn(0L, totalBytes) ?: 0L
            var remaining = length
            var lastProgressAt = 0L
            val uploadStartedAt = System.nanoTime()
            fun publishProgress(completed: Boolean = false, failure: TransferFailure? = null) {
                val now = System.nanoTime()
                if (!completed && failure == null && received > 0L && now - lastProgressAt < 150_000_000L) return
                lastProgressAt = now
                val files = nearbyFiles.update(fileIndex, TransferFileProgress(
                    relativePath = relativePath, sizeBytes = length, transferredBytes = received,
                    status = when {
                        completed -> TransferFileStatus.COMPLETED
                        failure != null -> TransferFileStatus.FAILED
                        else -> TransferFileStatus.TRANSFERRING
                    },
                    localPath = if (completed) target.absolutePath else null,
                    modifiedAtMillis = if (completed) target.lastModified() else 0,
                    failure = failure,
                ), batchId)
                val receivedTotal = if (nearbyFiles.hasManifest()) files.sumOf { it.transferredBytes }
                    else (batchOffset + received).coerceAtMost(totalBytes)
                val expectedTotal = if (nearbyFiles.hasManifest()) files.sumOf { it.sizeBytes } else totalBytes
                val metrics = TransferProgressEstimator.calculate(
                    transferredBytes = received,
                    totalBytes = (expectedTotal - batchOffset).coerceAtLeast(received),
                    elapsedMillis = ((now - uploadStartedAt).coerceAtLeast(0L) / 1_000_000L),
                )
                val remainingMillis = TransferProgressEstimator.remainingMillis(
                    (expectedTotal - receivedTotal).coerceAtLeast(0L),
                    metrics.bytesPerSecond,
                )
                val announced = nearbyFiles.hasManifest()
                if (announced) {
                    publishNearbyFiles(metrics.bytesPerSecond, if (completed && receivedTotal >= expectedTotal) 0L else remainingMillis)
                    return
                }
                onUploadProgress(
                    LanUploadProgress(
                        currentFile = name,
                        currentFileIndex = fileIndex,
                        totalFiles = if (announced) files.size else totalFiles,
                        currentFileBytes = received,
                        currentFileSize = length,
                        receivedBytes = receivedTotal,
                        totalBytes = expectedTotal,
                        completed = completed,
                        files = files,
                        bytesPerSecond = metrics.bytesPerSecond,
                        remainingMillis = if (completed && receivedTotal >= expectedTotal) 0L else remainingMillis,
                    ),
                )
            }
            progress = ::publishProgress
            phase = TransferPhase.NOTIFY
            publishProgress()
            phase = TransferPhase.OPEN
            diagnostic()
            FileOutputStream(staging).use { fileOutput ->
                val bufferedFileOutput = BufferedOutputStream(fileOutput, NearbyTransferTuning.IO_BUFFER_BYTES)
                val buffer = ByteArray(NearbyTransferTuning.IO_BUFFER_BYTES)
                try {
                    while (remaining > 0) {
                        if (!running.get() || nearbyFiles.isCancelled(batchId, fileIndex))
                            throw java.util.concurrent.CancellationException("Siuntimas atšauktas")
                        check(boundPeer == null || !groupDirectory.isRemoved(boundPeer)) { "Organizatorius pašalino šį telefoną iš grupės" }
                        phase = TransferPhase.READ
                        if (received == 0L) diagnostic()
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (read < 0) throw java.io.EOFException("Įkėlimas nutrūko")
                        phase = TransferPhase.WRITE
                        bufferedFileOutput.write(buffer, 0, read)
                        remaining -= read
                        received = Math.addExact(received, read.toLong())
                        phase = TransferPhase.NOTIFY
                        publishProgress()
                    }
                    phase = TransferPhase.SYNC
                    diagnostic()
                    bufferedFileOutput.flush()
                    fileOutput.fd.sync()
                } finally {
                    buffer.fill(0)
                }
            }
            phase = TransferPhase.COMMIT
            diagnostic()
            check(staging.length() == length) { "Įkelto failo dydis nesutampa" }
            if (!running.get() || nearbyFiles.isCancelled(batchId, fileIndex))
                throw java.util.concurrent.CancellationException("Siuntimas atšauktas")
            check(boundPeer == null || !groupDirectory.isRemoved(boundPeer)) { "Organizatorius pašalino šį telefoną iš grupės" }
            // A file created after keep-both planning must never be silently replaced.
            java.nio.file.Files.move(staging.toPath(), target.toPath())
            committed = true
            phase = TransferPhase.NOTIFY
            publishProgress(completed = true)
            onMutation(listOf(target))
            phase = TransferPhase.RESPONSE
            writeText(output, 201, t("Įkelta kaip ${target.name}"), "text/plain; charset=utf-8")
            phase = TransferPhase.COMPLETE
            diagnostic()
        } catch (failure: Exception) {
            val failedPhase = phase
            val errno = systemErrorNumber(failure).coerceIn(0, 4096)
            val category = when (errno) {
                1, 13 -> TransferFailure.PERMISSION
                28 -> TransferFailure.SPACE
                17 -> TransferFailure.CONFLICT
                else -> TransferFailure.classify(failure, phase)
            }
            diagnostic(category, errno)
            // A response write can fail after commit; that file is still complete.
            if (!committed && validated) {
                // Update the status even if the UI notification callback was the failure.
                runCatching { progress?.invoke(false, category) }.onFailure { notificationError ->
                    phase = TransferPhase.NOTIFY
                    diagnostic(TransferFailure.CALLBACK, systemErrorNumber(notificationError))
                }
            }
            throw ReceiverUploadException(category, failedPhase, errno, failure)
        } finally {
            partial?.let { if (it.exists()) it.delete() }
        }
    }

    private fun resolveRelative(value: String, requireDirectory: Boolean): File {
        require(value.length <= 4_096 && '\u0000' !in value) { "Netinkamas santykinis kelias" }
        require(!value.startsWith('/') && !value.startsWith('\\')) { "Leidžiamas tik santykinis kelias" }
        val candidate = File(root, value).canonicalFile
        require(FileSystemRules.isContained(root, candidate)) { "Kelias išeina už pasirinkto katalogo" }
        if (requireDirectory) require(candidate.isDirectory) { "Katalogas nepasiekiamas" }
        return candidate
    }

    private fun requestToken(request: Request): String? = request.headers["cookie"].orEmpty()
        .split(';').map(String::trim).firstOrNull { it.startsWith("af_session=") }?.substringAfter('=')

    private fun isAuthenticated(request: Request, remoteAddress: String): Boolean {
        val token = requestToken(request) ?: return false
        return if (groupMode) groupSessions[token]?.host == remoteAddress else token == cookieToken
    }

    private fun bindGroupPeer(request: Request, peer: NearbyPairing) {
        if (!groupMode) return
        val session = requestToken(request)?.let(groupSessions::get) ?: error("Gavimo sesija nepatvirtinta")
        synchronized(session) {
            require(session.peer == null || session.peer == peer) { "Gavimo sesija nepatvirtinta" }
            session.peer = peer
        }
    }

    private fun loginPage(session: LanServerSession, error: String? = null): String {
        val numericCode = session.code.matches(Regex("[0-9]{8}"))
        val prompt = if (numericCode) {
            "Įveskite telefone rodomą 8 skaitmenų vienkartinį kodą."
        } else {
            "Įveskite telefone nustatytą laikiną slaptažodį."
        }
        val inputAttributes = if (numericCode) {
            "inputmode=\"numeric\" maxlength=\"8\" autocomplete=\"one-time-code\""
        } else {
            "type=\"password\" maxlength=\"128\" autocomplete=\"current-password\""
        }
        return """
        <!doctype html><html lang="${html(language)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AF File Manager</title></head>
        <body style="font-family:system-ui;max-width:480px;margin:40px auto;padding:20px"><h1>AF File Manager</h1>
        <p>${html(t(prompt))}</p>${error?.let { "<p style='color:#b00020'>${html(it)}</p>" }.orEmpty()}
        <form method="post" action="/login"><input name="code" $inputAttributes required><button type="submit">${html(t("Prisijungti"))}</button></form>
        <p>${html(t("Sesija baigsis automatiškai."))} ${html(t("Katalogas"))}: ${html(session.rootName)}</p></body></html>
        """.trimIndent()
    }

    private fun createDirectory(request: Request, output: BufferedOutputStream) {
        val value = request.query["path"].orEmpty()
        require(value.length in 1..4_096 && '\u0000' !in value) { "Netinkamas santykinis kelias" }
        require(!value.startsWith('/') && !value.startsWith('\\')) { "Leidžiamas tik santykinis kelias" }
        val parts = value.replace('\\', '/').split('/').filter(String::isNotBlank)
        require(parts.isNotEmpty() && parts.size <= 65) { "Netinkamas santykinis kelias" }
        var current = root
        parts.forEach { rawName ->
            val name = FileSystemRules.validateFileName(rawName).getOrThrow()
            val child = File(current, name).canonicalFile
            require(FileSystemRules.isContained(root, child)) { "Kelias išeina už pasirinkto katalogo" }
            if (child.exists()) require(child.isDirectory) { "Tokiu vardu jau yra failas" }
            else require(child.mkdir()) { "Aplanko sukurti nepavyko" }
            current = child
        }
        writeText(output, 201, t("Aplankas paruoštas"), "text/plain; charset=utf-8")
    }

    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val contentLength: Long,
    )

    private fun readRequest(input: BufferedInputStream): Request? {
        var consumed = 0
        fun line(): String? {
            val builder = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
                consumed += 1
                if (consumed > MAX_HEADER_BYTES) throw IllegalArgumentException("Antraštės per didelės")
                if (byte == '\n'.code) return builder.toString().trimEnd('\r')
                builder.append(byte.toChar())
            }
        }
        val first = line()?.split(' ') ?: return null
        if (first.size != 3 || first[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return null
        val method = first[0].uppercase(Locale.ROOT)
        if (method !in setOf("GET", "POST")) return null
        val rawTarget = first[1]
        if (!rawTarget.startsWith('/') || rawTarget.contains("#")) return null
        val path = rawTarget.substringBefore('?')
        val query = parseQuery(rawTarget.substringAfter('?', ""))
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) return null
            val key = header.substring(0, separator).trim().lowercase(Locale.ROOT)
            val value = header.substring(separator + 1).trim()
            require(key.length <= 100 && value.length <= 8_192) { "Netinkama antraštė" }
            headers[key] = value
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        require(length >= 0) { "Netinkamas turinio dydis" }
        return Request(method, path, query, headers, length)
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        require(raw.length <= 8_192) { "Užklausa per ilga" }
        return raw.split('&').take(32).associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }
    }

    private fun readExactly(input: BufferedInputStream, length: Int): ByteArray {
        val result = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val read = input.read(result, offset, length - offset)
            if (read < 0) throw IllegalStateException("Užklausa nutrūko")
            offset += read
        }
        return result
    }

    private fun writeText(
        output: BufferedOutputStream,
        status: Int,
        text: String,
        contentType: String,
        extraHeaders: List<String> = emptyList(),
    ) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        writeHeaders(output, status, contentType, bytes.size.toLong(), extraHeaders)
        output.write(bytes)
        output.flush()
    }

    private fun writeHeaders(output: BufferedOutputStream, status: Int, contentType: String, length: Long, extra: List<String>) {
        val reason = when (status) {
            200 -> "OK"
            201 -> "Created"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            408 -> "Request Timeout"
            410 -> "Gone"
            else -> "Error"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: $length\r\n")
            append("Cache-Control: no-store\r\n")
            append("X-Content-Type-Options: nosniff\r\n")
            append("X-Frame-Options: DENY\r\n")
            append("Referrer-Policy: no-referrer\r\n")
            append("Content-Security-Policy: default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun decode(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    private fun url(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
    private fun html(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
    private fun t(value: String): String = UiTranslator.translate(value, language)

    private fun randomCode(): String = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')
    private fun randomToken(bytes: Int): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
}
