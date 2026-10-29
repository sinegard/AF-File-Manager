package com.affilemanager.app

import android.content.Intent
import android.os.Build
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.operations.OperationContext
import com.affilemanager.app.operations.TransferVerification
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.localization.AppLanguageManager
import com.affilemanager.app.workflow.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Revokes a real grant on the owned virtual SD, then obtains fresh OS consent. */
class DocumentGrantRevocationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun revocationRetainsMoveSourceAndOffersFreshSystemConsent(): Unit = checkDeniedTransfer(false)

    @Test fun readOnlyGrantRetainsMoveSourceAndOffersWriteConsent(): Unit = checkDeniedTransfer(true)

    private fun checkDeniedTransfer(writeOnly: Boolean): Unit = runBlocking {
        org.junit.Assume.assumeTrue(Build.VERSION.SDK_INT in 26..29)
        compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, "en") }
        compose.waitForIdle()
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        compose.runOnUiThread { vm.refreshStorageRoots() }
        compose.waitUntil(10_000) { vm.roots.value.any { it.removable && it.title.contains("Virtual", true) } }
        val root = vm.roots.value.single { it.removable && it.title.contains("Virtual", true) }
        val grant = app.contentResolver.persistedUriPermissions.single { it.isReadPermission && it.isWritePermission &&
            com.affilemanager.app.data.RemovableStorageAccess.belongsToVolume(it.uri.toString(), root.id, rootOnly = true) }
        val name = "AF-document-revocation-${System.nanoTime()}"
        val folder = app.graph.safFiles.createDirectory(grant.uri.toString(), name).getOrThrow()
        val local = File(app.cacheDir, name).apply { check(mkdir()) }
        val original = File(local, "retained.txt").apply { writeText("original survives revoked access") }
        val storage = AfStorageSessionFactory(app, app.graph.archives, app.graph.networkProfiles, app.graph.remoteClients).open()
        var revoked = false
        try {
            val plan = AfPlanDefinition(name = "Owned revoked-grant move", fileManagerTransfer = true,
                sources = listOf(AfSourceRef(AfLocationRef.local(original.path), original.name)),
                destinations = listOf(AfDestinationRef(AfLocationRef.document(folder.uri))),
                verification = TransferVerification.SHA256, deleteSourcesAfterVerifiedCopies = true)
            val preview = AfPlanPreflight().preview(plan, storage)
            assertTrue(preview.blockers.toString(), preview.canRun)
            val repository = AfExecutionRepository(app)
            val initial = repository.create(preview)
            app.contentResolver.releasePersistableUriPermission(grant.uri,
                if (writeOnly) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            revoked = true
            if (writeOnly) assertNotNull("A read-only grant still allows reads", storage.stat(AfLocationRef.document(folder.uri)))
            val writer = object : AfExecutionWriter {
                override fun saveState(state: AfExecutionState) = repository.save(state)
                override fun saveJournal(executionId: String, items: List<AfReceiptItem>) = repository.saveJournal(executionId, items)
            }
            val outcome = runCatching { AfPlanEngine(File(app.cacheDir, "document-workflow-test"))
                .execute(preview, initial, emptyList(), storage, writer, OperationContext.background()) }
            assertTrue("A stale preview must not bypass revoked consent", outcome.isFailure)
            assertEquals("original survives revoked access", original.readText())
            assertFalse(File(root.path, "$name/retained.txt").exists())
            compose.runOnUiThread { vm.closeSafBrowser(); vm.openStorageRoot(root) }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.path == root.path }
            compose.onNodeWithTag("removable_write_access").assertIsDisplayed()
        } finally {
            storage.close()
            if (revoked) {
                // Compose actions advance its test clock; native touches inside this
                // Compose rule do not. Only the OS picker is driven by accessibility.
                compose.runOnUiThread { vm.closeSafBrowser(); vm.openStorageRoot(root) }
                compose.waitForIdle()
                compose.onNodeWithText("Grant access").performClick()
                DocumentWorkflowRuntimeVerifier.chooseOwnedVolumeInSystemPicker(InstrumentationRegistry.getInstrumentation(), root.title)
                compose.waitUntil(10_000) { app.contentResolver.persistedUriPermissions.any {
                    it.uri == grant.uri && it.isReadPermission && it.isWritePermission } }
                compose.waitUntil(10_000) { vm.safBrowser.value.location != null && !vm.safBrowser.value.loading }
            }
            app.graph.safFiles.delete(folder.uri).getOrThrow()
            check(local.deleteRecursively())
        }
    }
}
