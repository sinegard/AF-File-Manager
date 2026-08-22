package com.affilemanager.app.network

import java.util.UUID

enum class NetworkProtocol {
    SFTP,
    SMB,
    WEBDAV,
    FTP,
    FTPS,
}

data class NetworkProfile(
    val id: String,
    val name: String,
    val protocol: NetworkProtocol,
    val host: String,
    val port: Int,
    val username: String,
    val basePath: String,
    val domain: String = "",
    val smbShare: String = "",
    val expectedHostKeySha256: String? = null,
    val allowFirstUseTrust: Boolean = false,
    val webDavUseTls: Boolean = true,
)

data class RemoteEntry(
    val name: String,
    val path: String,
    val directory: Boolean,
    val sizeBytes: Long,
    val modifiedAtMillis: Long?,
)

data class ConnectionSecret(
    val password: CharArray,
    val privateKeyPem: CharArray = CharArray(0),
) : AutoCloseable {
    override fun close() {
        password.fill('\u0000')
        privateKeyPem.fill('\u0000')
    }
}

object RemotePath {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/')
        val clean = ArrayDeque<String>()
        parts.forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (clean.isNotEmpty()) clean.removeLast()
                else -> {
                    require('\u0000' !in part) { "Netinkamas nuotolinis kelias" }
                    clean.add(part)
                }
            }
        }
        return "/" + clean.joinToString("/")
    }

    fun join(parent: String, child: String): String {
        require('/' !in child && '\\' !in child && child != "." && child != "..") { "Netinkamas failo vardas" }
        return normalize("${normalize(parent)}/$child")
    }

    fun temporarySibling(path: String, prefix: String): String {
        require(prefix.matches(Regex("[a-zA-Z0-9-]{1,32}"))) { "Netinkamas laikino failo prefiksas" }
        val normalized = normalize(path)
        val parent = normalize("$normalized/..")
        val token = UUID.randomUUID().toString().replace("-", "")
        return join(parent, ".$prefix-$token")
    }
}
