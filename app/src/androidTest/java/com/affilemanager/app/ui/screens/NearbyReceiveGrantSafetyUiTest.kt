package com.affilemanager.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.DocumentWorkflowRuntimeVerifier
import com.affilemanager.app.transfer.*
import com.affilemanager.app.ui.MainViewModel
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NearbyReceiveGrantSafetyUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun lostGrantNeverPublishesAndRealSystemConsentRecoversUnchangedOwnedStages() = runBlocking<Unit> {
        check(android.os.Build.MODEL.contains("sdk", true))
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.isReadPermission && it.isWritePermission &&
            it.uri.authority == "com.android.externalstorage.documents" && !DocumentsContract.getTreeDocumentId(it.uri).startsWith("primary:") }
        org.junit.Assume.assumeNotNull("Owned OS-selected virtual-volume fixture required", grant)
        val tree = requireNotNull(grant).uri
        val id = DocumentsContract.getTreeDocumentId(tree)
        val volume = app.getSystemService(StorageManager::class.java).storageVolumes.single { it.uuid == id.substringBefore(':') }
        check(volume.getDescription(app).contains("Virtual", true))
        val folder = app.graph.safFiles.createDirectory(tree.toString(), "AFReceiveGrant-${System.nanoTime()}").getOrThrow()
        val original = app.graph.safFiles.createFile(folder.uri, "original.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(original.uri), "wt")!!.use { it.write("owner original remains".toByteArray()) }
        val viewModels = ViewModelStore()
        val vm = MainViewModel(app).also { viewModels.put("test", it) }
        val before = vm.shareScreenPreferences.value
        val latest = AtomicReference<LanUploadProgress>()
        val warnings = ArrayList<String>()
        val storage = NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder.uri, NearbyReceiveConfiguration(), { warnings += it })
        val address = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is Inet4Address && it.isSiteLocalAddress }
        val client = OkHttpClient.Builder().followRedirects(false).readTimeout(30, TimeUnit.SECONDS).build()
        val executor = Executors.newSingleThreadExecutor()
        val sent = CountDownLatch(1)
        val finish = CountDownLatch(1)
        var restored = false
        try {
            compose.setContent { MaterialTheme {
                Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    NearbyReceiveLocation(vm, LanTransferState())
                }
            } }
            LanHttpServer(app.cacheDir, address, requestedCode = "12345678", receiveStorage = storage,
                onUploadProgress = latest::set).use { server ->
                val receiver = server.start()
                val cookie = client.newCall(Request.Builder().url(receiver.url + "login")
                    .post(FormBody.Builder().add("code", receiver.code).build()).build()).execute().use {
                    assertEquals(200, it.code); it.header("Set-Cookie")!!.substringBefore(';')
                }
                val request = executor.submit<Pair<Int, String?>> {
                    val body = object : RequestBody() {
                        override fun contentType() = null
                        override fun contentLength() = 32_768L
                        override fun writeTo(sink: okio.BufferedSink) {
                            sink.write(ByteArray(16_384) { 11 }); sink.flush(); sent.countDown()
                            check(finish.await(15, TimeUnit.SECONDS))
                            sink.write(ByteArray(16_384) { 22 })
                        }
                    }
                    client.newCall(Request.Builder().url(receiver.url + "upload?name=revoked.txt").header("Cookie", cookie).post(body).build())
                        .execute().use { it.code to it.header(LanHttpServer.ERROR_CODE_HEADER) }
                }
                assertTrue(sent.await(10, TimeUnit.SECONDS))
                withTimeout(10_000) { while ((latest.get()?.currentFileBytes ?: 0) == 0L) delay(25) }
                app.contentResolver.releasePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                finish.countDown()
                val response = request.get(15, TimeUnit.SECONDS)
                assertTrue(response.first >= 400)
                assertEquals(TransferFailure.PERMISSION.code, response.second)
                assertTrue(latest.get().files.all { it.status != TransferFileStatus.COMPLETED && it.localPath == null })

                compose.onNodeWithTag("nearby_receive_path").performClick()
                compose.onNodeWithTag("nearby_choose_receive_path").performClick()
                compose.onNodeWithTag("share_folder_more").performClick()
                compose.onNodeWithTag("share_folder_android_picker").performClick()
                val child = id.substringAfter(':').takeIf(String::isNotEmpty)
                check(child == null || '/' !in child)
                DocumentWorkflowRuntimeVerifier.chooseOwnedVolumeInSystemPicker(InstrumentationRegistry.getInstrumentation(), volume.getDescription(app), child)
                compose.waitUntil(20_000) { !vm.nearbyReceiveFolderChanging.value &&
                    app.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isReadPermission && it.isWritePermission } }
                assertNull(vm.nearbyReceiveFolderError.value)
                restored = true
                NearbyReceiveStorage(app, app.graph.nearbySourceReader, folder.uri, NearbyReceiveConfiguration())
                val after = app.graph.safFiles.list(folder.uri).getOrThrow()
                val details = org.json.JSONArray()
                after.forEach { item ->
                    val detail = org.json.JSONObject().put("uri", item.uri).put("name", item.name).put("size", item.sizeBytes)
                    app.contentResolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { fd ->
                        val stat = android.system.Os.fstat(fd.fileDescriptor)
                        detail.put("dev", stat.st_dev).put("ino", stat.st_ino).put("sizeActual", stat.st_size).put("mtime", stat.st_mtime)
                    }
                    details.put(detail)
                }
                java.io.File(com.affilemanager.app.uiAuditDirectory(app), "revoked-receive-recovery.json").writeText(org.json.JSONObject()
                    .put("files", details).put("journal", java.io.File(app.filesDir, "nearby-receive-stages.json").takeIf { it.exists() }?.readText()).toString())
                assertEquals(listOf("original.txt"), after.map { it.name })
                assertEquals("owner original remains", app.contentResolver.openInputStream(Uri.parse(original.uri))!!.bufferedReader().use { it.readText() })
                // The same listener and cookie work after consent; no pairing restart.
                client.newCall(Request.Builder().url(receiver.url + "upload?name=after.txt").header("Cookie", cookie)
                    .post("after real consent".toRequestBody()).build()).execute().use { assertEquals(it.body?.string(), 201, it.code) }
                val file = app.graph.safFiles.list(folder.uri).getOrThrow().single { it.name == "after.txt" }
                assertEquals("after real consent", app.contentResolver.openInputStream(Uri.parse(file.uri))!!.bufferedReader().use { it.readText() })
            }
        } finally {
            finish.countDown(); executor.shutdownNow()
            vm.updateShareScreenPreferences { before }; viewModels.clear()
            if (restored) app.graph.safFiles.delete(folder.uri).getOrThrow()
            else android.util.Log.e("AFRootAudit", "Owned grant-failure fixture retained for investigation: ${folder.uri}")
        }
    }
}
