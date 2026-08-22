package com.affilemanager.app.terminal

import com.affilemanager.app.network.NetworkProfile
import com.affilemanager.app.network.NetworkProtocol
import com.affilemanager.app.network.VerifiedSshSessionFactory
import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

class SshTerminalBackend private constructor(
    private val session: Session,
    private val channel: ChannelShell,
    private val input: InputStream,
    private val output: OutputStream,
    val trustedFingerprint: String,
) : TerminalBackend {
    private val closed = AtomicBoolean(false)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000

        suspend fun open(
            profile: NetworkProfile,
            password: CharArray,
            privateKeyPem: CharArray,
            workingDirectory: String,
            pathStyle: RemoteShellPathStyle = RemoteShellPathStyle.POSIX,
            rows: Int = TerminalLimits.INITIAL_ROWS,
            columns: Int = TerminalLimits.INITIAL_COLUMNS,
        ): SshTerminalBackend {
            var created: SshTerminalBackend? = null
            try {
                return withContext(Dispatchers.IO) {
                    require(profile.protocol == NetworkProtocol.SFTP) { "A server terminal is available for SFTP/SSH connections only" }
                    TerminalLimits.requireDimensions(rows, columns)
                    val changeDirectory = ShellCommandRules.changeDirectory(workingDirectory, pathStyle)
                    val verified = VerifiedSshSessionFactory.connect(profile, password, privateKeyPem)
                    val session = verified.session
                    var channel: ChannelShell? = null
                    try {
                        channel = session.openChannel("shell") as ChannelShell
                        channel.setPty(true)
                        channel.setPtyType("xterm-256color")
                        channel.setPtySize(columns, rows, 0, 0)
                        val input = channel.inputStream
                        val output = channel.outputStream
                        channel.connect(CONNECT_TIMEOUT_MS)
                        if (changeDirectory.isNotEmpty()) {
                            output.write(changeDirectory)
                            output.flush()
                        }
                        SshTerminalBackend(session, channel, input, output, verified.fingerprint)
                            .also { created = it }
                    } catch (error: Throwable) {
                        runCatching { channel?.disconnect() }
                        session.disconnect()
                        throw error
                    } finally {
                        changeDirectory.fill(0)
                    }
                }
            } catch (error: Throwable) {
                created?.close()
                throw error
            } finally {
                password.fill('\u0000')
                privateKeyPem.fill('\u0000')
            }
        }
    }

    override suspend fun read(destination: ByteArray): Int = withContext(Dispatchers.IO) {
        require(destination.isNotEmpty() && destination.size <= TerminalLimits.IO_CHUNK_BYTES)
        if (closed.get()) -1 else input.read(destination)
    }

    override suspend fun write(source: ByteArray, offset: Int, length: Int) = withContext(Dispatchers.IO) {
        require(offset >= 0 && length >= 0 && offset <= source.size && length <= source.size - offset) {
            "Invalid terminal input range"
        }
        require(length <= TerminalLimits.TRANSPORT_WRITE_CHUNK_BYTES) { "Terminal input chunk is too large" }
        check(!closed.get()) { "Terminal is closed" }
        output.write(source, offset, length)
        output.flush()
    }

    override suspend fun resize(rows: Int, columns: Int) = withContext(Dispatchers.IO) {
        TerminalLimits.requireDimensions(rows, columns)
        if (!closed.get() && channel.isConnected) channel.setPtySize(columns, rows, 0, 0)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { output.close() }
        runCatching { input.close() }
        channel.disconnect()
        session.disconnect()
    }
}
