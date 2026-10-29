package com.affilemanager.app

import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.workflow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Driver unmounts only its owned virtual disk before this test and remounts it afterward. */
class DocumentUnavailableStorageTest {
    @Test fun unavailableGrantedVolumeBlocksTheMoveWithoutTouchingTheLocalSource(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<AFFileManagerApplication>()
        val grant = app.contentResolver.persistedUriPermissions.single { it.isReadPermission && it.isWritePermission &&
            it.uri.authority == "com.android.externalstorage.documents" &&
            DocumentsContract.getTreeDocumentId(it.uri).let { id -> id.endsWith(":") && !id.startsWith("primary:") } }
        val volume = DocumentsContract.getTreeDocumentId(grant.uri).substringBefore(':')
        check(!File("/storage/$volume").isDirectory) { "The explicitly owned virtual volume must already be unmounted" }
        val source = File(app.cacheDir, "AF-document-unmounted-${System.nanoTime()}.txt").apply { writeText("retain offline move source") }
        val storage = AfStorageSessionFactory(app, app.graph.archives, app.graph.networkProfiles, app.graph.remoteClients).open()
        try {
            val plan = AfPlanDefinition(name = "Owned offline-volume move", fileManagerTransfer = true,
                sources = listOf(AfSourceRef(AfLocationRef.local(source.path), source.name)),
                destinations = listOf(AfDestinationRef(AfLocationRef.document(grant.uri.toString()))),
                verification = TransferVerification.SHA256, deleteSourcesAfterVerifiedCopies = true)
            val result = runCatching { AfPlanPreflight().preview(plan, storage) }
            assertTrue("Offline destinations must never produce a runnable move", result.isFailure || !result.getOrThrow().canRun)
            assertEquals("retain offline move source", source.readText())
        } finally { storage.close(); check(source.delete()) }
    }
}
