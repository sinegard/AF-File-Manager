package com.affilemanager.app.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class LanFtpServerTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun authenticationListingAndContainmentAreEnforced() {
        val root = temporary.newFolder("ftp-root").apply { resolve("visible.txt").writeText("hello") }
        LanFtpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678").use { server ->
            val session = server.start()
            Socket(InetAddress.getLoopbackAddress(), session.port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                assertTrue(reader.readLine().startsWith("220"))

                writer.print("PWD\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("530"))
                writer.print("USER af\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("331"))
                writer.print("PASS 12345678\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("230"))

                writer.print("CWD ..\r\n"); writer.flush()
                assertTrue(reader.readLine().startsWith("550"))

                writer.print("PASV\r\n"); writer.flush()
                val passive = reader.readLine()
                assertTrue(passive.startsWith("227"))
                val values = passive.substringAfter('(').substringBefore(')').split(',').map(String::toInt)
                val dataPort = values[4] * 256 + values[5]
                Socket(InetAddress.getLoopbackAddress(), dataPort).use { data ->
                    writer.print("NLST\r\n"); writer.flush()
                    assertTrue(reader.readLine().startsWith("150"))
                    val listing = data.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                    assertTrue(listing.contains("visible.txt"))
                }
                assertTrue(reader.readLine().startsWith("226"))
            }
        }
    }

    @Test
    fun limitsAreExplicit() {
        assertEquals(10_000, LanFtpServer.MAX_COMMANDS_PER_SESSION)
        assertEquals(20, LanFtpServer.MAX_AUTH_FAILURES)
        assertEquals(30_000L, LanFtpServer.AUTH_LOCK_MILLIS)
        assertEquals(LanHttpServer.MAX_UPLOAD_BYTES, LanFtpServer.MAX_UPLOAD_BYTES)
    }

    @Test
    fun activeEprtAndPortTransfersAreRestrictedToTheControlPeer() {
        val loopback = InetAddress.getLoopbackAddress()
        val root = temporary.newFolder("ftp-active-root").apply { resolve("active.txt").writeText("active") }
        LanFtpServer(root, loopback, requestedCode = "12345678").use { server ->
            val session = server.start()
            Socket(loopback, session.port).use { control ->
                control.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(control.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(control.getOutputStream(), true, StandardCharsets.UTF_8)
                assertTrue(reader.readLine().startsWith("220"))
                login(reader, writer)

                ServerSocket(0, 1, loopback).use { dataServer ->
                    command(writer, "EPRT |1|127.0.0.1|${dataServer.localPort}|")
                    assertTrue(reader.readLine().startsWith("200"))
                    command(writer, "NLST")
                    assertTrue(reader.readLine().startsWith("150"))
                    val listing = dataServer.accept().use { it.getInputStream().readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(listing.contains("active.txt"))
                    assertTrue(reader.readLine().startsWith("226"))
                }

                ServerSocket(0, 1, loopback).use { dataServer ->
                    val port = dataServer.localPort
                    command(writer, "PORT 127,0,0,1,${port / 256},${port % 256}")
                    assertTrue(reader.readLine().startsWith("200"))
                    command(writer, "NLST")
                    assertTrue(reader.readLine().startsWith("150"))
                    val listing = dataServer.accept().use { it.getInputStream().readBytes().toString(StandardCharsets.UTF_8) }
                    assertTrue(listing.contains("active.txt"))
                    assertTrue(reader.readLine().startsWith("226"))
                }

                command(writer, "EPRT |1|127.0.0.2|49152|")
                assertTrue(reader.readLine().startsWith("550"))
            }
        }
    }

    @Test
    fun authenticationLockExpiresInsteadOfDisablingTheWholeSession() {
        var now = 1_000L
        val root = temporary.newFolder("ftp-auth-root")
        LanFtpServer(root, InetAddress.getLoopbackAddress(), requestedCode = "12345678", nowMillis = { now }).use { server ->
            val session = server.start()
            Socket(InetAddress.getLoopbackAddress(), session.port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                assertTrue(reader.readLine().startsWith("220"))
                repeat(LanFtpServer.MAX_AUTH_FAILURES) {
                    command(writer, "USER af")
                    assertTrue(reader.readLine().startsWith("331"))
                    command(writer, "PASS 00000000")
                    assertTrue(reader.readLine().startsWith("530"))
                }
                command(writer, "USER af")
                assertTrue(reader.readLine().startsWith("331"))
                command(writer, "PASS 12345678")
                assertTrue(reader.readLine().startsWith("530"))

                now += LanFtpServer.AUTH_LOCK_MILLIS + 1
                command(writer, "USER af")
                assertTrue(reader.readLine().startsWith("331"))
                command(writer, "PASS 12345678")
                assertTrue(reader.readLine().startsWith("230"))
            }
        }
    }

    @Test
    fun customCredentialsAndReadOnlyModeRejectMutatingCommands() {
        val root = temporary.newFolder("ftp-read-only").apply { resolve("visible.txt").writeText("visible") }
        val password = "  temporary-pass  "
        LanFtpServer(
            rootDirectory = root,
            bindAddress = InetAddress.getLoopbackAddress(),
            requestedUsername = "owner",
            requestedCode = password,
            readOnly = true,
        ).use { server ->
            val session = server.start()
            assertEquals("owner", session.username)
            assertTrue(session.readOnly)
            Socket(InetAddress.getLoopbackAddress(), session.port).use { socket ->
                socket.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                val writer = PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)
                assertTrue(reader.readLine().startsWith("220"))
                command(writer, "USER owner")
                assertTrue(reader.readLine().startsWith("331"))
                command(writer, "PASS $password")
                assertTrue(reader.readLine().startsWith("230"))

                command(writer, "MKD blocked")
                assertTrue(reader.readLine().startsWith("550"))
                command(writer, "STOR blocked.txt")
                assertTrue(reader.readLine().startsWith("550"))
                assertTrue(!root.resolve("blocked").exists())
                assertTrue(!root.resolve("blocked.txt").exists())
            }
        }
    }

    private fun login(reader: BufferedReader, writer: PrintWriter) {
        command(writer, "USER af")
        assertTrue(reader.readLine().startsWith("331"))
        command(writer, "PASS 12345678")
        assertTrue(reader.readLine().startsWith("230"))
    }

    private fun command(writer: PrintWriter, value: String) {
        writer.print("$value\r\n")
        writer.flush()
    }
}
