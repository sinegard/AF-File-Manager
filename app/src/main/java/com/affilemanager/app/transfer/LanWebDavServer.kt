package com.affilemanager.app.transfer

import com.affilemanager.app.core.FileSystemRules
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
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
import java.security.MessageDigest
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

/** Temporary, bounded WebDAV endpoint for local-network file transfer. */
class LanWebDavServer(
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
        const val MAX_HEADER_BYTES = 16 * 1_024
        const val MAX_REQUESTS = 10_000
        const val MAX_AUTH_FAILURES = 20
        const val AUTH_LOCK_MILLIS = 30_000L
        const val MAX_TREE_ENTRIES = 100_000
        const val MAX_LOCKS = 256
        const val MAX_LOCK_BODY_BYTES = 64 * 1_024
        const val MAX_LOCK_SECONDS = 3_600L
        const val MAX_UPLOAD_BYTES = LanHttpServer.MAX_UPLOAD_BYTES
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
        private const val MAX_CLIENTS = 4
        private const val MAX_QUEUE = 16
        private const val ALLOW = "OPTIONS, PROPFIND, GET, HEAD, PUT, DELETE, MKCOL, MOVE, COPY, LOCK, UNLOCK"
        private const val READ_ONLY_ALLOW = "OPTIONS, PROPFIND, GET, HEAD"
        private val READ_ONLY_METHODS = setOf("OPTIONS", "PROPFIND", "GET", "HEAD")
        private val LOCK_TOKEN_PATTERN = Regex("<(opaquelocktoken:[^>]+)>", RegexOption.IGNORE_CASE)
    }

    private val root = rootDirectory.canonicalFile.also {
        require(it.isDirectory && it.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
    }
    private val durationMillis = durationMinutes.coerceIn(1, LanHttpServer.MAX_SESSION_MINUTES) * 60_000L
    private val username = validateRequestedUsername(requestedUsername, USERNAME)
    private val running = AtomicBoolean(false)
    private val requestCount = AtomicInteger(0)
    private val authGuard = Any()
    private var authFailures = 0
    private var authLockedUntilMillis = 0L
    private val lockGuard = Any()
    private val locks = linkedMapOf<String, LockRecord>()
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
            bind(InetSocketAddress(bindAddress, validateRequestedPort(requestedPort)), MAX_QUEUE)
        }
        serverSocket = socket
        val code = validateRequestedSecret(requestedCode) ?: randomCode()
        val created = LanServerSession(
            address = requireNotNull(bindAddress.hostAddress),
            port = socket.localPort,
            code = code,
            expiresAtMillis = Math.addExact(nowMillis(), durationMillis),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
            scheme = "http",
            username = username,
            readOnly = readOnly,
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
        synchronized(lockGuard) { locks.clear() }
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
        val request = try {
            readRequest(input)
        } catch (error: Throwable) {
            return writeError(output, 400, error.message ?: "Bad request")
        } ?: return writeError(output, 400, "Bad request")
        val active = session
        if (!running.get() || active == null || nowMillis() >= active.expiresAtMillis) {
            return writeError(output, 410, "Session expired")
        }
        when (authenticate(request, active)) {
            AuthResult.REJECTED -> return write(
                output,
                401,
                "text/plain; charset=utf-8",
                "Authentication required".toByteArray(),
                listOf("WWW-Authenticate: Basic realm=\"AF File Manager\""),
            )
            AuthResult.LOCKED -> {
                val retrySeconds = synchronized(authGuard) {
                    ((authLockedUntilMillis - nowMillis()).coerceAtLeast(1L) + 999L) / 1_000L
                }
                return write(output, 429, "text/plain; charset=utf-8", "Authentication temporarily locked".toByteArray(), listOf("Retry-After: $retrySeconds"))
            }
            AuthResult.ACCEPTED -> Unit
        }
        val expectation = request.headers["expect"]
        if (expectation != null) {
            if (!expectation.equals("100-continue", ignoreCase = true)) return writeError(output, 417, "Expectation not supported")
            if (request.hasBody) writeContinue(output)
        }
        runCatching { dispatch(request, input, output) }.onFailure { error ->
            val status = when (error) {
                is WebDavStatusException -> error.status
                is SecurityException, is IllegalArgumentException -> 400
                else -> 500
            }
            runCatching { writeError(output, status, error.message ?: "Server error") }
        }
    }

    private fun dispatch(request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        val target = resolve(request.path)
        val allow = if (readOnly) READ_ONLY_ALLOW else ALLOW
        if (readOnly && request.method !in READ_ONLY_METHODS) {
            write(output, 403, "text/plain; charset=utf-8", "This session is read-only".toByteArray(), listOf("Allow: $allow"))
            return
        }
        when (request.method) {
            "OPTIONS" -> write(output, 200, "text/plain", ByteArray(0), listOf("DAV: 1, 2", "Allow: $allow"))
            "PROPFIND" -> propfind(target, request.headers["depth"].orEmpty(), output)
            "GET", "HEAD" -> get(target, output, headOnly = request.method == "HEAD")
            "PUT" -> {
                requireWriteAllowed(target, request)
                put(target, request, input, output)
            }
            "MKCOL" -> {
                requireWriteAllowed(target, request)
                require(target != root && !target.exists() && target.parentFile?.isDirectory == true) { "Katalogo sukurti negalima" }
                require(target.mkdir()) { "Katalogo sukurti nepavyko" }
                write(output, 201, "text/plain", ByteArray(0))
            }
            "DELETE" -> {
                requireWriteAllowed(target, request)
                require(target != root && target.exists()) { "Bendrinimo šaknies pašalinti negalima" }
                deleteBounded(target, Counter())
                removeLocksUnder(target)
                write(output, 204, "text/plain", ByteArray(0))
            }
            "MOVE" -> move(target, request, output)
            "COPY" -> copy(target, request, output)
            "LOCK" -> lock(target, request, input, output)
            "UNLOCK" -> unlock(target, request, output)
            else -> write(output, 405, "text/plain", ByteArray(0), listOf("Allow: $allow"))
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
                append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
                exactLock(file)?.let { record -> append(lockDiscoveryXml(record, includePropertyWrapper = true)) }
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

    private fun put(target: File, request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        if (request.bodyKind == BodyKind.FIXED) require(request.contentLength in 0..MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą" }
        require(target != root && target.parentFile?.isDirectory == true && target.parentFile?.canWrite() == true) { "Paskirties katalogas neleidžia rašyti" }
        FileSystemRules.validateFileName(target.name).getOrThrow()
        val existed = target.exists()
        require(!target.isDirectory) { "Failo vietoje yra katalogas" }
        val partial = File(target.parentFile, ".af-webdav-${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(partial).use { fileOutput ->
                copyRequestBody(request, input, fileOutput, MAX_UPLOAD_BYTES)
                fileOutput.fd.sync()
            }
            Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            write(output, if (existed) 204 else 201, "text/plain", ByteArray(0))
        } finally { if (partial.exists()) partial.delete() }
    }

    private fun move(source: File, request: Request, output: BufferedOutputStream) {
        require(source != root && source.exists()) { "Šaltinis nepasiekiamas" }
        val target = destination(request)
        requireWriteAllowed(source, request)
        requireWriteAllowed(target, request)
        require(target != root && target.parentFile?.isDirectory == true) { "Paskirtis nepasiekiama" }
        if (target.exists()) {
            require(!request.headers["overwrite"].equals("F", true)) { "Paskirtis jau egzistuoja" }
            deleteBounded(target, Counter())
        }
        require(source.renameTo(target)) { "Perkelti nepavyko" }
        removeLocksUnder(source)
        write(output, 201, "text/plain", ByteArray(0))
    }

    private fun copy(source: File, request: Request, output: BufferedOutputStream) {
        require(source.exists()) { "Šaltinis nepasiekiamas" }
        val target = destination(request)
        requireWriteAllowed(target, request)
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
        // URLDecoder follows HTML-form rules and would otherwise turn a literal '+' in a path into a space.
        val encodedPath = rawPath.substringBefore('?').replace("+", "%2B")
        val decoded = URLDecoder.decode(encodedPath, StandardCharsets.UTF_8.name()).trimStart('/')
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

    private fun authenticate(request: Request, active: LanServerSession): AuthResult = synchronized(authGuard) {
        val now = nowMillis()
        if (now < authLockedUntilMillis) return@synchronized AuthResult.LOCKED
        if (authLockedUntilMillis != 0L) {
            authLockedUntilMillis = 0L
            authFailures = 0
        }
        val value = request.headers["authorization"]
        val supplied = if (value?.startsWith("Basic ", true) == true) {
            runCatching { Base64.getDecoder().decode(value.substringAfter(' ').trim()) }.getOrNull()
        } else {
            null
        }
        val expected = "${active.username}:${active.code}".toByteArray(StandardCharsets.UTF_8)
        val matches = supplied != null && MessageDigest.isEqual(supplied, expected)
        if (matches) {
            authFailures = 0
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

    private data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val bodyKind: BodyKind,
        val contentLength: Long,
    ) {
        val hasBody: Boolean get() = bodyKind == BodyKind.CHUNKED || contentLength > 0

        fun conditionTokens(): Set<String> = sequenceOf(headers["if"], headers["lock-token"])
            .filterNotNull()
            .flatMap { value -> LOCK_TOKEN_PATTERN.findAll(value).map { match -> match.groupValues[1] } }
            .toSet()
    }

    private enum class BodyKind { NONE, FIXED, CHUNKED }

    private enum class AuthResult { ACCEPTED, REJECTED, LOCKED }

    private data class LockRecord(
        val path: String,
        val token: String,
        val depthInfinity: Boolean,
        val expiresAtMillis: Long,
    )

    private class WebDavStatusException(val status: Int, message: String) : IllegalArgumentException(message)

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
        if (!method.matches(Regex("[A-Z]{1,16}")) || !first[1].startsWith('/')) return null
        val headers = linkedMapOf<String, String>()
        while (true) {
            val header = line() ?: return null
            if (header.isEmpty()) break
            val separator = header.indexOf(':')
            if (separator <= 0) return null
            val value = header.substring(separator + 1).trim()
            require(value.length <= 8_192) { "Header value is too large" }
            headers[header.substring(0, separator).trim().lowercase(Locale.ROOT)] = value
        }
        val contentLengthHeader = headers["content-length"]
        val transferEncoding = headers["transfer-encoding"]
        require(contentLengthHeader == null || transferEncoding == null) { "Content-Length and Transfer-Encoding cannot be used together" }
        val bodyKind: BodyKind
        val length: Long
        when {
            transferEncoding != null -> {
                require(transferEncoding.equals("chunked", ignoreCase = true)) { "Unsupported Transfer-Encoding" }
                bodyKind = BodyKind.CHUNKED
                length = 0L
            }
            contentLengthHeader != null -> {
                length = contentLengthHeader.toLongOrNull() ?: throw IllegalArgumentException("Netinkamas turinio dydis")
                require(length >= 0) { "Netinkamas turinio dydis" }
                bodyKind = BodyKind.FIXED
            }
            else -> {
                bodyKind = BodyKind.NONE
                length = 0L
            }
        }
        return Request(method, first[1], headers, bodyKind, length)
    }

    private fun copyRequestBody(
        request: Request,
        input: BufferedInputStream,
        output: OutputStream,
        maximumBytes: Long,
    ): Long = when (request.bodyKind) {
        BodyKind.NONE -> 0L
        BodyKind.FIXED -> {
            require(request.contentLength <= maximumBytes) { "Request body exceeds the allowed limit" }
            copyFixed(input, output, request.contentLength)
        }
        BodyKind.CHUNKED -> copyChunked(input, output, maximumBytes)
    }

    private fun copyFixed(input: BufferedInputStream, output: OutputStream, length: Long): Long {
        var remaining = length
        val buffer = ByteArray(256 * 1_024)
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) throw WebDavStatusException(400, "Request body ended unexpectedly")
            output.write(buffer, 0, read)
            remaining -= read
        }
        return length
    }

    private fun copyChunked(input: BufferedInputStream, output: OutputStream, maximumBytes: Long): Long {
        var total = 0L
        while (true) {
            val sizeText = readCrlfLine(input, 8_192).substringBefore(';').trim()
            require(sizeText.matches(Regex("[0-9A-Fa-f]{1,16}"))) { "Invalid chunk size" }
            val size = sizeText.toLongOrNull(16) ?: throw IllegalArgumentException("Invalid chunk size")
            if (size == 0L) {
                var trailerBytes = 0
                while (true) {
                    val trailer = readCrlfLine(input, 8_192)
                    trailerBytes = Math.addExact(trailerBytes, trailer.length + 2)
                    require(trailerBytes <= MAX_HEADER_BYTES) { "Chunk trailers are too large" }
                    if (trailer.isEmpty()) break
                    require(':' in trailer) { "Invalid chunk trailer" }
                }
                return total
            }
            total = Math.addExact(total, size)
            require(total <= maximumBytes) { "Request body exceeds the allowed limit" }
            copyFixed(input, output, size)
            require(input.read() == '\r'.code && input.read() == '\n'.code) { "Invalid chunk ending" }
        }
    }

    private fun readCrlfLine(input: BufferedInputStream, maximumBytes: Int): String {
        val result = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) throw WebDavStatusException(400, "Request body ended unexpectedly")
            if (byte == '\n'.code) {
                require(result.isNotEmpty() && result.last() == '\r') { "CRLF line ending required" }
                result.setLength(result.length - 1)
                return result.toString()
            }
            require(result.length < maximumBytes) { "Request line is too long" }
            result.append(byte.toChar())
        }
    }

    private fun lock(target: File, request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        val bodyOutput = ByteArrayOutputStream()
        copyRequestBody(request, input, bodyOutput, MAX_LOCK_BODY_BYTES.toLong())
        val body = bodyOutput.toString(StandardCharsets.UTF_8.name())
        val suppliedTokens = request.conditionTokens()
        val now = nowMillis()
        val requestedSeconds = lockTimeoutSeconds(request.headers["timeout"])
        val sessionExpiry = session?.expiresAtMillis ?: Math.addExact(now, requestedSeconds * 1_000L)
        val expiresAt = minOf(Math.addExact(now, requestedSeconds * 1_000L), sessionExpiry)
        val targetPath = target.canonicalPath
        val record = synchronized(lockGuard) {
            cleanupExpiredLocks(now)
            val existing = locks[targetPath]
            if (body.isBlank()) {
                if (existing == null || existing.token !in suppliedTokens) {
                    throw WebDavStatusException(412, "Lock token does not match")
                }
                existing.copy(expiresAtMillis = expiresAt).also { locks[targetPath] = it }
            } else {
                val normalizedBody = body.lowercase(Locale.ROOT)
                require("lockscope" in normalizedBody && "exclusive" in normalizedBody && "write" in normalizedBody) {
                    "Only exclusive write locks are supported"
                }
                val depthInfinity = target.isDirectory && !request.headers["depth"].equals("0", ignoreCase = true)
                val conflict = locks.values.firstOrNull { other -> locksConflict(other, targetPath, depthInfinity) }
                if (conflict != null) throw WebDavStatusException(423, "Resource is locked")
                if (locks.size >= MAX_LOCKS) throw WebDavStatusException(507, "Lock limit reached")
                LockRecord(
                    path = targetPath,
                    token = "opaquelocktoken:${UUID.randomUUID()}",
                    depthInfinity = depthInfinity,
                    expiresAtMillis = expiresAt,
                ).also { locks[targetPath] = it }
            }
        }
        val response = lockDiscoveryXml(record, includePropertyWrapper = false).toByteArray(StandardCharsets.UTF_8)
        write(
            output,
            if (target.exists()) 200 else 201,
            "application/xml; charset=utf-8",
            response,
            listOf("Lock-Token: <${record.token}>", "Timeout: Second-${remainingLockSeconds(record)}"),
        )
    }

    private fun unlock(target: File, request: Request, output: BufferedOutputStream) {
        val token = request.conditionTokens().singleOrNull() ?: throw WebDavStatusException(400, "Invalid Lock-Token header")
        val removed = synchronized(lockGuard) {
            cleanupExpiredLocks(nowMillis())
            val current = locks[target.canonicalPath]
            if (current?.token == token) locks.remove(target.canonicalPath) != null else false
        }
        if (!removed) throw WebDavStatusException(409, "Lock token does not match")
        write(output, 204, "text/plain", ByteArray(0))
    }

    private fun requireWriteAllowed(target: File, request: Request) {
        val supplied = request.conditionTokens()
        val blocking = synchronized(lockGuard) {
            cleanupExpiredLocks(nowMillis())
            locks.values.filter { record -> lockAffects(record, target.canonicalPath) }
        }
        if (blocking.any { it.token !in supplied }) throw WebDavStatusException(423, "Resource is locked")
    }

    private fun exactLock(target: File): LockRecord? = synchronized(lockGuard) {
        cleanupExpiredLocks(nowMillis())
        locks[target.canonicalPath]
    }

    private fun removeLocksUnder(target: File) = synchronized(lockGuard) {
        val path = target.canonicalPath
        locks.entries.removeAll { (_, record) -> record.path == path || record.path.startsWith(path + File.separator) }
    }

    private fun cleanupExpiredLocks(now: Long) {
        locks.entries.removeAll { (_, record) -> record.expiresAtMillis <= now }
    }

    private fun locksConflict(existing: LockRecord, targetPath: String, targetDepthInfinity: Boolean): Boolean =
        lockAffects(existing, targetPath) || (targetDepthInfinity && existing.path.startsWith(targetPath + File.separator))

    private fun lockAffects(record: LockRecord, targetPath: String): Boolean =
        record.path == targetPath || (record.depthInfinity && targetPath.startsWith(record.path + File.separator))

    private fun lockTimeoutSeconds(raw: String?): Long {
        if (raw.isNullOrBlank()) return minOf(600L, MAX_LOCK_SECONDS)
        raw.split(',').forEach { candidate ->
            val value = candidate.trim()
            if (value.equals("Infinite", ignoreCase = true)) return MAX_LOCK_SECONDS
            if (value.startsWith("Second-", ignoreCase = true)) {
                val seconds = value.substringAfter('-').toLongOrNull()
                if (seconds != null && seconds > 0) return seconds.coerceAtMost(MAX_LOCK_SECONDS)
            }
        }
        throw WebDavStatusException(400, "Invalid Timeout header")
    }

    private fun lockDiscoveryXml(record: LockRecord, includePropertyWrapper: Boolean): String {
        val active = buildString {
            append("<D:activelock><D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope>")
            append("<D:depth>").append(if (record.depthInfinity) "Infinity" else "0").append("</D:depth>")
            append("<D:owner><D:href>AF File Manager</D:href></D:owner>")
            append("<D:timeout>Second-").append(remainingLockSeconds(record)).append("</D:timeout>")
            append("<D:locktoken><D:href>").append(xml(record.token)).append("</D:href></D:locktoken>")
            append("<D:lockroot><D:href>").append(xml(davHref(File(record.path)))).append("</D:href></D:lockroot></D:activelock>")
        }
        return if (includePropertyWrapper) {
            "<D:lockdiscovery>$active</D:lockdiscovery>"
        } else {
            "<?xml version=\"1.0\" encoding=\"utf-8\"?><D:prop xmlns:D=\"DAV:\"><D:lockdiscovery>$active</D:lockdiscovery></D:prop>"
        }
    }

    private fun remainingLockSeconds(record: LockRecord): Long =
        ((record.expiresAtMillis - nowMillis()).coerceAtLeast(1L) + 999L) / 1_000L

    private fun writeContinue(output: BufferedOutputStream) {
        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun writeError(output: BufferedOutputStream, status: Int, message: String) {
        write(output, status, "text/plain; charset=utf-8", message.take(300).toByteArray(StandardCharsets.UTF_8))
    }

    private fun write(output: BufferedOutputStream, status: Int, type: String, body: ByteArray, extra: List<String> = emptyList()) {
        writeHeaders(output, status, type, body.size.toLong(), extra)
        output.write(body)
        output.flush()
    }

    private fun writeHeaders(output: BufferedOutputStream, status: Int, type: String, length: Long, extra: List<String>) {
        val reason = mapOf(
            200 to "OK",
            201 to "Created",
            204 to "No Content",
            207 to "Multi-Status",
            400 to "Bad Request",
            401 to "Unauthorized",
            403 to "Forbidden",
            405 to "Method Not Allowed",
            409 to "Conflict",
            410 to "Gone",
            412 to "Precondition Failed",
            417 to "Expectation Failed",
            423 to "Locked",
            429 to "Too Many Requests",
            500 to "Internal Server Error",
            507 to "Insufficient Storage",
        )[status] ?: "Error"
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
