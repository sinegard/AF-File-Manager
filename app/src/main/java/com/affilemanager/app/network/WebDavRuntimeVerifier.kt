package com.affilemanager.app.network

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking

/** Non-exported, signed-instrumentation seam for the exact optimized client and diagnostics. */
@Keep
object WebDavRuntimeVerifier {
    @JvmStatic
    fun verify(
        host: String,
        port: Int,
        useTls: Boolean,
        username: String,
        password: CharArray,
        basePath: String,
        expectedFile: String,
        expectedErrorCode: String,
    ): Boolean = runBlocking {
        val profile = NetworkProfile(
            id = "optimized-webdav-test", name = "Optimized WebDAV test", protocol = NetworkProtocol.WEBDAV,
            host = host, port = port, username = username, basePath = basePath, webDavUseTls = useTls,
        )
        try {
            val client = WebDavRemoteClient.connect(profile, password)
            try {
                check(expectedErrorCode.isEmpty()) { "Expected WebDAV rejection was not observed" }
                client.list(basePath).any { it.name == expectedFile }
            } finally {
                client.close()
            }
        } catch (failure: Exception) {
            if (expectedErrorCode.isEmpty()) throw failure
            RemoteErrorPresenter.present(NetworkProtocol.WEBDAV, RemoteOperation.CONNECT, failure).diagnosticCode == expectedErrorCode
        } finally {
            password.fill('\u0000')
        }
    }
}
