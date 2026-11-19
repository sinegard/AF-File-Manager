package com.affilemanager.app.transfer

import android.app.ActivityManager
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class NearbyReceiveStorageAndroidTest {
    private val app get() = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
    private val client = OkHttpClient.Builder().followRedirects(false).readTimeout(30, TimeUnit.SECONDS).build()

    @Test fun localFolderChangesKeepTheInFlightFileAndTheSameAuthenticatedSession() = runBlocking<Unit> {
        owned()
        val fixture = File(app.cacheDir, "receive-switch-${System.nanoTime()}").apply { check(mkdir()) }
        val first = File(fixture, "first").apply { check(mkdir()) }
        val second = File(fixture, "second").apply { check(mkdir()) }
        try { exerciseSwitch(first.path, second.path, listOf(fixture.path))
            assertEquals("part one and part two", File(first, "flight.txt").readText())
            assertFalse(File(second, "flight.txt").exists())
            assertEquals("next payload", File(second, "next.txt").readText())
        } finally { stopReceiver(); check(fixture.deleteRecursively()) }
    }

    @Test fun localToDocumentSwitchVerifiesBothCopiesAndDoesNotReplaceAConflict() = runBlocking<Unit> {
        owned()
        val grant = grant()
        val folder = app.graph.safFiles.createDirectory(grant.toString(), "AFReceive-${System.nanoTime()}").getOrThrow()
        val conflict = app.graph.safFiles.createFile(folder.uri, "next.txt").getOrThrow()
        write(conflict.uri, "original conflict")
        val local = File(app.cacheDir, "receive-local-document-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            exerciseSwitch(local.path, folder.uri, listOf(local.path))
            assertEquals("part one and part two", File(local, "flight.txt").readText())
            val files = app.graph.safFiles.list(folder.uri).getOrThrow()
            assertEquals(setOf("next.txt", "next (1).txt"), files.map { it.name }.toSet())
            assertEquals("original conflict", read(conflict.uri))
            assertEquals("next payload", read(files.single { it.name == "next (1).txt" }.uri))
            val row = LanTransferController.state.value.incomingUpload!!.files.last()
            assertEquals("next (1).txt", row.savedName)
            assertEquals("next payload", read(requireNotNull(row.localPath)))
        } finally { stopReceiver(); app.graph.safFiles.delete(folder.uri).getOrThrow(); check(local.deleteRecursively()) }
    }

    @Test fun aDocumentStageAlreadyBeingWrittenDoesNotFollowTheNewLocalFolder() = runBlocking<Unit> {
        owned()
        val folder = app.graph.safFiles.createDirectory(grant().toString(), "AFReceive-${System.nanoTime()}").getOrThrow()
        val local = File(app.cacheDir, "receive-document-local-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            exerciseSwitch(folder.uri, local.path, listOf(local.path))
            val files = app.graph.safFiles.list(folder.uri).getOrThrow()
            assertEquals(listOf("flight.txt"), files.map { it.name })
            assertEquals("part one and part two", read(files.single().uri))
            assertEquals("next payload", File(local, "next.txt").readText())
        } finally { stopReceiver(); app.graph.safFiles.delete(folder.uri).getOrThrow(); check(local.deleteRecursively()) }
    }

    @Test fun actualNearbySenderPublishesADocumentUriAndLeavesTheOriginalUntouched() = runBlocking<Unit> {
        owned()
        val folder = app.graph.safFiles.createDirectory(grant().toString(), "AFReceive-${System.nanoTime()}").getOrThrow()
        val source = File(app.cacheDir, "nearby-source-${System.nanoTime()}.txt").apply { writeText("actual AF service sender to OS document receiver") }
        try {
            val receiver = startReceiver(folder.uri, emptyList())
            val parsed = java.net.URI(receiver.url!!)
            val prepared = app.graph.nearbySources.prepareLocalPaths(listOf(source.path)).getOrThrow()
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            NearbyTransferController.start(app, NearbyPairing.create(parsed.host, parsed.port, receiver.code!!), prepared)
            withTimeout(10_000) { while (LanTransferController.state.value.pendingSenders.isEmpty()) delay(25) }
            approveOwnedRequest()
            withTimeout(30_000) {
                while (NearbyTransferController.state.value.status in setOf(NearbyTransferStatus.STARTING, NearbyTransferStatus.RUNNING)) delay(25)
            }
            assertEquals(NearbyTransferController.state.value.message, NearbyTransferStatus.COMPLETED, NearbyTransferController.state.value.status)
            val files = app.graph.safFiles.list(folder.uri).getOrThrow()
            val diagnostics = org.json.JSONArray()
            files.forEach { file ->
                val row = org.json.JSONObject().put("name", file.name).put("uri", file.uri).put("size", file.sizeBytes)
                app.contentResolver.openFileDescriptor(Uri.parse(file.uri), "r")?.use { descriptor ->
                    val stat = android.system.Os.fstat(descriptor.fileDescriptor)
                    row.put("dev", stat.st_dev).put("ino", stat.st_ino).put("mtime", stat.st_mtime).put("actualSize", stat.st_size)
                }
                diagnostics.put(row)
            }
            File(com.affilemanager.app.uiAuditDirectory(app), "document-receive-cleanup.json").writeText(org.json.JSONObject()
                .put("files", diagnostics).put("message", LanTransferController.state.value.message)
                .put("journal", File(app.filesDir, "nearby-receive-stages.json").takeIf { it.exists() }?.readText()).toString())
            assertEquals(1, files.size)
            assertEquals(source.readText(), read(files.single().uri))
            val row = LanTransferController.state.value.incomingUpload!!.files.single()
            assertEquals(TransferFileStatus.COMPLETED, row.status)
            assertEquals(files.single().uri, row.localPath)
            assertTrue(source.isFile)
            val viewModels = androidx.lifecycle.ViewModelStore()
            val vm = com.affilemanager.app.ui.MainViewModel(app).also { viewModels.put("receipt", it) }
            try {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    vm.openNearbyTransferEntry(com.affilemanager.app.model.FileEntry(requireNotNull(row.localPath), files.single().name,
                        com.affilemanager.app.model.EntryKind.DOCUMENT, source.length(), 0, false, true, true))
                }
                withTimeout(10_000) { while (vm.preview.value == null) delay(25) }
                val preview = vm.preview.value as com.affilemanager.app.ui.PreviewTarget.ContentFile
                assertEquals(row.localPath, preview.entry.uri.toString())
                assertEquals(source.readText(), read(preview.entry.uri.toString()))
            } finally { viewModels.clear() }
        } finally {
            NearbyTransferController.connection.clear(); NearbyTransferController.clearFinished()
            stopReceiver(); app.graph.safFiles.delete(folder.uri).getOrThrow(); check(source.delete())
        }
    }

    @Test fun stoppedDocumentStartupCannotReplaceTheNextLocalSession() = runBlocking<Unit> {
        owned()
        val folder = app.graph.safFiles.createDirectory(grant().toString(), "AFReceiveLifecycle-${System.nanoTime()}").getOrThrow()
        val local = File(app.cacheDir, "receive-lifecycle-${System.nanoTime()}").apply { check(mkdir()) }
        try {
            repeat(3) { index ->
                stopReceiver()
                val before = LanTransferController.state.value
                LanTransferController.start(app, folder.uri, phoneReceiver = true)
                withTimeout(10_000) { while (LanTransferController.state.value === before) delay(5) }
                stopReceiver()
                val running = startReceiver(local.path, listOf(local.path))
                delay(200)
                assertEquals(LanTransferStatus.RUNNING, LanTransferController.state.value.status)
                assertEquals(local.path, LanTransferController.state.value.rootPath)
                client.newCall(Request.Builder().url(running.url + "upload?name=cycle-$index.txt")
                    .header("Cookie", login(running)).post("cycle $index".toRequestBody()).build()).execute().use {
                    assertEquals(it.body?.string(), 201, it.code)
                }
                assertEquals("cycle $index", File(local, "cycle-$index.txt").readText())
            }
            assertTrue(app.graph.safFiles.list(folder.uri).getOrThrow().isEmpty())
        } finally { stopReceiver(); app.graph.safFiles.delete(folder.uri).getOrThrow(); check(local.deleteRecursively()) }
    }

    private suspend fun exerciseSwitch(first: String, second: String, allowed: List<String>) {
        val receiver = startReceiver(first, allowed)
        val cookie = login(receiver)
        val sent = CountDownLatch(1)
        val continueBody = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val sending = executor.submit<Int> {
                val body = object : RequestBody() {
                    override fun contentType() = null
                    override fun contentLength() = "part one and part two".toByteArray().size.toLong()
                    override fun writeTo(sink: okio.BufferedSink) {
                        sink.writeUtf8("part one "); sink.flush(); sent.countDown()
                        check(continueBody.await(15, TimeUnit.SECONDS))
                        sink.writeUtf8("and part two")
                    }
                }
                client.newCall(Request.Builder().url(receiver.url + "upload?name=flight.txt").header("Cookie", cookie).post(body).build()).execute().use { it.code }
            }
            assertTrue(sent.await(10, TimeUnit.SECONDS))
            withTimeout(15_000) { while (LanTransferController.state.value.incomingUpload?.currentFileBytes?.let { it > 0 } != true) delay(25) }
            val commit = withContext(Dispatchers.IO) {
                LanTransferController.prepareReceivePathChange(app, second, NearbyReceiveConfiguration(1, approvedRootPaths = allowed))
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { commit() }
            assertEquals(receiver.url, LanTransferController.state.value.url)
            assertEquals(receiver.code, LanTransferController.state.value.code)
            assertEquals(second, LanTransferController.state.value.rootPath)
            continueBody.countDown()
            assertEquals(201, sending.get(20, TimeUnit.SECONDS))
            client.newCall(Request.Builder().url(receiver.url + "upload?name=next.txt").header("Cookie", cookie)
                .post("next payload".toRequestBody()).build()).execute().use { assertEquals(it.body?.string(), 201, it.code) }
            client.newCall(Request.Builder().url(receiver.url + "list").header("Cookie", cookie).get().build()).execute().use {
                assertEquals(200, it.code); assertTrue(it.body!!.string().contains("next"))
            }
            assertEquals(receiver.url, LanTransferController.state.value.url)
        } finally { continueBody.countDown(); executor.shutdownNow() }
    }

    private suspend fun startReceiver(path: String, allowed: List<String>): LanTransferState {
        stopReceiver()
        val before = LanTransferController.state.value
        LanTransferController.start(app, path, options = LanTransferOptions(password = "12345678"), phoneReceiver = true,
            receiveConfiguration = NearbyReceiveConfiguration(1, approvedRootPaths = allowed))
        return withTimeout(15_000) {
            while (LanTransferController.state.value === before || LanTransferController.state.value.status !in setOf(LanTransferStatus.RUNNING, LanTransferStatus.ERROR)) delay(25)
            LanTransferController.state.value.also { assertEquals(it.message, LanTransferStatus.RUNNING, it.status) }
        }
    }
    private suspend fun stopReceiver() {
        val manager = app.getSystemService(ActivityManager::class.java)
        @Suppress("DEPRECATION")
        fun running() = manager.getRunningServices(100).any { it.service.className == LanTransferService::class.java.name }
        if (running()) LanTransferController.stop(app)
        withTimeout(10_000) { while (running()) delay(25) }
    }
    private fun login(receiver: LanTransferState): String {
        val endpoint = java.net.URI(receiver.url!!)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit<String> {
                NearbyLoginClient.login(client, NearbyPairing.create(endpoint.host, endpoint.port, receiver.code!!), "Owned storage fixture").cookie
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (LanTransferController.state.value.pendingSenders.isEmpty() && System.nanoTime() < deadline) Thread.sleep(25)
            approveOwnedRequest()
            return pending.get(15, TimeUnit.SECONDS)
        } finally { executor.shutdownNow() }
    }
    private fun approveOwnedRequest() {
        val current = LanTransferController.state.value
        assertTrue("The fixture must request consent first", current.phoneReceiver)
        val request = current.pendingSenders.single()
        LanTransferController.decideSender(app, current.receiveSessionId!!, request.id, true)
    }
    private fun grant(): Uri {
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission &&
            it.uri.authority == "com.android.externalstorage.documents" && !DocumentsContract.getTreeDocumentId(it.uri).startsWith("primary:") }
        org.junit.Assume.assumeNotNull("Owned OS-selected virtual volume required", grant)
        return grant!!.uri
    }
    private fun write(uri: String, text: String) { app.contentResolver.openOutputStream(Uri.parse(uri), "wt")!!.use { it.write(text.toByteArray()) } }
    private fun read(uri: String): String = app.contentResolver.openInputStream(Uri.parse(uri))!!.bufferedReader().use { it.readText() }
    private fun owned() { check(android.os.Build.MODEL.contains("sdk", true)) }
}
