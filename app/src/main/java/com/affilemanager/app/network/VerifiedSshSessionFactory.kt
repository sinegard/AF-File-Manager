package com.affilemanager.app.network

import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64

internal data class VerifiedSshSession(
    val session: Session,
    val fingerprint: String,
)

/** Creates SSH sessions with the same host-key and credential policy used by SFTP browsing. */
internal object VerifiedSshSessionFactory {
    private const val CONNECT_TIMEOUT_MS = 15_000

    suspend fun connect(
        profile: NetworkProfile,
        password: CharArray,
        privateKeyPem: CharArray,
    ): VerifiedSshSession {
        var connected: VerifiedSshSession? = null
        try {
            return withContext(Dispatchers.IO) {
                require(profile.protocol == NetworkProtocol.SFTP) { "An SSH terminal requires an SFTP profile" }
                val repository = FingerprintHostKeyRepository(
                    expected = profile.expectedHostKeySha256,
                    allowFirstUse = profile.allowFirstUseTrust,
                )
                val jsch = JSch().apply { hostKeyRepository = repository }
                val session = jsch.getSession(profile.username, profile.host, profile.port)
                val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
                val privateKeyBytes = privateKeyPem.concatToString().toByteArray(Charsets.UTF_8)
                try {
                    if (privateKeyBytes.isNotEmpty()) {
                        jsch.addIdentity(
                            "af-file-manager-${profile.id}",
                            privateKeyBytes,
                            null,
                            passwordBytes.takeIf(ByteArray::isNotEmpty),
                        )
                    } else {
                        require(passwordBytes.isNotEmpty()) { "An SFTP password or private SSH key is required" }
                        session.setPassword(passwordBytes)
                    }
                    session.setConfig("StrictHostKeyChecking", "yes")
                    session.setConfig(
                        "PreferredAuthentications",
                        if (privateKeyBytes.isNotEmpty()) "publickey" else "password,keyboard-interactive",
                    )
                    session.setServerAliveInterval(15_000)
                    session.setServerAliveCountMax(3)
                    session.timeout = CONNECT_TIMEOUT_MS
                    session.connect(CONNECT_TIMEOUT_MS)
                    val fingerprint = repository.observed
                        ?: throw SecurityException("The SSH server key was not verified")
                    VerifiedSshSession(session, fingerprint).also { connected = it }
                } catch (error: Throwable) {
                    session.disconnect()
                    throw error
                } finally {
                    passwordBytes.fill(0)
                    privateKeyBytes.fill(0)
                }
            }
        } catch (error: Throwable) {
            connected?.session?.disconnect()
            throw error
        } finally {
            password.fill('\u0000')
            privateKeyPem.fill('\u0000')
        }
    }
}

internal class FingerprintHostKeyRepository(
    private val expected: String?,
    private val allowFirstUse: Boolean,
) : HostKeyRepository {
    @Volatile
    var observed: String? = null
        private set

    override fun check(host: String?, key: ByteArray): Int {
        val actual = "SHA256:" + Base64.getEncoder().withoutPadding()
            .encodeToString(MessageDigest.getInstance("SHA-256").digest(key))
        observed = actual
        return when {
            expected != null && constantTimeEquals(expected, actual) -> HostKeyRepository.OK
            expected == null && allowFirstUse -> HostKeyRepository.OK
            expected != null -> HostKeyRepository.CHANGED
            else -> HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey?, userinfo: com.jcraft.jsch.UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "AF File Manager SHA-256 pinning"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val leftBytes = left.toByteArray(Charsets.US_ASCII)
        val rightBytes = right.toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(leftBytes, rightBytes)
    }
}
