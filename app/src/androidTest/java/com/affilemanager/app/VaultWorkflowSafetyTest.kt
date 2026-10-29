package com.affilemanager.app

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.affilemanager.app.operations.OperationStatus
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PanelId
import com.affilemanager.app.ui.localization.AppLanguageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class VaultWorkflowSafetyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun repeatingTheRealEncryptionDialogKeepsBothVaultsAndPreservesTheOriginal() = runBlocking {
        val app = compose.activity.application as AFFileManagerApplication
        check(Build.MODEL.contains("sdk", true) && app.packageName.endsWith(".debug"))
        val root = File(app.getExternalFilesDir("validation"), "vault-ui-${UUID.randomUUID()}").apply { check(mkdir()) }
        val source = root.resolve("document.txt").apply { writeText("original text remains unchanged") }
        val original = digest(source)
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        try {
            compose.runOnUiThread {
                AppLanguageManager.setLanguage(compose.activity, "en")
                vm.activatePanel(PanelId.LEFT); vm.navigate(PanelId.LEFT, root.path, rememberHistory = false)
            }
            compose.waitUntil(10_000) { !vm.leftPanel.value.loading && vm.leftPanel.value.entries.any { it.absolutePath == source.path } }
            compose.runOnUiThread { vm.selectPaths(PanelId.LEFT, listOf(source.path)) }
            compose.onNodeWithTag("nav_tools").performClick()
            compose.onNodeWithTag("settings_section_privacy").performScrollTo().performClick()
            var firstDigest: ByteArray? = null
            for ((index, password) in listOf("fixture-one-key", "fixture-two-key").withIndex()) {
                val before = vm.operations.value.map { it.id }.toSet()
                compose.onNodeWithTag("tools_list").performScrollToNode(hasText("Encrypt selected file"))
                compose.onNodeWithText("Encrypt selected file").assertIsDisplayed().assertIsEnabled().performClick()
                compose.onNode(hasSetTextAction() and hasText("Passphrase", substring = true)).performTextInput(password)
                compose.onNode(hasSetTextAction() and hasText("Repeat", substring = true)).performTextInput(password)
                compose.onNodeWithText("Encrypt").performClick()
                compose.waitUntil(15_000) {
                    vm.operations.value.any { it.id !in before && it.status in setOf(OperationStatus.SUCCEEDED, OperationStatus.FAILED, OperationStatus.COMPLETED_WITH_ERRORS) }
                }
                assertEquals(OperationStatus.SUCCEEDED, vm.operations.value.single { it.id !in before }.status)
                val vaults = root.listFiles().orEmpty().filter { it.extension == "afvault" }
                assertEquals("Repeated encryption replaced its preceding result", index + 1, vaults.size)
                val first = root.resolve("document.txt.afvault")
                if (index == 0) firstDigest = digest(first) else assertArrayEquals(firstDigest, digest(first))
                assertArrayEquals(original, digest(source))
                assertTrue(root.listFiles().orEmpty().none { it.name.startsWith(".af-vault-") || it.name.endsWith(".partial") })
            }
            for ((name, password) in listOf("document.txt.afvault" to "fixture-one-key", "document.txt (1).afvault" to "fixture-two-key")) {
                val destination = root.resolve("restore-${UUID.randomUUID()}").apply { check(mkdir()) }
                val restored = app.graph.fileVault.decrypt(root.resolve(name), destination, password.toCharArray())
                assertArrayEquals(original, digest(restored))
                assertEquals(1, destination.listFiles()!!.size)
            }
        } finally {
            compose.runOnUiThread { vm.clearSelection(PanelId.LEFT); vm.navigate(PanelId.LEFT, root.parentFile!!.path, rememberHistory = false) }
            check(root.deleteRecursively())
        }
    }

    private fun digest(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
}
