package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
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
    private val requestedCode: String? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStopped: (String) -> Unit = {},
) : TemporaryLanServer {
    companion object {
        const val USERNAME = "af"
        const val MAX_COMMAND_BYTES = 8 * 1_024
        const val MAX_COMMANDS_PER_SESSION = 10_000
        const val MAX_AUTH_FAILURES = 20
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
    private val running = AtomicBoolean(false)
    private val commandCount = AtomicInteger(0)
    private val authFailures = AtomicInteger(0)
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
            bind(InetSocketAddress(bindAddress, 0), MAX_QUEUE)
        }
        controlSocket = socket
        val code = requestedCode?.also { require(it.matches(Regex("[0-9]{8}"))) } ?: randomCode()
        val created = LanServerSession(
            address = requireNotNull(bindAddress.hostAddress),
            port = socket.localPort,
            code = code,
            expiresAtMillis = Math.addExact(nowMillis(), durationMillis),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
            scheme = "ftp",
            username = USERNAME,
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

        fun openPassive(): ServerSocket {
            passive?.close()
            return ServerSocket().apply {
                reuseAddress = false
                soTimeout = DATA_TIMEOUT_MILLIS
                bind(InetSocketAddress(bindAddress, 0), 1)
                passive = this
            }
        }

        fun withData(block: (Socket) -> Unit) {
            val server = passive
            if (server == null) {
                reply(425, "Use PASV or EPSV first")
                return
            }
            passive = null
            reply(150, "Opening data connection")
            try {
                server.use { listener -> listener.accept().use(block) }
                reply(226, "Transfer complete")
            } catch (_: SocketTimeoutException) {
                reply(425, "Data connection timed out")
            }
        }

        reply(220, "AF File Manager temporary FTP server")
        try {
            while (running.get() && nowMillis() < active.expiresAtMillis) {
                val line = reader.readLine() ?: break
                require(line.toByteArray(StandardCharsets.UTF_8).size <= MAX_COMMAND_BYTES) { "FTP komanda per ilga" }
                if (commandCount.incrementAndGet() > MAX_COMMANDS_PER_SESSION) break
                val command = line.substringBefore(' ').uppercase(Locale.ROOT)
                val argument = line.substringAfter(' ', "").trim()
                try {
                    when (command) {
                    "USER" -> {
                        acceptedUser = argument == USERNAME
                        reply(if (acceptedUser) 331 else 530, if (acceptedUser) "Password required" else "Unknown user")
                    }
                    "PASS" -> {
                        authenticated = acceptedUser && argument == active.code && authFailures.get() < MAX_AUTH_FAILURES
                        if (!authenticated) authFailures.incrementAndGet()
                        reply(if (authenticated) 230 else 530, if (authenticated) "Logged in" else "Login incorrect")
                    }
                    "SYST" -> reply(215, "UNIX Type: L8")
                    "FEAT" -> {
                        writer.write("211-Features\r\n UTF8\r\n EPSV\r\n SIZE\r\n MDTM\r\n MLSD\r\n211 End\r\n")
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
                    "STOR" -> if (requireAuth()) {
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
                    "MKD", "XMKD" -> if (requireAuth()) {
                        val dir = resolveWritablePath(cwd, argument)
                        if (!dir.exists() && dir.mkdir()) reply(257, "\"${virtualPath(dir)}\" created") else reply(550, "Create failed")
                    }
                    "DELE" -> if (requireAuth()) {
                        val file = resolvePath(cwd, argument)
                        if (file != root && file.isFile && file.delete()) reply(250, "Deleted") else reply(550, "Delete failed")
                    }
                    "RMD", "XRMD" -> if (requireAuth()) {
                        val dir = resolvePath(cwd, argument)
                        if (dir != root && dir.isDirectory && dir.list()?.isEmpty() == true && dir.delete()) reply(250, "Removed") else reply(550, "Remove failed")
                    }
                    "RNFR" -> if (requireAuth()) {
                        val source = resolvePath(cwd, argument)
                        if (source != root && source.exists()) { renameFrom = source; reply(350, "Ready for RNTO") } else reply(550, "Source unavailable")
                    }
                    "RNTO" -> if (requireAuth()) {
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
                    reply(550, (error.message ?: "Request rejected").take(300))
                }
            }
        } finally {
            runCatching { passive?.close() }
        }
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
}
