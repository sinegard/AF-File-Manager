package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
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
import java.net.URI
import java.net.URLConnection
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Temporary Basic-auth WebDAV endpoint for local-network file transfer. */
class LanWebDavServer(
    rootDirectory: File,
    private val bindAddress: InetAddress,
    durationMinutes: Int = 15,
    private val requestedCode: String? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val onStopped: (String) -> Unit = {},
) : TemporaryLanServer {
    companion object {
        const val USERNAME = "af"
        const val MAX_HEADER_BYTES = 16 * 1_024
        const val MAX_REQUESTS = 10_000
        const val MAX_AUTH_FAILURES = 20
        const val MAX_TREE_ENTRIES = 100_000
        const val MAX_UPLOAD_BYTES = LanHttpServer.MAX_UPLOAD_BYTES
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUE = 16
    }

    private val root = rootDirectory.canonicalFile.also {
        require(it.isDirectory && it.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
    }
    private val durationMillis = durationMinutes.coerceIn(1, LanHttpServer.MAX_SESSION_MINUTES) * 60_000L
    private val running = AtomicBoolean(false)
    private val requestCount = AtomicInteger(0)
    private val authFailures = AtomicInteger(0)
    private val executor = ThreadPoolExecutor(
        MAX_CLIENTS,
        MAX_CLIENTS,
        10,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(MAX_QUEUE),
        { task -> Thread(task, "af-webdav-client").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var session: LanServerSession? = null

    override fun start(): LanServerSession {
        check(running.compareAndSet(false, true)) { "WebDAV serveris jau veikia" }
        require(bindAddress is Inet4Address && (bindAddress.isSiteLocalAddress || bindAddress.isLoopbackAddress)) {
            "Serveris gali klausytis tik privačiame IPv4 tinkle"
        }
        val socket = ServerSocket().apply {
            reuseAddress = false
            soTimeout = 1_000
            bind(InetSocketAddress(bindAddress, 0), MAX_QUEUE)
        }
        serverSocket = socket
        val code = requestedCode?.also { require(it.matches(Regex("[0-9]{8}"))) } ?: randomCode()
        val created = LanServerSession(
            address = requireNotNull(bindAddress.hostAddress),
            port = socket.localPort,
            code = code,
            expiresAtMillis = Math.addExact(nowMillis(), durationMillis),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
            scheme = "http",
            username = USERNAME,
        )
        session = created
        acceptThread = Thread({ acceptLoop(created) }, "af-webdav-accept").apply { isDaemon = true; start() }
        return created
    }

    override fun close() = stop("WebDAV serveris sustabdytas")

    override fun stop(reason: String) {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        executor.shutdownNow()
        session = null
        onStopped(reason.take(200))
    }

    private fun acceptLoop(active: LanServerSession) {
        try {
            while (running.get()) {
                if (nowMillis() >= active.expiresAtMillis) return stop("WebDAV sesijos laikas baigėsi")
                if (requestCount.get() >= MAX_REQUESTS) return stop("WebDAV sesijos užklausų riba pasiekta")
                try {
                    val socket = serverSocket?.accept() ?: break
                    socket.soTimeout = SOCKET_TIMEOUT_MILLIS
                    requestCount.incrementAndGet()
                    runCatching { executor.execute { socket.use(::handle) } }.onFailure { socket.close() }
                } catch (_: SocketTimeoutException) {
                    // Re-check expiry.
                }
            }
        } catch (_: Throwable) {
            if (running.get()) stop("WebDAV serverio ryšys nutrūko")
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1_024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1_024)
        val request = try { readRequest(input) } catch (_: Throwable) { null }
        if (request == null) return write(output, 400, "text/plain; charset=utf-8", "Bad request".toByteArray())
        val active = session
        if (!running.get() || active == null || nowMillis() >= active.expiresAtMillis) {
            return write(output, 410, "text/plain; charset=utf-8", "Session expired".toByteArray())
        }
        if (!authenticated(request, active)) {
            authFailures.incrementAndGet()
            return write(output, 401, "text/plain; charset=utf-8", "Authentication required".toByteArray(), listOf("WWW-Authenticate: Basic realm=\"AF File Manager\""))
        }
        if (authFailures.get() >= MAX_AUTH_FAILURES) {
            return write(output, 403, "text/plain; charset=utf-8", "Authentication locked".toByteArray())
        }
        runCatching { dispatch(request, input, output) }.onFailure { error ->
            runCatching { write(output, if (error is SecurityException || error is IllegalArgumentException) 400 else 500, "text/plain; charset=utf-8", (error.message ?: "Server error").take(300).toByteArray()) }
        }
    }

    private fun dispatch(request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        val target = resolve(request.path)
        when (request.method) {
            "OPTIONS" -> write(output, 200, "text/plain", ByteArray(0), listOf("DAV: 1, 2", "Allow: OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY"))
            "PROPFIND" -> propfind(target, request.headers["depth"].orEmpty(), output)
            "GET", "HEAD" -> get(target, output, headOnly = request.method == "HEAD")
            "PUT" -> put(target, request.contentLength, input, output)
            "MKCOL" -> {
                require(target != root && !target.exists() && target.parentFile?.isDirectory == true) { "Katalogo sukurti negalima" }
                require(target.mkdir()) { "Katalogo sukurti nepavyko" }
                write(output, 201, "text/plain", ByteArray(0))
            }
            "DELETE" -> {
                require(target != root && target.exists()) { "Bendrinimo šaknies pašalinti negalima" }
                deleteBounded(target, Counter())
                write(output, 204, "text/plain", ByteArray(0))
            }
            "MOVE" -> move(target, request, output)
            "COPY" -> copy(target, request, output)
            else -> write(output, 405, "text/plain", ByteArray(0), listOf("Allow: OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY"))
        }
    }

    private fun propfind(target: File, depth: String, output: BufferedOutputStream) {
        require(target.exists()) { "Kelias neegzistuoja" }
        val entries = if (depth == "0" || !target.isDirectory) listOf(target) else listOf(target) + target.listFiles().orEmpty().take(MAX_TREE_ENTRIES)
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><D:multistatus xmlns:D=\"DAV:\">")
            entries.forEach { file ->
                val href = davHref(file)
                append("<D:response><D:href>").append(xml(href)).append("</D:href><D:propstat><D:prop>")
                append("<D:displayname>").append(xml(file.name.ifBlank { root.name })).append("</D:displayname>")
                if (file.isDirectory) append("<D:resourcetype><D:collection/></D:resourcetype>")
                else append("<D:resourcetype/><D:getcontentlength>").append(file.length()).append("</D:getcontentlength>")
                append("<D:getlastmodified>").append(httpDate(file.lastModified())).append("</D:getlastmodified>")
                append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>")
            }
            append("</D:multistatus>")
        }.toByteArray(StandardCharsets.UTF_8)
        write(output, 207, "application/xml; charset=utf-8", body)
    }

    private fun get(target: File, output: BufferedOutputStream, headOnly: Boolean) {
        require(target.isFile && target.canRead()) { "Failas nepasiekiamas" }
        val type = URLConnection.guessContentTypeFromName(target.name) ?: "application/octet-stream"
        writeHeaders(output, 200, type, target.length(), emptyList())
        if (!headOnly) target.inputStream().use { it.copyTo(output, 256 * 1_024) }
        output.flush()
    }

    private fun put(target: File, length: Long, input: BufferedInputStream, output: BufferedOutputStream) {
        require(length in 0..MAX_UPLOAD_BYTES) { "Reikalingas ne didesnis kaip 1 GB Content-Length" }
        require(target != root && target.parentFile?.isDirectory == true && target.parentFile?.canWrite() == true) { "Paskirties katalogas neleidžia rašyti" }
        FileSystemRules.validateFileName(target.name).getOrThrow()
        val existed = target.exists()
        require(!target.isDirectory) { "Failo vietoje yra katalogas" }
        val partial = File(target.parentFile, ".af-webdav-${UUID.randomUUID()}.partial")
        var remaining = length
        try {
            FileOutputStream(partial).use { fileOutput ->
                val buffer = ByteArray(256 * 1_024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) error("Įkėlimas nutrūko")
                    fileOutput.write(buffer, 0, read)
                    remaining -= read
                }
                fileOutput.fd.sync()
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            write(output, if (existed) 204 else 201, "text/plain", ByteArray(0))
        } finally { if (partial.exists()) partial.delete() }
    }

    private fun move(source: File, request: Request, output: BufferedOutputStream) {
        require(source != root && source.exists()) { "Šaltinis nepasiekiamas" }
        val target = destination(request)
        require(target != root && target.parentFile?.isDirectory == true) { "Paskirtis nepasiekiama" }
        if (target.exists()) {
            require(!request.headers["overwrite"].equals("F", true)) { "Paskirtis jau egzistuoja" }
            deleteBounded(target, Counter())
        }
        require(source.renameTo(target)) { "Perkelti nepavyko" }
        write(output, 201, "text/plain", ByteArray(0))
    }

    private fun copy(source: File, request: Request, output: BufferedOutputStream) {
        require(source.exists()) { "Šaltinis nepasiekiamas" }
        val target = destination(request)
        require(target != root && !FileSystemRules.isContained(source, target)) { "Negalima kopijuoti į šaltinio vidų" }
        if (target.exists()) {
            require(!request.headers["overwrite"].equals("F", true)) { "Paskirtis jau egzistuoja" }
            deleteBounded(target, Counter())
        }
        copyBounded(source, target, Counter())
        write(output, 201, "text/plain", ByteArray(0))
    }

    private fun destination(request: Request): File {
        val raw = request.headers["destination"] ?: throw IllegalArgumentException("Trūksta Destination antraštės")
        val path = runCatching { URI(raw).rawPath }.getOrNull() ?: raw.substringAfter("//").substringAfter('/', "/")
        return resolve(path)
    }

    private fun resolve(rawPath: String): File {
        require(rawPath.length <= 4_096 && '\u0000' !in rawPath && '\\' !in rawPath) { "Netinkamas WebDAV kelias" }
        val decoded = URLDecoder.decode(rawPath.substringBefore('?'), StandardCharsets.UTF_8.name()).trimStart('/')
        val candidate = File(root, decoded).canonicalFile
        require(FileSystemRules.isContained(root, candidate)) { "Kelias išeina už pasirinkto katalogo" }
        return candidate
    }

    private fun copyBounded(source: File, target: File, counter: Counter) {
        counter.add()
        require(!Files.isSymbolicLink(source.toPath())) { "Simbolinės nuorodos nekopijuojamos" }
        if (source.isDirectory) {
            require(target.mkdir()) { "Katalogo sukurti nepavyko" }
            source.listFiles()?.forEach { copyBounded(it, File(target, it.name), counter) } ?: error("Katalogas neperskaitomas")
        } else source.inputStream().use { input -> target.outputStream().use { input.copyTo(it, 256 * 1_024) } }
    }

    private fun deleteBounded(file: File, counter: Counter) {
        counter.add()
        require(!Files.isSymbolicLink(file.toPath())) { "Simbolinės nuorodos nešalinamos" }
        if (file.isDirectory) file.listFiles()?.forEach { deleteBounded(it, counter) } ?: error("Katalogas neperskaitomas")
        require(file.delete()) { "Pašalinti nepavyko" }
    }

    private fun authenticated(request: Request, active: LanServerSession): Boolean {
        if (authFailures.get() >= MAX_AUTH_FAILURES) return false
        val value = request.headers["authorization"] ?: return false
        if (!value.startsWith("Basic ", true)) return false
        val decoded = runCatching { String(Base64.getDecoder().decode(value.substringAfter(' ').trim()), StandardCharsets.UTF_8) }.getOrNull() ?: return false
        return decoded == "$USERNAME:${active.code}"
    }

    private data class Request(val method: String, val path: String, val headers: Map<String, String>, val contentLength: Long)

    private fun readRequest(input: BufferedInputStream): Request? {
        var consumed = 0
        fun line(): String? {
            val result = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (result.isEmpty()) null else result.toString()
                if (++consumed > MAX_HEADER_BYTES) throw IllegalArgumentException("Antraštės per didelės")
                if (byte == '\n'.code) return result.toString().trimEnd('\r')
                result.append(byte.toChar())
            }
        }
        val first = line()?.split(' ') ?: return null
        if (first.size != 3 || first[2] !in setOf("HTTP/1.0", "HTTP/1.1")) return null
        val method = first[0].uppercase(Locale.ROOT)
        val allowed = setOf("OPTIONS", "PROPFIND", "GET", "HEAD", "PUT", "DELETE", "MKCOL", "MOVE", "COPY")
        if (method !in allowed || !first[1].startsWith('/')) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) return null
            headers[header.substring(0, separator).trim().lowercase(Locale.ROOT)] = header.substring(separator + 1).trim().take(8_192)
        }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        require(length >= 0) { "Netinkamas turinio dydis" }
        return Request(method, first[1], headers, length)
    }

    private fun write(output: BufferedOutputStream, status: Int, type: String, body: ByteArray, extra: List<String> = emptyList()) {
        writeHeaders(output, status, type, body.size.toLong(), extra)
        output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: BufferedOutputStream, status: Int, type: String, length: Long, extra: List<String>) {
        val reason = mapOf(200 to "OK", 201 to "Created", 204 to "No Content", 207 to "Multi-Status", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 405 to "Method Not Allowed", 410 to "Gone")[status] ?: "Error"
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\nContent-Type: $type\r\nContent-Length: $length\r\nCache-Control: no-store\r\n")
            extra.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun davHref(file: File): String {
        val relative = root.toPath().relativize(file.toPath()).joinToString("/") { URLEncoder.encode(it.toString(), StandardCharsets.UTF_8.name()).replace("+", "%20") }
        return "/$relative${if (file.isDirectory && relative.isNotEmpty()) "/" else ""}"
    }

    private fun httpDate(millis: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(millis.coerceAtLeast(0)))
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun randomCode(): String = SecureRandom().nextInt(100_000_000).toString().padStart(8, '0')

    private class Counter {
        private var value = 0
        fun add() {
            value = Math.addExact(value, 1)
            require(value <= MAX_TREE_ENTRIES) { "Failų medis viršijo saugos ribą" }
        }
    }
}
