package com.affilemanager.app.transfer

import android.app.Instrumentation
import android.os.Environment
import android.provider.MediaStore
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.UUID

/** Benchmark-only entry point: the coroutine facade is not retained in the shipping APK. */
object IssueRegressionRuntimeVerifier {
    @JvmStatic fun verify(test: Instrumentation): Boolean = runBlocking {
        check(android.os.Build.MODEL.contains("sdk", true)) { "Disposable emulator required" }
        val app = test.targetContext.applicationContext as AFFileManagerApplication
        val graph = app.graph
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AFOptimizedIndex-${UUID.randomUUID()}")
        check(root.mkdir())
        val source = File(root, "original.apk")
        File(app.applicationInfo.sourceDir).copyTo(source)
        val destination = File(root, "copy").apply { check(mkdir()) }
        val moved = File(root, "moved").apply { check(mkdir()) }
        val context = OperationContext.background()
        fun indexedName(file: File): String? = app.contentResolver.query(MediaStore.Files.getContentUri("external"),
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), "${MediaStore.MediaColumns.DATA} = ?",
            arrayOf(file.path), null)?.use { if (it.moveToFirst()) it.getString(0) else null }
        try {
            graph.sharedStorageIndex.changed(listOf(source))
            graph.localFileOperator.copyOrMove(listOf(source.path), destination.path, false, ConflictPolicy.SKIP, context)
            check(source.isFile)
            val copied = File(destination, source.name)
            check(copied.isFile && indexedName(copied) == copied.name)
            val renamed = graph.localFiles.rename(copied.path, "renamed.apk").getOrThrow()
            val renamedFile = File(renamed.absolutePath)
            check(indexedName(copied) == null && indexedName(renamedFile) == "renamed.apk")
            graph.localFileOperator.copyOrMove(listOf(renamedFile.path), moved.path, true, ConflictPolicy.SKIP, context)
            check(!renamedFile.exists() && indexedName(renamedFile) == null)
            check(indexedName(File(moved, "renamed.apk")) == "renamed.apk" && source.isFile)

            val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
                .first { it is Inet4Address && it.isSiteLocalAddress }
            LanHttpServer(root, address, requestedCode = "12345678", groupMode = true).use { server ->
                val port = server.start().port
                fun request(value: String): String = Socket(address, port).use { socket ->
                    socket.soTimeout = 5_000
                    socket.getOutputStream().write(value.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                    val response = socket.getInputStream().readBytes()
                    check(response.size < 64 * 1_024)
                    response.toString(Charsets.UTF_8)
                }
                fun login(): String {
                    val body = "code=12345678"
                    return request("POST /login HTTP/1.1\r\nHost: localhost\r\nContent-Length: ${body.length}\r\n\r\n$body")
                        .lineSequence().first { it.startsWith("Set-Cookie:") }
                        .substringAfter(':').substringBefore(';').trim()
                }
                fun join(cookie: String, peer: NearbyPairing) {
                    val body = peer.encoded()
                    check(request("POST /nearby/group/join HTTP/1.1\r\nHost: localhost\r\nCookie: $cookie\r\n" +
                        "Content-Length: ${body.toByteArray().size}\r\n\r\n$body").startsWith("HTTP/1.1 200"))
                }
                val first = login()
                val second = login()
                val firstPeer = NearbyPairing.create(address.hostAddress, 23_001, "23456789", "First")
                val secondPeer = NearbyPairing.create(address.hostAddress, 23_002, "34567890", "Second")
                join(first, firstPeer); join(second, secondPeer)
                check(request("POST /nearby/disconnect HTTP/1.1\r\nHost: localhost\r\nCookie: $first\r\nContent-Length: 0\r\n\r\n")
                    .startsWith("HTTP/1.1 200"))
                val remaining = request("GET /nearby/group/members HTTP/1.1\r\nHost: localhost\r\nCookie: $second\r\n\r\n")
                check(remaining.startsWith("HTTP/1.1 200") && NearbyGroupCodec.decode(remaining.substringAfter("\r\n\r\n").toByteArray()).size == 2)
                check(server.removeGroupMember(secondPeer))
                val rejected = request("POST /upload?name=blocked.txt HTTP/1.1\r\nHost: localhost\r\nCookie: $second\r\nContent-Length: 1\r\n\r\nx")
                check(rejected.startsWith("HTTP/1.1 403") && rejected.contains("X-AF-Group-State: removed"))
                check(!File(root, "blocked.txt").exists())
            }
            true
        } finally {
            check(root.deleteRecursively())
            graph.sharedStorageIndex.changed(listOf(root))
        }
    }
}
