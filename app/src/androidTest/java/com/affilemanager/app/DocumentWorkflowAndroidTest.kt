package com.affilemanager.app

import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.model.ConflictPolicy
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.workflow.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Uses a real, previously selected OS tree on the owned emulator, as the app UID. */
class DocumentWorkflowAndroidTest {
    private val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
    private val factory get() = AfStorageSessionFactory(app, app.graph.archives, app.graph.networkProfiles, app.graph.remoteClients)

    @Test fun documentAndFtpLocationsCopyBothDirectionsUsingTheSharedEngine(): Unit = runBlocking {
        val grant = removableGrant()
        val container = app.graph.safFiles.createDirectory(grant.toString(), "AF-document-ftp-${System.nanoTime()}").getOrThrow()
        val source = app.graph.safFiles.createFile(container.uri, "upload.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(source.uri), "wt")!!.use { it.write("document and FTP payload".toByteArray()) }
        val serverRoot = File(app.cacheDir, "AF-document-ftp-${System.nanoTime()}").apply { check(mkdir()) }
        val address = java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .first { it is java.net.Inet4Address && it.isSiteLocalAddress }
        val id = java.util.UUID.randomUUID().toString()
        com.affilemanager.app.transfer.LanFtpServer(serverRoot, address, anonymous = true).use { server ->
            val endpoint = server.start()
            val profile = com.affilemanager.app.network.NetworkProfile(id, "AF owned anonymous FTP test",
                com.affilemanager.app.network.NetworkProtocol.FTP, endpoint.address, endpoint.port, "anonymous", "/")
            // Public anonymous FTP convention, not an owner/server credential. Remove the temporary profile below.
            app.graph.networkProfiles.save(profile, "anonymous@".toCharArray()).getOrThrow()
            val storage = factory.open()
            try {
                val uploaded = runPlan(storage, AfSourceRef(AfLocationRef.document(container.uri, "upload.txt"), "upload.txt"),
                    AfLocationRef.remote(id, profile.name, "/"), false)
                assertEquals(AfExecutionStatus.COMPLETED, uploaded.first.status)
                assertEquals("document and FTP payload", File(serverRoot, "upload.txt").readText())
                check(File(serverRoot, "upload.txt").renameTo(File(serverRoot, "returned.txt")))
                val returned = runPlan(storage, AfSourceRef(AfLocationRef.remote(id, profile.name, "/returned.txt"), "returned.txt"),
                    AfLocationRef.document(container.uri), false)
                assertEquals(AfExecutionStatus.COMPLETED, returned.first.status)
                assertTrue(File(serverRoot, "returned.txt").isFile)
                val files = app.graph.safFiles.list(container.uri).getOrThrow()
                assertEquals(setOf("upload.txt", "returned.txt"), files.map { it.name }.toSet())
            } finally { storage.close(); app.graph.networkProfiles.remove(id).getOrThrow() }
        }
        app.graph.safFiles.delete(container.uri).getOrThrow()
        check(serverRoot.deleteRecursively())
    }

    @Test fun verifiedDirectoryCopyAndCutUseTheSameEngineBothDirections(): Unit = runBlocking {
        val grant = removableGrant()
        val name = "AF-document-workflow-${System.nanoTime()}"
        val local = File(app.cacheDir, name).apply { check(mkdir()) }
        val source = File(local, "source").apply { check(mkdir()) }
        File(source, "nested").mkdir()
        File(source, "nested/content.txt").writeText("Full provider workflow\nžąčé\n")
        File(source, "empty").mkdir()
        val destination = File(local, "returned").apply { check(mkdir()) }
        val container = app.graph.safFiles.createDirectory(grant.toString(), name).getOrThrow()
        val session = factory.open()
        try {
            val remote = AfLocationRef.document(container.uri)
            val copy = runPlan(session, AfSourceRef(AfLocationRef.local(source.path), source.name), remote, move = false)
            assertEquals(AfExecutionStatus.COMPLETED, copy.first.status)
            assertTrue(source.isDirectory)
            val copied = AfWorkflowPaths.child(remote, source.name)
            assertNotNull(session.stat(AfWorkflowPaths.child(copied, "nested/content.txt")))
            val move = runPlan(session, AfSourceRef(copied, source.name), AfLocationRef.local(destination.path), move = true)
            assertEquals("${move.first.errors}", AfExecutionStatus.COMPLETED, move.first.status)
            assertTrue(move.first.sourcesDeleted)
            assertNull(session.stat(copied))
            assertEquals(File(source, "nested/content.txt").readText(), File(destination, "source/nested/content.txt").readText())
            assertTrue(File(destination, "source/empty").isDirectory)
            File(uiAuditDirectory(app), "document-shared-workflow.json").writeText(JSONObject().put("sdk", Build.VERSION.SDK_INT)
                .put("uid", android.os.Process.myUid()).put("copy", copy.first.status.name).put("move", move.first.status.name)
                .put("sourcesDeleted", move.first.sourcesDeleted).toString())
        } finally {
            session.close()
            app.graph.safFiles.delete(container.uri).getOrThrow()
            check(local.deleteRecursively())
        }
    }

    @Test fun replacementKeepsAnUndoBackupAndSelfCopyIsBlocked(): Unit = runBlocking {
        val grant = removableGrant()
        val name = "AF-document-replace-${System.nanoTime()}"
        val local = File(app.cacheDir, name).apply { check(mkdir()) }
        val source = File(local, "data.txt").apply { writeText("new content") }
        val container = app.graph.safFiles.createDirectory(grant.toString(), name).getOrThrow()
        val existing = app.graph.safFiles.createFile(container.uri, source.name).getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(existing.uri), "wt")!!.use { it.write("old content".toByteArray()) }
        val session = factory.open()
        try {
            val root = AfLocationRef.document(container.uri)
            val result = runPlan(session, AfSourceRef(AfLocationRef.local(source.path), source.name), root, false, ConflictPolicy.REPLACE)
            assertEquals("${result.first.errors}", AfExecutionStatus.COMPLETED, result.first.status)
            val undo = AfTimelineUndoEngine()
            val preview = undo.preview(result.second, session)
            assertTrue(preview.toString(), preview.canRun)
            undo.execute(result.second, preview, session, OperationContext.background())
            val restored = app.graph.safFiles.list(container.uri).getOrThrow().single { it.name == source.name }
            assertEquals("old content", app.contentResolver.openInputStream(Uri.parse(restored.uri))!!.bufferedReader().use { it.readText() })
            val same = AfPlanPreflight().preview(plan(AfSourceRef(AfWorkflowPaths.child(root, source.name), source.name), root, true), session)
            assertFalse(same.toString(), same.canRun)
        } finally {
            session.close(); app.graph.safFiles.delete(container.uri).getOrThrow(); check(local.deleteRecursively())
        }
    }

    @Test fun emptyOnlyCleanupRetainsUnexpectedNewChildren(): Unit = runBlocking {
        val grant = removableGrant()
        val container = app.graph.safFiles.createDirectory(grant.toString(), "AF-document-retain-${System.nanoTime()}").getOrThrow()
        val child = app.graph.safFiles.createFile(container.uri, "unselected.txt").getOrThrow()
        val session = factory.open()
        try {
            val source = AfLocationRef.document(grant.toString(), container.name)
            assertTrue(runCatching { session.delete(source, recursive = false) }.isFailure)
            assertEquals(child.uri, app.graph.safFiles.list(container.uri).getOrThrow().single().uri)
        } finally { session.close(); app.graph.safFiles.delete(container.uri).getOrThrow() }
    }

    @Test fun moveRetainsAnUnselectedChildThatArrivesAfterTheCopy(): Unit = runBlocking {
        val grant = removableGrant()
        val container = app.graph.safFiles.createDirectory(grant.toString(), "AF-document-race-${System.nanoTime()}").getOrThrow()
        val source = app.graph.safFiles.createDirectory(container.uri, "source").getOrThrow()
        val file = app.graph.safFiles.createFile(source.uri, "selected.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(file.uri), "wt")!!.use { it.write("selected payload".toByteArray()) }
        val target = File(app.cacheDir, "AF-document-race-${System.nanoTime()}").apply { check(mkdir()) }
        val storage = factory.open()
        var arrived = false
        val racing = object : AfStorageSession by storage {
            override suspend fun install(sourceFile: File, destination: AfLocationRef, replace: Boolean, operation: OperationContext) {
                storage.install(sourceFile, destination, replace, operation)
                if (!arrived) {
                    arrived = true
                    val extra = app.graph.safFiles.createFile(source.uri, "unselected.txt").getOrThrow()
                    app.contentResolver.openOutputStream(Uri.parse(extra.uri), "wt")!!.use { it.write("retain this".toByteArray()) }
                }
            }
        }
        try {
            val outcome = runCatching { runPlan(racing, AfSourceRef(AfLocationRef.document(container.uri, "source"), "source"), AfLocationRef.local(target.path), true) }
            assertTrue(outcome.isFailure)
            assertEquals("selected payload", File(target, "source/selected.txt").readText())
            val retained = app.graph.safFiles.list(source.uri).getOrThrow().single { it.name == "unselected.txt" }
            assertEquals("retain this", app.contentResolver.openInputStream(Uri.parse(retained.uri))!!.bufferedReader().use { it.readText() })
        } finally { storage.close(); app.graph.safFiles.delete(container.uri).getOrThrow(); check(target.deleteRecursively()) }
    }

    @Test fun interruptedDirectoryCleanupRestoresGuardedDataWithoutRemovingNewChildren(): Unit = runBlocking {
        val grant = removableGrant()
        val container = app.graph.safFiles.createDirectory(grant.toString(), "AF-document-recovery-${System.nanoTime()}").getOrThrow()
        val source = app.graph.safFiles.createDirectory(container.uri, "source").getOrThrow()
        val id = java.util.UUID.randomUUID().toString()
        val guardName = ".af-empty-$id"
        val nonce = ByteArray(32) { it.toByte() }
        val guard = app.graph.safFiles.createFile(source.uri, guardName).getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(guard.uri), "wt")!!.use { it.write(nonce) }
        val extra = app.graph.safFiles.createFile(source.uri, "new-child.txt").getOrThrow()
        app.contentResolver.openOutputStream(Uri.parse(extra.uri), "wt")!!.use { it.write("not selected".toByteArray()) }
        val volume = DocumentsContract.getTreeDocumentId(grant).substringBefore(':')
        val external = app.getExternalFilesDirs(null).filterNotNull().single { it.path.startsWith("/storage/$volume/") }
        val recovery = File(external, "af-workflow-recovery/$id").apply { check(mkdirs()) }
        val holdingId = "$volume:Android/data/${app.packageName}/files/af-workflow-recovery/$id"
        val holdingUri = DocumentsContract.buildDocumentUriUsingTree(grant, holdingId)
        val held = DocumentsContract.buildDocumentUriUsingTree(grant, "$holdingId/source")
        val records = File(app.filesDir, "af-document-recovery-v1").apply { mkdirs() }
        val record = File(records, "$id.json")
        val json = JSONObject().put("schemaVersion", 2).put("source", source.uri).put("parent", container.uri).put("held", held)
            .put("path", File(recovery, "source").path).put("guard", guardName)
            .put("nonce", android.util.Base64.encodeToString(nonce, android.util.Base64.NO_WRAP))
        record.writeText(json.toString())
        DocumentsContract.moveDocument(app.contentResolver, Uri.parse(source.uri), Uri.parse(container.uri), holdingUri)
            ?: error("Interrupted-state fixture did not move")
        var recovered = false
        try {
            com.affilemanager.app.workflow.AfDocumentEmptyDirectoryRemoval(app).recoverPending()
            assertFalse(record.exists())
            assertFalse(File(recovery, "source").exists())
            val children = app.graph.safFiles.list(source.uri).getOrThrow()
            assertEquals(listOf("new-child.txt"), children.map { it.name })
            assertEquals("not selected", app.contentResolver.openInputStream(Uri.parse(children.single().uri))!!.bufferedReader().use { it.readText() })
            recovered = true
        } finally { if (recovered) app.graph.safFiles.delete(container.uri).getOrThrow() }
    }

    @Test fun cancellationRetainsTheSourceAfterACompletedCopy(): Unit = runBlocking {
        val grant = removableGrant()
        val container = app.graph.safFiles.createDirectory(grant.toString(), "AF-document-cancel-${System.nanoTime()}").getOrThrow()
        val local = File(app.cacheDir, "AF-document-cancel-${System.nanoTime()}").apply { check(mkdir()) }
        val source = File(local, "source.txt").apply { writeText("keep original") }
        val storage = factory.open()
        val cancelling = object : AfStorageSession by storage {
            override suspend fun install(sourceFile: File, destination: AfLocationRef, replace: Boolean, operation: OperationContext) {
                storage.install(sourceFile, destination, replace, operation)
                throw kotlinx.coroutines.CancellationException("Controlled cancellation after copy")
            }
        }
        try {
            val outcome = runCatching { runPlan(cancelling, AfSourceRef(AfLocationRef.local(source.path), source.name), AfLocationRef.document(container.uri), true) }
            assertTrue(outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
            assertEquals("keep original", source.readText())
            assertTrue(app.graph.safFiles.list(container.uri).getOrThrow().none { it.name.startsWith(".af-stage-") })
        } finally { storage.close(); app.graph.safFiles.delete(container.uri).getOrThrow(); check(local.deleteRecursively()) }
    }

    private fun removableGrant(): Uri {
      val grant = app.contentResolver.persistedUriPermissions.firstOrNull {
        it.isReadPermission && it.isWritePermission && it.uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.getTreeDocumentId(it.uri).let { id -> id.endsWith(":") && !id.startsWith("primary:") }
      }
      org.junit.Assume.assumeNotNull("An owned, OS-granted removable-volume fixture is required", grant)
      return requireNotNull(grant).uri
    }

    private fun plan(source: AfSourceRef, destination: AfLocationRef, move: Boolean, conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH) =
        AfPlanDefinition(name = "Provider test", sources = listOf(source), destinations = listOf(AfDestinationRef(destination)),
            verification = TransferVerification.SHA256, conflictPolicy = conflict, deleteSourcesAfterVerifiedCopies = move)

    private suspend fun runPlan(storage: AfStorageSession, source: AfSourceRef, destination: AfLocationRef,
                                move: Boolean, conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH): Pair<AfExecutionState, AfOperationReceipt> {
        val preview = AfPlanPreflight().preview(plan(source, destination, move, conflict), storage)
        assertTrue("${preview.blockers}", preview.canRun)
        val execution = AfExecutionRepository(app)
        val initial = execution.create(preview)
        val writer = object : AfExecutionWriter {
            override fun saveState(state: AfExecutionState) = execution.save(state)
            override fun saveJournal(executionId: String, items: List<AfReceiptItem>) = execution.saveJournal(executionId, items)
        }
        return AfPlanEngine(File(app.cacheDir, "document-workflow-test")).execute(preview, initial, emptyList(), storage, writer, OperationContext.background())
    }
}
