package com.affilemanager.app.network

import com.affilemanager.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class NextcloudLoginStart(
    val loginUrl: String,
    val pollUrl: String,
    val token: String,
    val requestedServer: String,
)

data class NextcloudLoginCredentials(
    val server: String,
    val loginName: String,
    val userId: String,
    val appPassword: CharArray,
) : AutoCloseable {
    fun profile(): NetworkProfile {
        val endpoint = NextcloudServerRules.parse(server)
        val encodedRoot = buildString {
            append(endpoint.basePath.trimEnd('/'))
            append("/remote.php/dav/files/")
            append(userId)
        }
        return NetworkProfile(
            id = UUID.randomUUID().toString(),
            name = "Nextcloud - ${endpoint.host}",
            protocol = NetworkProtocol.WEBDAV,
            host = endpoint.host,
            port = endpoint.port,
            username = loginName,
            basePath = RemotePath.normalize(encodedRoot),
            webDavUseTls = endpoint.useTls,
            provider = NetworkProvider.NEXTCLOUD,
        )
    }

    override fun close() {
        appPassword.fill('\u0000')
    }
}

data class NextcloudServerEndpoint(
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int,
    val basePath: String,
    val useTls: Boolean,
)

object NextcloudServerRules {
    fun parse(value: String): NextcloudServerEndpoint {
        val prepared = value.trim().trimEnd('/').let { raw ->
            if ("://" in raw) raw else "https://$raw"
        }
        require(prepared.length <= MAX_SERVER_URL_LENGTH) { "Nextcloud serverio adresas per ilgas" }
        val uri = runCatching { URI(prepared) }.getOrElse {
            throw IllegalArgumentException("Netinkamas Nextcloud serverio adresas")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "Naudokite HTTPS arba HTTP Nextcloud adresą" }
        require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
            "Įrašykite tik Nextcloud serverio adresą"
        }
        val host = uri.host?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("Netinkamas Nextcloud serverio adresas")
        require(NetworkProfileRules.hostError(host) == null) { "Netinkamas Nextcloud serverio adresas" }
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        require(NetworkProfileRules.portError(port) == null) { "Netinkamas Nextcloud prievadas" }
        val basePath = RemotePath.normalize(uri.path.orEmpty().ifBlank { "/" })
        val authority = if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) host else "$host:$port"
        val normalized = "$scheme://$authority${basePath.takeUnless { it == "/" }.orEmpty()}"
        return NextcloudServerEndpoint(normalized, scheme, host, port, basePath, scheme == "https")
    }

    fun endpoint(server: NextcloudServerEndpoint, suffix: String): HttpUrl {
        val path = listOf(server.basePath.trim('/'), suffix.trim('/'))
            .filter(String::isNotEmpty)
            .joinToString("/")
        return "${server.scheme}://${server.hostForUrl()}:${server.port}/$path".toHttpUrl()
    }

    fun requireSameOrigin(server: NextcloudServerEndpoint, candidate: String, field: String): HttpUrl {
        require(candidate.length <= MAX_SERVER_URL_LENGTH) { "$field adresas per ilgas" }
        val url = runCatching { candidate.toHttpUrl() }.getOrElse {
            throw IllegalArgumentException("Netinkamas $field adresas")
        }
        require(url.scheme == server.scheme && url.host.equals(server.host, ignoreCase = true) && url.port == server.port) {
            "$field adresas nukreipia į kitą serverį"
        }
        return url
    }

    fun validUserId(value: String): String = value.trim().also {
        require(it.isNotEmpty() && it.length <= 256) { "Nextcloud naudotojo ID netinkamas" }
        require(it != "." && it != ".." && it.none { char -> char == '/' || char == '\\' || char == '\u0000' || char.isISOControl() }) {
            "Nextcloud naudotojo ID netinkamas"
        }
    }

    private fun NextcloudServerEndpoint.hostForUrl(): String = if (':' in host) "[$host]" else host

    const val MAX_SERVER_URL_LENGTH = 8_192
}

class NextcloudLoginClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
) {
    companion object {
        const val LOGIN_TIMEOUT_MILLIS = 20L * 60L * 1_000L
        private const val POLL_INTERVAL_MILLIS = 1_000L
        private const val MAX_RESPONSE_BYTES = 64 * 1_024
        private const val MAX_TOKEN_LENGTH = 4_096
        private const val USER_AGENT = "AF File Manager/${BuildConfig.VERSION_NAME}"
    }

    suspend fun begin(serverInput: String): NextcloudLoginStart = withContext(Dispatchers.IO) {
        val server = NextcloudServerRules.parse(serverInput)
        val request = Request.Builder()
            .url(NextcloudServerRules.endpoint(server, "index.php/login/v2"))
            .header("User-Agent", USER_AGENT)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Nextcloud prisijungimo pradėti nepavyko (HTTP ${response.code})" }
            val json = JSONObject(readLimited(requireNotNull(response.body).byteStream()))
            val poll = json.getJSONObject("poll")
            val token = poll.getString("token")
            require(token.isNotBlank() && token.length <= MAX_TOKEN_LENGTH && token.none(Char::isISOControl)) {
                "Nextcloud grąžino netinkamą prisijungimo žymą"
            }
            val loginUrl = NextcloudServerRules.requireSameOrigin(server, json.getString("login"), "Prisijungimo").toString()
            val pollUrl = NextcloudServerRules.requireSameOrigin(server, poll.getString("endpoint"), "Tikrinimo").toString()
            NextcloudLoginStart(loginUrl, pollUrl, token, server.url)
        }
    }

    suspend fun await(start: NextcloudLoginStart): NextcloudLoginCredentials = withContext(Dispatchers.IO) {
        val requested = NextcloudServerRules.parse(start.requestedServer)
        val pollUrl = NextcloudServerRules.requireSameOrigin(requested, start.pollUrl, "Tikrinimo")
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LOGIN_TIMEOUT_MILLIS)
        while (System.nanoTime() < deadline) {
            coroutineContext.ensureActive()
            val request = Request.Builder()
                .url(pollUrl)
                .header("User-Agent", USER_AGENT)
                .post(FormBody.Builder().add("token", start.token).build())
                .build()
            val result = client.newCall(request).execute().use { response ->
                when (response.code) {
                    404 -> null
                    200 -> JSONObject(readLimited(requireNotNull(response.body).byteStream()))
                    else -> throw IllegalStateException("Nextcloud prisijungimo patvirtinti nepavyko (HTTP ${response.code})")
                }
            }
            if (result != null) {
                val returnedServer = NextcloudServerRules.parse(result.getString("server"))
                require(returnedServer.scheme == requested.scheme && returnedServer.host.equals(requested.host, true) && returnedServer.port == requested.port) {
                    "Nextcloud grąžino kito serverio adresą"
                }
                val loginName = result.getString("loginName").trim()
                require(NetworkProfileRules.usernameError(loginName) == null && loginName.isNotEmpty()) {
                    "Nextcloud grąžino netinkamą naudotojo vardą"
                }
                val password = result.getString("appPassword").toCharArray()
                require(password.isNotEmpty() && password.size <= 4_096 && password.none(Char::isISOControl)) {
                    password.fill('\u0000')
                    "Nextcloud grąžino netinkamą programos slaptažodį"
                }
                return@withContext try {
                    val userId = try {
                        fetchUserId(returnedServer, loginName, password)
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (_: Throwable) {
                        NextcloudServerRules.validUserId(loginName)
                    }
                    NextcloudLoginCredentials(returnedServer.url, loginName, userId, password)
                } catch (error: Throwable) {
                    password.fill('\u0000')
                    throw error
                }
            }
            delay(POLL_INTERVAL_MILLIS)
        }
        throw IllegalStateException("Nextcloud prisijungimo laikas baigėsi")
    }

    private fun fetchUserId(server: NextcloudServerEndpoint, loginName: String, password: CharArray): String {
        val request = Request.Builder()
            .url(NextcloudServerRules.endpoint(server, "ocs/v1.php/cloud/user?format=json"))
            .header("User-Agent", USER_AGENT)
            .header("OCS-APIRequest", "true")
            .header("Accept", "application/json")
            .header("Authorization", Credentials.basic(loginName, password.concatToString(), Charsets.UTF_8))
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            require(response.code == 200) { "Nextcloud naudotojo ID negautas" }
            val data = JSONObject(readLimited(requireNotNull(response.body).byteStream()))
                .getJSONObject("ocs")
                .getJSONObject("data")
            NextcloudServerRules.validUserId(data.getString("id"))
        }
    }

    private fun readLimited(input: InputStream): String = input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1_024)
        var total = 0
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_RESPONSE_BYTES) { "Nextcloud atsakymas per didelis" }
            output.write(buffer, 0, read)
        }
        output.toString(Charsets.UTF_8.name())
    }
}
