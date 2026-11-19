package com.affilemanager.app.transfer

import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class NearbyReceiveStorageSafetyAndroidTest {
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()

    @Test fun failureBeforeOpeningAStreamIsReportedAsAFailedRowNotAWaitingFile() {
        check(android.os.Build.MODEL.contains("sdk", true))
        val progress = java.util.concurrent.atomic.AtomicReference<LanUploadProgress>()
        val address = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is java.net.Inet4Address && it.isSiteLocalAddress }
        val denied = object : LanReceiveStorage {
            override val label = "Owned rejection fixture"
            override fun list(relativeDirectory: String) = emptyList<LanSharedEntry>()
            override fun read(relativePath: String): LanSharedRead = error("Unused")
            override fun createDirectory(relativePath: String) = Unit
            override fun directory(relativeDirectory: String, fileName: String, routeByType: Boolean) =
                LanReceiveDirectory.Documents { throw SecurityException("Owned denial") }
        }
        val client = okhttp3.OkHttpClient.Builder().followRedirects(false).build()
        LanHttpServer(app.cacheDir, address, requestedCode = "12345678", receiveStorage = denied, onUploadProgress = progress::set).use { server ->
            val endpoint = server.start()
            val cookie = client.newCall(okhttp3.Request.Builder().url(endpoint.url + "login")
                .post(okhttp3.FormBody.Builder().add("code", endpoint.code).build()).build()).execute().use {
                assertEquals(200, it.code); it.header("Set-Cookie")!!.substringBefore(';')
            }
            val body = object : okhttp3.RequestBody() {
                override fun contentType() = null
                override fun contentLength() = 3L
                override fun writeTo(sink: okio.BufferedSink) { sink.writeUtf8("new") }
            }
            client.newCall(okhttp3.Request.Builder().url(endpoint.url + "upload?name=denied.txt").header("Cookie", cookie).post(body).build())
                .execute().use { assertEquals(TransferFailure.PERMISSION.code, it.header(LanHttpServer.ERROR_CODE_HEADER)) }
            val row = progress.get().files.single()
            assertEquals(TransferFileStatus.FAILED, row.status)
            assertEquals(TransferFailure.PERMISSION, row.failure)
            assertNull(row.localPath)
        }
    }

    @Test fun abortRemovesOnlyTheOwnedPartialAndPreservesTheExistingFile() = runBlocking<Unit> {
        val folder = fixture()
        val original = app.graph.safFiles.createFile(folder, "original.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(original.uri), "wt")!!.use { it.write("original stays".toByteArray()) }
        try {
            val storage = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
            val upload = (storage.directory("", "new.txt", false) as LanReceiveDirectory.Documents).open("new.txt")
            upload.output.write("unfinished copy".toByteArray()); upload.sync(); upload.output.close()
            upload.close() // Interrupted before commit.
            assertEquals(listOf("original.txt"), app.graph.safFiles.list(folder).getOrThrow().map { it.name })
            assertEquals("original stays", read(original.uri))
        } finally { app.graph.safFiles.delete(folder).getOrThrow() }
    }

    @Test fun changedPartialIsRetainedRatherThanDeletedByCleanupOrRecovery() = runBlocking<Unit> {
        val folder = fixture()
        val warnings = ArrayList<String>()
        try {
            val storage = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration(), warnings::add)
            val upload = (storage.directory("", "new.txt", false) as LanReceiveDirectory.Documents).open("new.txt")
            upload.output.write("owned partial".toByteArray()); upload.sync(); upload.output.close()
            val stage = app.graph.safFiles.list(folder).getOrThrow().single()
            app.contentResolver.openOutputStream(Uri.parse(stage.uri), "wt")!!.use { it.write("changed by a different writer: retain this data".toByteArray()) }
            assertTrue(runCatching { upload.close() }.isFailure)
            NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration(), warnings::add)
            assertEquals("changed by a different writer: retain this data", read(stage.uri))
            assertTrue(warnings.contains("Document recovery is required"))
            // The retained input above was created entirely by this test, not by an owner.
            app.graph.safFiles.delete(stage.uri).getOrThrow()
            NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
            val journal = File(app.filesDir, "nearby-receive-stages.json")
            assertFalse(journal.readText().contains(stage.uri.replace("/", "\\/")))
        } finally { app.graph.safFiles.delete(folder).getOrThrow() }
    }

    @Test fun sameLengthEditedPartialIsRetainedEvenWithTheSameCoarseTimestamp() = runBlocking<Unit> {
        val folder = fixture()
        try {
          var exercised = false
          for (attempt in 0 until 3) {
            // A SAF-only writer on Android8 cannot set a raw SD path's mtime.
            // Arrange writes inside the same natural two-second FAT bucket instead.
            kotlinx.coroutines.withTimeout(5_000) {
                while (System.currentTimeMillis() % 2_000 > 100) kotlinx.coroutines.delay(10)
            }
            val storage = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
            val upload = (storage.directory("", "new.txt", false) as LanReceiveDirectory.Documents).open("new.txt")
            upload.output.write("original bytes".toByteArray()); upload.sync(); upload.output.close()
            val stage = app.graph.safFiles.list(folder).getOrThrow().single()
            val uri = Uri.parse(stage.uri)
            val previous = app.contentResolver.openFileDescriptor(uri, "r")!!.use { android.system.Os.fstat(it.fileDescriptor) }
            app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write("modified bytes".toByteArray()) }
            val edited = app.contentResolver.openFileDescriptor(uri, "r")!!.use { android.system.Os.fstat(it.fileDescriptor) }
            assertEquals(previous.st_size, edited.st_size)
            if (previous.st_mtime != edited.st_mtime) {
                // Retry only arranging the input, never a failed ownership assertion.
                runCatching { upload.close() }
                app.graph.safFiles.delete(stage.uri).getOrThrow()
                NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
                continue
            }
            assertTrue(runCatching { upload.close() }.isFailure)
            NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
            assertEquals("modified bytes", read(stage.uri))
            app.graph.safFiles.delete(stage.uri).getOrThrow()
            NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
            exercised = true
            break
          }
          assertTrue("The same-size, same-time changed-content guard was not exercised", exercised)
        } finally { app.graph.safFiles.delete(folder).getOrThrow() }
    }

    @Test fun peerPathsCannotEscapeLocalOrGrantedDocumentRoots() = runBlocking<Unit> {
        val folder = fixture()
        val local = File(app.cacheDir, "receive-boundary-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            for (path in listOf(folder, local.path)) {
                val storage = NearbyReceiveStorage(app, app.graph.nearbySourceReader, path,
                    NearbyReceiveConfiguration(approvedRootPaths = listOf(local.path)))
                for (relative in listOf("../outside", "/absolute", "a/../../outside", "\\absolute", "x\u0000y")) {
                    assertTrue(relative, runCatching { storage.directory(relative, "file.txt", false) }.isFailure)
                    assertTrue(relative, runCatching { storage.createDirectory(relative) }.isFailure)
                }
            }
            assertTrue(app.graph.safFiles.list(folder).getOrThrow().isEmpty())
            assertTrue(local.listFiles()!!.isEmpty())
        } finally { app.graph.safFiles.delete(folder).getOrThrow(); check(local.deleteRecursively()) }
    }

    @Test fun simultaneousNameConflictRetainsTheWinnerAndReportsTheOtherCopy() = runBlocking<Unit> {
        val folder = fixture()
        val store = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
        val first = (store.directory("", "race.txt", false) as LanReceiveDirectory.Documents).open("race.txt")
        val second = (store.directory("", "race.txt", false) as LanReceiveDirectory.Documents).open("race.txt")
        try {
            first.output.write("first".toByteArray()); first.sync(); first.output.close()
            second.output.write("other".toByteArray()); second.sync(); second.output.close()
            first.commit(5) {}
            val failure = runCatching { second.commit(5) {} }.exceptionOrNull()
            assertTrue(failure.toString(), failure is com.affilemanager.app.workflow.AfDocumentConflictException)
            first.close(); second.close()
            val files = app.graph.safFiles.list(folder).getOrThrow()
            assertEquals(listOf("race.txt"), files.map { it.name })
            assertEquals("first", read(files.single().uri))
        } finally {
            runCatching { first.close() }; runCatching { second.close() }
            app.graph.safFiles.delete(folder).getOrThrow()
        }
    }

    @Test fun cancelledPublicationRemovesItsNewTargetAndStageButNotTheOriginal() = runBlocking<Unit> {
        val folder = fixture()
        val original = app.graph.safFiles.createFile(folder, "untouched.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(original.uri), "wt")!!.use { it.write("owner bytes".toByteArray()) }
        val store = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder, NearbyReceiveConfiguration())
        val upload = (store.directory("", "cancel.txt", false) as LanReceiveDirectory.Documents).open("cancel.txt")
        try {
            val bytes = ByteArray(512 * 1_024) { (it % 251).toByte() }
            upload.output.write(bytes); upload.sync(); upload.output.close()
            var checkpoints = 0
            val failure = runCatching { upload.commit(bytes.size.toLong()) {
                if (++checkpoints == 6) throw kotlinx.coroutines.CancellationException("Owned publication cancellation")
            } }.exceptionOrNull()
            assertTrue(failure.toString(), failure is kotlinx.coroutines.CancellationException)
            upload.close()
            assertEquals(listOf("untouched.txt"), app.graph.safFiles.list(folder).getOrThrow().map { it.name })
            assertEquals("owner bytes", read(original.uri))
        } finally { runCatching { upload.close() }; app.graph.safFiles.delete(folder).getOrThrow() }
    }

    private suspend fun fixture(): String {
        check(android.os.Build.MODEL.contains("sdk", true))
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission &&
            it.uri.authority == "com.android.externalstorage.documents" && !DocumentsContract.getTreeDocumentId(it.uri).startsWith("primary:") }
        org.junit.Assume.assumeNotNull("Owned virtual volume grant required", grant)
        return app.graph.safFiles.createDirectory(grant!!.uri.toString(), "AFReceiveSafety-${System.nanoTime()}").getOrThrow().uri
    }
    private fun read(uri: String) = app.contentResolver.openInputStream(Uri.parse(uri))!!.bufferedReader().use { it.readText() }
}
