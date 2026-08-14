package com.affilemanager.app.network

import android.util.Xml
import com.affilemanager.app.BuildConfig
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class WebDavRemoteClient private constructor(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl,
    private val authorization: String,
) : RemoteClient {
    companion object {
        private const val MAX_XML_BYTES = 16L * 1_024 * 1_024
        private const val MAX_LIST_ENTRIES = 50_000
        private const val BUFFER_SIZE = 256 * 1_024
        private val XML = "application/xml; charset=utf-8".toMediaType()

        suspend fun connect(profile: NetworkProfile, password: CharArray): WebDavRemoteClient = withContext(Dispatchers.IO) {
            require(profile.protocol == NetworkProtocol.WEBDAV)
            val client = OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(15))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
            try {
                val baseUrl = HttpUrl.Builder()
                    .scheme("https")
                    .host(profile.host)
                    .port(profile.port)
                    .build()
                val auth = Credentials.basic(profile.username, password.concatToString(), Charsets.UTF_8)
                val remote = WebDavRemoteClient(client, baseUrl, auth)
                remote.list(profile.basePath)
                remote
            } finally {
                password.fill('\u0000')
            }
        }
    }

    override suspend fun list(path: String): List<RemoteEntry> = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(path)
        val body = """
            <?xml version="1.0" encoding="utf-8" ?>
            <d:propfind xmlns:d="DAV:">
              <d:prop><d:displayname/><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop>
            </d:propfind>
        """.trimIndent().toRequestBody(XML)
        execute(
            Request.Builder()
                .url(url(normalized))
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build(),
            expected = setOf(207),
        ).use { response ->
            val contentLength = response.body?.contentLength() ?: -1
            require(contentLength < 0 || contentLength <= MAX_XML_BYTES) { "WebDAV atsakymas per didelis" }
            val stream = LimitedInputStream(requireNotNull(response.body).byteStream(), MAX_XML_BYTES)
            parseMultiStatus(stream, normalized)
        }
    }

    override suspend fun download(
        remotePath: String,
        localDestination: File,
        operation: OperationContext?,
        maxBytes: Long?,
    ) = withContext(Dispatchers.IO) {
        val normalized = RemotePath.normalize(remotePath)
        val limit = RemoteDownloadLimit(maxBytes)
        val partial = File(localDestination.parentFile, ".${localDestination.name}.partial")
        try {
            execute(Request.Builder().url(url(normalized)).get().build(), expected = setOf(200)).use { response ->
                val expected = response.body?.contentLength()?.takeIf { it >= 0 }
                limit.checkExpected(expected)
                requireNotNull(response.body).byteStream().use { input ->
                    partial.outputStream().buffered().use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            operation?.checkpoint()
                            val read = input.read(buffer)
                            if (read < 0) break
                            limit.record(read)
                            output.write(buffer, 0, read)
                            operation?.progress(byteDelta = read.toLong(), currentName = localDestination.name)
                        }
                    }
                }
                if (expected != null) require(partial.length() == expected) { "Atsisiųsto failo dydis nesutampa" }
            }
            if (localDestination.exists()) require(localDestination.delete()) { "Esamo failo pakeisti nepavyko" }
            require(partial.renameTo(localDestination)) { "Atsisiuntimo užbaigti nepavyko" }
            operation?.progress(itemDelta = 1, currentName = localDestination.name)
        } finally {
            if (partial.exists()) partial.delete()
        }
        Unit
    }

    override suspend fun upload(localSource: File, remotePath: String, operation: OperationContext?) = withContext(Dispatchers.IO) {
        require(localSource.isFile) { "Vietinis failas nepasiekiamas" }
        val normalized = RemotePath.normalize(remotePath)
        val partial = RemotePath.temporarySibling(normalized, "af-partial")
        try {
            val body = object : RequestBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength(): Long = localSource.length()
                override fun writeTo(sink: BufferedSink) {
                    localSource.inputStream().buffered().use { input ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            sink.write(buffer, 0, read)
                            operation?.progress(byteDelta = read.toLong(), currentName = localSource.name)
                        }
                    }
                }
            }
            operation?.checkpoint()
            execute(Request.Builder().url(url(partial)).put(body).build(), expected = setOf(200, 201, 204)).close()
            moveRemote(partial, normalized, overwrite = true)
            operation?.progress(itemDelta = 1, currentName = localSource.name)
        } finally {
            runCatching { deleteRequest(partial) }
        }
        Unit
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        execute(
            Request.Builder().url(url(RemotePath.normalize(path))).method("MKCOL", ByteArray(0).toRequestBody(null)).build(),
            expected = setOf(201, 204),
        ).close()
    }

    override suspend fun rename(fromPath: String, toPath: String) = withContext(Dispatchers.IO) {
        moveRemote(RemotePath.normalize(fromPath), RemotePath.normalize(toPath), overwrite = false)
    }

    override suspend fun delete(path: String, recursive: Boolean) = withContext(Dispatchers.IO) {
        deleteRequest(RemotePath.normalize(path))
    }

    override suspend fun close() = withContext(Dispatchers.IO) {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun moveRemote(fromPath: String, toPath: String, overwrite: Boolean) {
        execute(
            Request.Builder()
                .url(url(fromPath))
                .header("Destination", url(toPath).toString())
                .header("Overwrite", if (overwrite) "T" else "F")
                .method("MOVE", ByteArray(0).toRequestBody(null))
                .build(),
            expected = setOf(201, 204),
        ).close()
    }

    private fun deleteRequest(path: String) {
        execute(
            Request.Builder().url(url(path)).delete().build(),
            expected = setOf(200, 202, 204, 404),
        ).close()
    }

    private fun execute(request: Request, expected: Set<Int>): Response {
        val authenticated = request.newBuilder()
            .header("Authorization", authorization)
            .header("User-Agent", "AFFileManager/${BuildConfig.VERSION_NAME}")
            .build()
        val response = client.newCall(authenticated).execute()
        if (response.code !in expected) {
            response.close()
            throw IllegalStateException("WebDAV serveris grąžino HTTP ${response.code}")
        }
        return response
    }

    private fun url(path: String): HttpUrl {
        val normalized = RemotePath.normalize(path)
        val builder = baseUrl.newBuilder()
        normalized.trim('/').split('/').filter(String::isNotEmpty).forEach(builder::addPathSegment)
        if (normalized.endsWith('/')) builder.addPathSegment("")
        return builder.build()
    }

    private fun parseMultiStatus(input: InputStream, requestedPath: String): List<RemoteEntry> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, Charsets.UTF_8.name())
        }
        val entries = mutableListOf<RemoteEntry>()
        var href: String? = null
        var displayName: String? = null
        var length = 0L
        var modified: Long? = null
        var directory = false
        var inResponse = false
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "response" -> {
                        inResponse = true
                        href = null
                        displayName = null
                        length = 0
                        modified = null
                        directory = false
                    }
                    "href" -> if (inResponse) href = parser.nextText()
                    "displayname" -> if (inResponse) displayName = parser.nextText()
                    "getcontentlength" -> if (inResponse) length = parser.nextText().toLongOrNull()?.coerceAtLeast(0) ?: 0
                    "getlastmodified" -> if (inResponse) modified = runCatching {
                        ZonedDateTime.parse(parser.nextText(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
                    }.getOrNull()
                    "collection" -> if (inResponse) directory = true
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("response", ignoreCase = true) && inResponse) {
                    inResponse = false
                    val rawHref = href.orEmpty()
                    val decoded = runCatching { java.net.URI(rawHref).path }.getOrDefault(rawHref)
                    val normalizedHref = RemotePath.normalize(decoded)
                    val requested = RemotePath.normalize(requestedPath)
                    if (normalizedHref.trimEnd('/') != requested.trimEnd('/')) {
                        val fallbackName = normalizedHref.trimEnd('/').substringAfterLast('/')
                        val name = displayName?.takeIf(String::isNotBlank) ?: fallbackName
                        if (name.isNotBlank()) {
                            entries += RemoteEntry(name, normalizedHref, directory, length, modified)
                            require(entries.size <= MAX_LIST_ENTRIES) { "WebDAV aplanke per daug elementų" }
                        }
                    }
                }
            }
            parser.next()
        }
        return entries.sortedWith(compareByDescending<RemoteEntry> { it.directory }.thenBy { it.name.lowercase() })
    }
}

private class LimitedInputStream(
    input: InputStream,
    private val maxBytes: Long,
) : FilterInputStream(input) {
    private var count = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val read = super.read(buffer, offset, length)
        if (read > 0) record(read.toLong())
        return read
    }

    private fun record(bytes: Long) {
        count = Math.addExact(count, bytes)
        require(count <= maxBytes) { "WebDAV XML atsakymas viršijo ribą" }
    }
}
