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
        assertEquals(LanHttpServer.MAX_UPLOAD_BYTES, LanFtpServer.MAX_UPLOAD_BYTES)
    }
}
