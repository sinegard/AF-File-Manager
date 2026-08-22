package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** A deliberately small, bounded FTP server for an explicitly started LAN session. */
class LanFtpServer(
    rootDirectory: File,
    private val bindAddress: InetAddress,
    durationMinutes: Int = 15,
    private val requestedPort: Int = 0,
    requestedUsername: String? = null,
    private val requestedCode: String? = null,
    private val readOnly: Boolean = false,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStopped: (String) -> Unit = {},
) : TemporaryLanServer {
    companion object {
        const val USERNAME = "af"
        const val MAX_COMMAND_BYTES = 8 * 1_024
        const val MAX_COMMANDS_PER_SESSION = 10_000
        const val MAX_AUTH_FAILURES = 20
        const val AUTH_LOCK_MILLIS = 30_000L
        const val MAX_UPLOAD_BYTES = LanHttpServer.MAX_UPLOAD_BYTES
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val DATA_TIMEOUT_MILLIS = 15_000
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUE = 12
    }

    private val root = rootDirectory.canonicalFile.also {
        require(it.isDirectory && it.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
    }
    private val durationMillis = durationMinutes.coerceIn(1, LanHttpServer.MAX_SESSION_MINUTES) * 60_000L
    private val username = validateRequestedUsername(requestedUsername, USERNAME)
    private val running = AtomicBoolean(false)
    private val commandCount = AtomicInteger(0)
    private val authGuard = Any()
    private var authFailures = 0
    private var authLockedUntilMillis = 0L
    private val executor = ThreadPoolExecutor(
        MAX_CLIENTS,
        MAX_CLIENTS,
        10,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUE),
        { task -> Thread(task, "af-ftp-client").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var controlSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var session: LanServerSession? = null

    override fun start(): LanServerSession {
        check(running.compareAndSet(false, true)) { "FTP serveris jau veikia" }
        require(bindAddress is Inet4Address && (bindAddress.isSiteLocalAddress || bindAddress.isLoopbackAddress)) {
            "Serveris gali klausytis tik privačiame IPv4 tinkle"
        }
        val socket = ServerSocket().apply {
            reuseAddress = false
            soTimeout = 1_000
            bind(InetSocketAddress(bindAddress, validateRequestedPort(requestedPort)), MAX_QUEUE)
        }
        controlSocket = socket
        val code = validateRequestedSecret(requestedCode) ?: randomCode()
        val created = LanServerSession(
            address = requireNotNull(bindAddress.hostAddress),
            port = socket.localPort,
            code = code,
            expiresAtMillis = Math.addExact(nowMillis(), durationMillis),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
            scheme = "ftp",
            username = username,
            readOnly = readOnly,
        )
        session = created
        acceptThread = Thread({ acceptLoop(created) }, "af-ftp-accept").apply { isDaemon = true; start() }
        return created
    }

    override fun close() = stop("FTP serveris sustabdytas")

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        runCatching { controlSocket?.close() }
        controlSocket = null
        executor.shutdownNow()
        session = null
        onStopped(reason.take(200))
    }

    private fun acceptLoop(active: LanServerSession) {
        try {
            while (running.get()) {
                if (nowMillis() >= active.expiresAtMillis) return stop("FTP sesijos laikas baigėsi")
                if (commandCount.get() >= MAX_COMMANDS_PER_SESSION) return stop("FTP sesijos komandų riba pasiekta")
                try {
                    val client = controlSocket?.accept() ?: break
                    client.soTimeout = SOCKET_TIMEOUT_MILLIS
                    runCatching { executor.execute { client.use { handleClient(it, active) } } }.onFailure { client.close() }
                } catch (_: SocketTimeoutException) {
                    // Re-check expiry once per second.
                }
            }
        } catch (_: Throwable) {
            if (running.get()) stop("FTP serverio ryšys nutrūko")
        }
    }

    private fun handleClient(socket: Socket, active: LanServerSession) {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8), 8 * 1_024)
        val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), 8 * 1_024)
        var authenticated = false
        var acceptedUser = false
        var cwd = root
        var passive: ServerSocket? = null
        var activeTarget: InetSocketAddress? = null
        var renameFrom: File? = null

        fun reply(code: Int, text: String) {
            writer.write("$code ${text.replace('\r', ' ').replace('\n', ' ').take(500)}\r\n")
            writer.flush()
        }

        fun requireAuth(): Boolean {
            if (authenticated) return true
            reply(530, "Please log in with USER and PASS")
            return false
        }

        fun requireWrite(): Boolean {
            if (!requireAuth()) return false
            if (!readOnly) return true
            reply(550, "This session is read-only")
            return false
        }

        fun openPassive(): ServerSocket {
            passive?.close()
            activeTarget = null
            return ServerSocket().apply {
                reuseAddress = false
                soTimeout = DATA_TIMEOUT_MILLIS
                bind(InetSocketAddress(bindAddress, 0), 1)
                passive = this
            }
        }

        fun withData(block: (Socket) -> Unit) {
            val server = passive
            val target = activeTarget
            if (server == null && target == null) {
                reply(425, "Use PASV, EPSV, PORT or EPRT first")
                return
            }
            passive = null
            activeTarget = null
            reply(150, "Opening data connection")
            try {
                val data = if (server != null) {
                    server.use { listener -> listener.accept() }
                } else {
                    Socket().apply {
                        soTimeout = DATA_TIMEOUT_MILLIS
                        connect(requireNotNull(target), DATA_TIMEOUT_MILLIS)
                    }
                }
                data.use(block)
                reply(226, "Transfer complete")
            } catch (_: SocketTimeoutException) {
                reply(425, "Data connection timed out")
            } catch (_: IOException) {
                reply(425, "Data connection failed")
            }
        }

        fun setActiveTarget(target: InetSocketAddress) {
            runCatching { passive?.close() }
            passive = null
            activeTarget = target
        }

        reply(220, "AF File Manager temporary FTP server")
        try {
            while (running.get() && nowMillis() < active.expiresAtMillis) {
                val line = reader.readLine() ?: break
                require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_COMMAND_BYTES) { "FTP komanda per ilga" }
                if (commandCount.incrementAndGet() > MAX_COMMANDS_PER_SESSION) break
                val command = line.substringBefore(' ').uppercase(Locale.ROOT)
                val rawArgument = line.substringAfter(' ', "")
                val argument = if (command == "PASS") rawArgument else rawArgument.trim()
                try {
                    when (command) {
                    "USER" -> {
                        acceptedUser = argument == active.username
                        reply(if (acceptedUser) 331 else 530, if (acceptedUser) "Password required" else "Unknown user")
                    }
                    "PASS" -> {
                        when (verifyCredentials(acceptedUser, argument, active.code)) {
                            AuthResult.ACCEPTED -> {
                                authenticated = true
                                reply(230, "Logged in")
                            }
                            AuthResult.REJECTED -> {
                                authenticated = false
                                reply(530, "Login incorrect")
                            }
                            AuthResult.LOCKED -> {
                                authenticated = false
                                reply(530, "Too many login failures; retry later")
                            }
                        }
                    }
                    "SYST" -> reply(215, "UNIX Type: L8")
                    "FEAT" -> {
                        writer.write("211-Features\r\n UTF8\r\n EPSV\r\n EPRT\r\n SIZE\r\n MDTM\r\n MLSD\r\n211 End\r\n")
                        writer.flush()
                    }
                    "OPTS" -> reply(200, "UTF8 enabled")
                    "NOOP" -> reply(200, "OK")
                    "TYPE" -> reply(200, "Type set")
                    "PWD", "XPWD" -> if (requireAuth()) reply(257, "\"${virtualPath(cwd)}\"")
                    "CWD" -> if (requireAuth()) {
                        val target = resolvePath(cwd, argument)
                        if (target.isDirectory && target.canRead()) { cwd = target; reply(250, "Directory changed") }
                        else reply(550, "Directory unavailable")
                    }
                    "CDUP" -> if (requireAuth()) {
                        cwd = if (cwd == root) root else cwd.parentFile?.takeIf { FileSystemRules.isContained(root, it) } ?: root
                        reply(250, "Directory changed")
                    }
                    "PASV" -> if (requireAuth()) {
                        val data = openPassive()
                        val host = requireNotNull(bindAddress.hostAddress).replace('.', ',')
                        reply(227, "Entering Passive Mode ($host,${data.localPort / 256},${data.localPort % 256})")
                    }
                    "EPSV" -> if (requireAuth()) {
                        val data = openPassive()
                        reply(229, "Entering Extended Passive Mode (|||${data.localPort}|)")
                    }
                    "PORT" -> if (requireAuth()) {
                        setActiveTarget(parsePortTarget(argument, socket.inetAddress))
                        reply(200, "Active data connection configured")
                    }
                    "EPRT" -> if (requireAuth()) {
                        setActiveTarget(parseEprtTarget(argument, socket.inetAddress))
                        reply(200, "Extended active data connection configured")
                    }
                    "LIST", "NLST", "MLSD" -> if (requireAuth()) {
                        val target = resolvePath(cwd, argument.ifBlank { "." })
                        val entries = if (target.isDirectory) target.listFiles()?.toList().orEmpty() else listOf(target)
                        require(entries.size <= 100_000) { "Aplanke per daug elementų" }
                        withData { data ->
                            data.getOutputStream().bufferedWriter(StandardCharsets.UTF_8).use { out ->
                                entries.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase(Locale.ROOT) }).forEach { file ->
                                    val safeName = file.name.replace('\r', '_').replace('\n', '_')
                                    val row = when (command) {
                                        "NLST" -> safeName
                                        "MLSD" -> "type=${if (file.isDirectory) "dir" else "file"};size=${if (file.isFile) file.length() else 0};modify=${ftpDate(file.lastModified())}; $safeName"
                                        else -> "${if (file.isDirectory) "drwxr-xr-x" else "-rw-r--r--"} 1 af af ${if (file.isFile) file.length() else 0} Jan 01 00:00 $safeName"
                                    }
                                    out.write(row); out.write("\r\n")
                                }
                            }
                        }
                    }
                    "RETR" -> if (requireAuth()) {
                        val file = resolvePath(cwd, argument)
                        if (!file.isFile || !file.canRead()) reply(550, "File unavailable")
                        else withData { data -> file.inputStream().use { it.copyTo(data.getOutputStream(), 256 * 1_024) } }
                    }
                    "STOR" -> if (requireWrite()) {
                        val target = resolveWritablePath(cwd, argument)
                        val partial = File(target.parentFile, ".af-ftp-${UUID.randomUUID()}.partial")
                        try {
                            withData { data ->
                                var total = 0L
                                partial.outputStream().use { output ->
                                    val input = data.getInputStream()
                                    val buffer = ByteArray(256 * 1_024)
                                    while (true) {
                                        val read = input.read(buffer)
                                        if (read < 0) break
                                        total = Math.addExact(total, read.toLong())
                                        require(total <= MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą" }
                                        output.write(buffer, 0, read)
                                    }
                                }
                                Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                            }
                        } finally { if (partial.exists()) partial.delete() }
                    }
                    "SIZE" -> if (requireAuth()) {
                        val file = resolvePath(cwd, argument)
                        if (file.isFile) reply(213, file.length().toString()) else reply(550, "File unavailable")
                    }
                    "MDTM" -> if (requireAuth()) {
                        val file = resolvePath(cwd, argument)
                        if (file.exists()) reply(213, ftpDate(file.lastModified())) else reply(550, "File unavailable")
                    }
                    "MKD", "XMKD" -> if (requireWrite()) {
                        val dir = resolveWritablePath(cwd, argument)
                        if (!dir.exists() && dir.mkdir()) reply(257, "\"${virtualPath(dir)}\" created") else reply(550, "Create failed")
                    }
                    "DELE" -> if (requireWrite()) {
                        val file = resolvePath(cwd, argument)
                        if (file != root && file.isFile && file.delete()) reply(250, "Deleted") else reply(550, "Delete failed")
                    }
                    "RMD", "XRMD" -> if (requireWrite()) {
                        val dir = resolvePath(cwd, argument)
                        if (dir != root && dir.isDirectory && dir.list()?.isEmpty() == true && dir.delete()) reply(250, "Removed") else reply(550, "Remove failed")
                    }
                    "RNFR" -> if (requireWrite()) {
                        val source = resolvePath(cwd, argument)
                        if (source != root && source.exists()) { renameFrom = source; reply(350, "Ready for RNTO") } else reply(550, "Source unavailable")
                    }
                    "RNTO" -> if (requireWrite()) {
                        val source = renameFrom
                        renameFrom = null
                        val target = resolveWritablePath(cwd, argument)
                        if (source != null && !target.exists() && source.renameTo(target)) reply(250, "Renamed") else reply(550, "Rename failed")
                    }
                    "QUIT" -> { reply(221, "Goodbye"); break }
                        else -> reply(502, "Command not implemented")
                    }
                } catch (error: Throwable) {
                    runCatching { passive?.close() }
                    passive = null
                    activeTarget = null
                    reply(550, (error.message ?: "Request rejected").take(300))
                }
            }
        } finally {
            runCatching { passive?.close() }
            activeTarget = null
        }
    }

    private fun verifyCredentials(acceptedUser: Boolean, password: String, expectedCode: String): AuthResult = synchronized(authGuard) {
        val now = nowMillis()
        if (now < authLockedUntilMillis) return@synchronized AuthResult.LOCKED
        val matches = acceptedUser && MessageDigest.isEqual(
            password.toByteArray(StandardCharsets.UTF_8),
            expectedCode.toByteArray(StandardCharsets.UTF_8),
        )
        if (matches) {
            authFailures = 0
            authLockedUntilMillis = 0L
            AuthResult.ACCEPTED
        } else {
            authFailures += 1
            if (authFailures >= MAX_AUTH_FAILURES) {
                authFailures = 0
                authLockedUntilMillis = Math.addExact(now, AUTH_LOCK_MILLIS)
                AuthResult.LOCKED
            } else {
                AuthResult.REJECTED
            }
        }
    }

    private fun parsePortTarget(argument: String, peerAddress: InetAddress): InetSocketAddress {
        val values = argument.split(',').map { part -> part.toIntOrNull() ?: throw IllegalArgumentException("Invalid PORT") }
        require(values.size == 6 && values.all { it in 0..255 }) { "Invalid PORT" }
        val address = InetAddress.getByAddress(values.take(4).map(Int::toByte).toByteArray())
        val port = values[4] * 256 + values[5]
        return validatedActiveTarget(address, port, peerAddress)
    }

    private fun parseEprtTarget(argument: String, peerAddress: InetAddress): InetSocketAddress {
        require(argument.length in 7..80) { "Invalid EPRT" }
        val delimiter = argument.first()
        val parts = argument.split(delimiter)
        require(parts.size == 5 && parts.first().isEmpty() && parts.last().isEmpty() && parts[1] == "1") { "Invalid EPRT" }
        val octets = parts[2].split('.').map { part -> part.toIntOrNull() ?: throw IllegalArgumentException("Invalid EPRT") }
        require(octets.size == 4 && octets.all { it in 0..255 }) { "Invalid EPRT" }
        val address = InetAddress.getByAddress(octets.map(Int::toByte).toByteArray())
        val port = parts[3].toIntOrNull() ?: throw IllegalArgumentException("Invalid EPRT")
        return validatedActiveTarget(address, port, peerAddress)
    }

    private fun validatedActiveTarget(address: InetAddress, port: Int, peerAddress: InetAddress): InetSocketAddress {
        require(address.address.contentEquals(peerAddress.address)) { "Active FTP address must match the control client" }
        require(port in 1_024..65_535) { "Active FTP port is outside the allowed range" }
        return InetSocketAddress(address, port)
    }

    private fun resolvePath(cwd: File, raw: String): File {
        require(raw.length <= 4_096 && '\u0000' !in raw && '\\' !in raw) { "Netinkamas FTP kelias" }
        val candidate = if (raw.startsWith('/')) File(root, raw.trimStart('/')) else File(cwd, raw)
        val canonical = candidate.canonicalFile
        require(FileSystemRules.isContained(root, canonical)) { "Kelias išeina už pasirinkto katalogo" }
        return canonical
    }

    private fun resolveWritablePath(cwd: File, raw: String): File {
        val candidate = resolvePath(cwd, raw)
        require(candidate != root) { "Šakninio bendrinimo katalogo keisti negalima" }
        require(candidate.parentFile?.isDirectory == true && candidate.parentFile?.canWrite() == true) { "Paskirties aplankas neleidžia rašyti" }
        FileSystemRules.validateFileName(candidate.name).getOrThrow()
        return candidate
    }

    private fun virtualPath(file: File): String {
        val relative = root.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/')
        return if (relative.isBlank()) "/" else "/$relative"
    }

    private fun ftpDate(millis: Long): String = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date(millis.coerceAtLeast(0)))
    private fun randomCode(): String = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')

    private enum class AuthResult { ACCEPTED, REJECTED, LOCKED }
}
