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
) {
    val url: String get() = "$scheme://$address:$port/"
}

internal interface TemporaryLanServer : AutoCloseable {
    fun start(): LanServerSession
    fun stop(reason: String)
}

class LanHttpServer(
    rootDirectory: File,
    private val bindAddress: InetAddress,
    durationMinutes: Int = 15,
    private val requestedCode: String? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val language: String = AppLanguageManager.ENGLISH,
    private val onStopped: (String) -> Unit = {},
) : TemporaryLanServer {
    companion object {
        const val MAX_SESSION_MINUTES = 60
        const val MAX_CONCURRENT_REQUESTS = 4
        const val MAX_QUEUED_REQUESTS = 16
        const val MAX_REQUESTS_PER_SESSION = 10_000
        const val MAX_AUTH_FAILURES = 20
        const val MAX_HEADER_BYTES = 16 * 1_024
        const val MAX_UPLOAD_BYTES = 1L * 1_024 * 1_024 * 1_024
        private const val SOCKET_TIMEOUT_MILLIS = 30_000
    }

    private val root = rootDirectory.canonicalFile.also {
        require(it.isDirectory && it.canRead()) { "Pasirinktas katalogas nepasiekiamas" }
    }
    private val durationMillis = durationMinutes.coerceIn(1, MAX_SESSION_MINUTES) * 60_000L
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
            bind(InetSocketAddress(bindAddress, 0), MAX_QUEUED_REQUESTS)
        }
        serverSocket = socket
        val code = requestedCode?.also { require(it.matches(Regex("[0-9]{8}"))) } ?: randomCode()
        val created = LanServerSession(
            address = bindAddress.hostAddress ?: error("Tinklo adresas nepasiekiamas"),
            port = socket.localPort,
            code = code,
            expiresAtMillis = Math.addExact(nowMillis(), durationMillis),
            rootName = root.name.ifBlank { "Pasirinktas katalogas" },
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
        session = null
        onStopped(t(reason).take(200))
    }

    private fun acceptLoop(activeSession: LanServerSession) {
        try {
            while (running.get()) {
                if (nowMillis() >= activeSession.expiresAtMillis) {
                    stop("LAN sesijos laikas baigėsi")
                    break
                }
                if (requests.get() >= MAX_REQUESTS_PER_SESSION) {
                    stop("LAN sesijos užklausų riba pasiekta")
                    break
                }
                try {
                    val client = serverSocket?.accept() ?: break
                    client.soTimeout = SOCKET_TIMEOUT_MILLIS
                    requests.incrementAndGet()
                    runCatching { executor.execute { client.use(::handle) } }
                        .onFailure { client.close() }
                } catch (_: SocketTimeoutException) {
                    // Periodically re-check bounded session lifetime.
                }
            }
        } catch (_: Throwable) {
            if (running.get()) stop("LAN serverio ryšys nutrūko")
        }
    }

    private fun handle(socket: Socket) {
        val input = BufferedInputStream(socket.getInputStream(), 64 * 1_024)
        val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1_024)
        try {
            handleRequest(input, output)
        } catch (error: Throwable) {
            val clientError = error is IllegalArgumentException || error is SecurityException
            runCatching {
                writeText(
                    output,
                    if (clientError) 400 else 500,
                    if (clientError) t(error.message ?: "Užklausa atmesta").take(200) else t("Serverio klaida"),
                    "text/plain; charset=utf-8",
                )
            }
        }
    }

    private fun handleRequest(input: BufferedInputStream, output: BufferedOutputStream) {
        val request = readRequest(input)
        if (request == null) {
            writeText(output, 400, t("Bloga užklausa"), "text/plain; charset=utf-8")
            return
        }
        val active = session
        if (!running.get() || active == null || nowMillis() >= active.expiresAtMillis) {
            writeText(output, 410, t("Sesija baigėsi"), "text/plain; charset=utf-8")
            return
        }

        if (request.path == "/login" && request.method == "POST") {
            handleLogin(request, input, output, active)
            return
        }
        if (!isAuthenticated(request)) {
            writeText(output, 401, loginPage(active), "text/html; charset=utf-8")
            return
        }
        when {
            request.method == "GET" && request.path == "/" -> showDirectory(output, "")
            request.method == "GET" && request.path == "/list" -> showDirectory(output, request.query["path"].orEmpty())
            request.method == "GET" && request.path == "/download" -> download(output, request.query["path"].orEmpty())
            request.method == "POST" && request.path == "/upload" -> upload(request, input, output)
            else -> writeText(output, 404, t("Nerasta"), "text/plain; charset=utf-8")
        }
    }

    private fun handleLogin(request: Request, input: BufferedInputStream, output: BufferedOutputStream, active: LanServerSession) {
        if (codeConsumed.get() || authFailures.get() >= MAX_AUTH_FAILURES) {
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
        codeConsumed.set(true)
        val page = "<html lang='${html(language)}'><head><meta http-equiv='refresh' content='0;url=/'></head><body>${html(t("Prisijungta"))}.</body></html>"
        writeText(
            output,
            200,
            page,
            "text/html; charset=utf-8",
            extraHeaders = listOf("Set-Cookie: af_session=$cookieToken; HttpOnly; SameSite=Strict; Path=/"),
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
        val page = """
            <!doctype html><html lang="${html(language)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>AF File Manager</title><style>body{font-family:system-ui;max-width:900px;margin:auto;padding:20px;background:#111;color:#eee}a{color:#80cbc4}li{padding:8px}input,button{padding:10px;margin:4px}</style></head>
            <body><h1>${html(session?.rootName.orEmpty())}</h1><p>${html(t("Vieta"))}: /${html(relative)}</p><ul>$rows</ul>
            <hr><h2>${html(t("Įkelti failą"))}</h2><input id="file" type="file"><button onclick="upload()">${html(t("Įkelti"))}</button><pre id="status"></pre>
            <script>async function upload(){let f=document.getElementById('file').files[0];if(!f)return;let u='/upload?dir=${url(relative)}&name='+encodeURIComponent(f.name);let r=await fetch(u,{method:'POST',body:f,headers:{'Content-Type':'application/octet-stream'}});document.getElementById('status').textContent=await r.text();if(r.ok)location.reload();}</script>
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

    private fun upload(request: Request, input: BufferedInputStream, output: BufferedOutputStream) {
        val length = request.contentLength
        require(length in 0..MAX_UPLOAD_BYTES) { "Failas viršija 1 GB ribą" }
        val directory = resolveRelative(request.query["dir"].orEmpty(), requireDirectory = true)
        require(directory.canWrite()) { "Pasirinktas katalogas neleidžia įkelti" }
        val name = FileSystemRules.validateFileName(request.query["name"].orEmpty()).getOrThrow()
        val requested = File(directory, name)
        val target = FileSystemRules.keepBothTarget(requested)
        require(FileSystemRules.isContained(root, target)) { "Tikslas išeina už pasirinkto katalogo" }
        val partial = File(directory, ".af-upload-${UUID.randomUUID()}.partial")
        var remaining = length
        try {
            FileOutputStream(partial).use { fileOutput ->
                val buffer = ByteArray(256 * 1_024)
                while (remaining > 0) {
                    val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                    if (read < 0) throw IllegalStateException("Įkėlimas nutrūko")
                    fileOutput.write(buffer, 0, read)
                    remaining -= read
                }
                fileOutput.fd.sync()
                buffer.fill(0)
            }
            require(partial.length() == length) { "Įkelto failo dydis nesutampa" }
            require(partial.renameTo(target)) { "Įkėlimo užbaigti nepavyko" }
            writeText(output, 201, t("Įkelta kaip ${target.name}"), "text/plain; charset=utf-8")
        } finally {
            if (partial.exists()) partial.delete()
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

    private fun isAuthenticated(request: Request): Boolean {
        val cookies = request.headers["cookie"].orEmpty().split(';').map(String::trim)
        return cookies.any { it == "af_session=$cookieToken" }
    }

    private fun loginPage(session: LanServerSession, error: String? = null): String = """
        <!doctype html><html lang="${html(language)}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>AF File Manager</title></head>
        <body style="font-family:system-ui;max-width:480px;margin:40px auto;padding:20px"><h1>AF File Manager</h1>
        <p>${html(t("Įveskite telefone rodomą 8 skaitmenų vienkartinį kodą."))}</p>${error?.let { "<p style='color:#b00020'>${html(it)}</p>" }.orEmpty()}
        <form method="post" action="/login"><input name="code" inputmode="numeric" maxlength="8" autocomplete="one-time-code" required><button type="submit">${html(t("Prisijungti"))}</button></form>
        <p>${html(t("Sesija baigsis automatiškai."))} ${html(t("Katalogas"))}: ${html(session.rootName)}</p></body></html>
    """.trimIndent()

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
