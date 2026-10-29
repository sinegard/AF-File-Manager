package com.affilemanager.app

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.data.TransferOptions
import com.affilemanager.app.operations.DurableTransferStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class TransferPreferenceFailureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun aRealSettingsWriteFailureIsVisibleInTheDialogAndRetryCopiesOnlyOnce() {
        val app = compose.activity.application as AFFileManagerApplication
        check(Build.MODEL.contains("sdk", true) && app.packageName.endsWith(".debug")) { "Disposable emulator only" }
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = vm.transferPreferences.value
        val root = File(app.getExternalFilesDir("validation"), "save-failure-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = File(root, "source").apply { check(mkdir()) }
        val destination = File(root, "destination").apply { check(mkdir()) }
        val input = File(source, "original.txt").apply { writeText("must not be removed or copied before the settings are saved") }
        val digest = MessageDigest.getInstance("SHA-256").digest(input.readBytes())
        var fault: PreferenceWriteFault? = null
        try {
            compose.runOnUiThread {
                check(vm.saveTransferOptions(false, TransferOptions.defaults(false)))
                vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, source.path)
            }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.any { it.absolutePath == input.path } }
            compose.runOnUiThread {
                vm.selectOnly(PanelId.LEFT, input.path); vm.copySelection(PanelId.LEFT, false)
                vm.navigate(PanelId.LEFT, destination.path)
            }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.path == destination.path }
            compose.onNodeWithContentDescription("Folder actions").performClick()
            compose.onNodeWithText("Paste", useUnmergedTree = true).performClick()
            // A no-op SharedPreferences commit need not touch disk. Force a real changed value.
            compose.onNodeWithTag("transfer_options_sha256").performScrollTo().assertIsOff().performClick()
            val ids = app.graph.durableTransferRepository.list().map { it.plan.id }.toSet()
            fault = PreferenceWriteFault(app)
            compose.onNodeWithTag("transfer_options_confirm").performClick()
            compose.onNodeWithTag("transfer_options_dialog").assertExists()
            compose.onNodeWithTag("transfer_options_error").assertIsDisplayed()
            assertEquals(ids, app.graph.durableTransferRepository.list().map { it.plan.id }.toSet())
            assertTrue(destination.listFiles().orEmpty().isEmpty())
            assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(input.readBytes()))
            fault.close(); fault = null
            compose.onNodeWithTag("transfer_options_confirm").performClick()
            compose.waitUntil(10_000) {
                app.graph.durableTransferRepository.list().any { it.plan.sourceRoots == listOf(input.path) && it.state.status == DurableTransferStatus.COMPLETED }
            }
            assertEquals(1, destination.listFiles().orEmpty().size)
            assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(input.readBytes()))
            assertArrayEquals(digest, MessageDigest.getInstance("SHA-256").digest(File(destination, input.name).readBytes()))
        } finally {
            fault?.close()
            compose.runOnUiThread {
                check(vm.saveTransferOptions(false, previous.copy)); check(vm.saveTransferOptions(true, previous.move))
                vm.navigate(PanelId.LEFT, root.parentFile!!.path)
            }
            check(root.deleteRecursively())
        }
    }

    /** Reversible EISDIR fault, restricted to the disposable debug app's own preference file. */
    private class PreferenceWriteFault(app: AFFileManagerApplication) : Closeable {
        private val token = UUID.randomUUID().toString()
        private val backup = File(app.cacheDir, "preference-backup-$token").apply { check(mkdir()) }
        private val directory = File(app.applicationInfo.dataDir, "shared_prefs").canonicalFile
        private val records = mutableListOf<Triple<File, File, ByteArray?>>()
        private var closed = false
        init {
            try {
                for (name in listOf("ui_preferences_v1.xml", "ui_preferences_v1.xml.bak")) {
                    val original = File(directory, name)
                    check(!original.isDirectory && !java.nio.file.Files.isSymbolicLink(original.toPath()))
                    val bytes = original.takeIf(File::isFile)?.readBytes()
                    val saved = File(backup, name)
                    if (bytes != null) check(original.renameTo(saved))
                    try {
                        check(original.mkdir())
                        File(original, token).writeText(token)
                        records += Triple(original, saved, bytes)
                    } catch (failure: Throwable) {
                        File(original, token).takeIf { it.isFile && it.readText() == token }?.delete()
                        if (original.isDirectory) check(original.delete())
                        if (bytes != null) check(saved.renameTo(original))
                        throw failure
                    }
                }
            } catch (failure: Throwable) { close(); throw failure }
        }
        override fun close() {
            if (closed) return
            for ((blocked, saved, bytes) in records) {
                check(requireNotNull(blocked.parentFile).canonicalFile == directory)
                val children = blocked.listFiles().orEmpty()
                check(children.size == 1 && children.single().name == token && children.single().readText() == token)
                check(children.single().delete()); check(blocked.delete())
                if (bytes != null) {
                    check(saved.renameTo(blocked)); assertArrayEquals(bytes, blocked.readBytes())
                }
            }
            check(backup.delete()); closed = true
        }
    }
}
