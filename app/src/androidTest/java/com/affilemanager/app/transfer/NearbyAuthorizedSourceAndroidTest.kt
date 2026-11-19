package com.affilemanager.app.transfer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

class NearbyAuthorizedSourceAndroidTest {
    @Test fun selectedRemovableTreeAndItsRawVolumePathSendThroughTheActualService() = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        check(android.os.Build.MODEL.contains("sdk", true))
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull {
            it.isReadPermission && it.isWritePermission && it.uri.authority == "com.android.externalstorage.documents" &&
                DocumentsContract.getTreeDocumentId(it.uri).let { id -> !id.startsWith("primary:") }
        }
        org.junit.Assume.assumeNotNull("An owned OS-selected removable tree is required", grant)
        val folder = app.graph.safFiles.createDirectory(grant!!.uri.toString(), "AFNearby-${System.nanoTime()}").getOrThrow()
        val nested = app.graph.safFiles.createDirectory(folder.uri, "nested").getOrThrow()
        app.graph.safFiles.createDirectory(folder.uri, "empty").getOrThrow()
        val file = app.graph.safFiles.createFile(nested.uri, "kept.txt").getOrThrow()
        val content = "AF document source, not a cache duplicate\nžąčé\n"
        app.contentResolver.openOutputStream(Uri.parse(file.uri), "wt")!!.use { it.write(content.toByteArray()) }
        val destination = File(app.cacheDir, "nearby-document-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            val tree = DocumentsContract.getTreeDocumentId(grant.uri)
            assertEquals(DocumentsContract.buildDocumentUriUsingTree(grant.uri, tree).toString(),
                app.graph.nearbySourceReader.parent(folder.uri))
            val raw = "/storage/${tree.substringBefore(':')}/" + listOf(tree.substringAfter(':'), folder.name).filter(String::isNotEmpty).joinToString("/")
            for (path in listOf(folder.uri, raw)) {
                val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(path, file.uri)).getOrThrow()
                assertEquals(listOf(""), prepared.paths)
                assertEquals(listOf(file.uri), prepared.sourceUris)
                assertEquals(listOf("${folder.name}/nested/kept.txt"), prepared.relativePaths)
                assertNull(prepared.cleanupRootPath)
                val target = File(destination, if (path == raw) "raw" else "uri").apply { check(mkdir()) }
                sendNearbyFixture(app, prepared, target)
                assertEquals(content, File(target, "${folder.name}/nested/kept.txt").readText())
                assertTrue(File(target, "${folder.name}/empty").isDirectory)
            }
            assertEquals(content, app.contentResolver.openInputStream(Uri.parse(file.uri))!!.bufferedReader().use { it.readText() })
            assertTrue(app.graph.nearbySourceReader.list("", false).getOrThrow().any { it.absolutePath == grant.uri.toString() })
        } finally {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            app.graph.safFiles.delete(folder.uri).getOrThrow()
            check(destination.deleteRecursively())
        }
    }
}

internal suspend fun sendNearbyFixture(app: AFFileManagerApplication, prepared: PreparedNearbyTransfer,
                                      destination: File, expected: NearbyTransferStatus = NearbyTransferStatus.COMPLETED) {
    val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
        .first { it is Inet4Address && it.isSiteLocalAddress }
    LanHttpServer(destination, address, requestedCode = "12345678").use { server ->
        val session = server.start()
        NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
        NearbyTransferController.start(app, NearbyPairing.create(session.address, session.port, session.code, "Owned test receiver"), prepared)
        val result = withTimeout(60_000) {
            while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
            NearbyTransferController.state.value
        }
        assertEquals(result.message, expected, result.status)
    }
}
