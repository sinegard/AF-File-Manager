package com.affilemanager.app.network

import androidx.annotation.Keep
import kotlinx.coroutines.runBlocking

/** Stable, non-exported entry point for exercising SFTP in an optimized APK. */
@Keep
object SftpRuntimeVerifier {
    @JvmStatic
    fun verifyPasswordConnection(
        host: String,
        port: Int,
        username: String,
        password: CharArray,
        expectedFile: String,
    ): Boolean = runBlocking {
        val profile = NetworkProfile(
            id = "optimized-release-test",
            name = "Optimized release SFTP test",
            protocol = NetworkProtocol.SFTP,
            host = host,
            port = port,
            username = username,
            basePath = "/",
            allowFirstUseTrust = true,
        )
        val client = SftpRemoteClient.connect(profile, password, CharArray(0))
        try {
            client.list("/").any { it.name == expectedFile }
        } finally {
            client.close()
        }
    }
}
