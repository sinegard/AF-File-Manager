package com.affilemanager.app

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.localization.AppLanguageManager
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.regex.Pattern

/** Real DocumentsUI grants, not mocked paths or adopted shell permissions. */
class SafAddressWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun selectedProviderRootAndTypedChildRemainInsideTheGrantedTree() {
        compose.runOnUiThread { AppLanguageManager.setLanguage(compose.activity, "en") }
        compose.waitForIdle()
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val root = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), "AF-address-${System.nanoTime()}").apply { mkdirs() }
        val child = File(root, "child folder").apply { mkdir() }
        File(child, "visible.txt").writeText("provider fixture")
        val documentId = "primary:Documents/${root.name}"
        val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", documentId)
        var selected: Uri? = null
        var launcher: ActivityResultLauncher<Uri?>? = null
        try {
            compose.runOnUiThread {
                launcher = compose.activity.activityResultRegistry.register("saf-address-fixture", ActivityResultContracts.OpenDocumentTree()) { uri ->
                    selected = uri
                    if (uri != null) vm.addSafLocation(uri, "AF provider test")
                }
                launcher?.launch(initial)
            }
            val use = waitForLabel(Pattern.compile("(?i)(use this folder|select|use folder)"), 10_000)
            assertNotNull("DocumentsUI did not offer folder selection", use)
            assertTrue("DocumentsUI selection was disabled", use!!.isEnabled)
            click(use)
            waitForLabel(Pattern.compile("(?i)allow"), 2000)?.let(::click)
            compose.waitUntil(10_000) { selected != null && vm.safLocations.value.any { it.uri == selected.toString() } }
            val tree = requireNotNull(selected)
            // Older DocumentsUI can return home: rather than primary:Documents/.
            // Verify the real chosen directory, then use the provider's own URI.
            val grantedRoot = requireNotNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(compose.activity, tree))
            assertEquals(root.name, grantedRoot.name)
            val grantedChild = requireNotNull(grantedRoot.findFile(child.name))
            assertTrue(grantedChild.isDirectory)
            val childUri = grantedChild.uri
            kotlinx.coroutines.runBlocking {
                val app = compose.activity.application as AFFileManagerApplication
                val storage = com.affilemanager.app.workflow.AfStorageSessionFactory(app, app.graph.archives,
                    app.graph.networkProfiles, app.graph.remoteClients).open()
                try {
                    val local = com.affilemanager.app.workflow.AfLocationRef.local(root.path)
                    val document = com.affilemanager.app.workflow.AfLocationRef.document(tree.toString())
                    val plan = com.affilemanager.app.workflow.AfPlanDefinition(name = "Owned alias self-copy check",
                        sources = listOf(com.affilemanager.app.workflow.AfSourceRef(local, root.name)),
                        destinations = listOf(com.affilemanager.app.workflow.AfDestinationRef(
                            com.affilemanager.app.workflow.AfLocationRef.document(childUri.toString()))))
                    assertFalse("A native folder cannot be copied into its home-provider child alias",
                        com.affilemanager.app.workflow.AfPlanPreflight().preview(plan, storage).canRun)
                    val reverse = plan.copy(sources = listOf(com.affilemanager.app.workflow.AfSourceRef(document, root.name)),
                        destinations = listOf(com.affilemanager.app.workflow.AfDestinationRef(
                            com.affilemanager.app.workflow.AfLocationRef.local(child.path))))
                    assertFalse("A home-provider folder cannot be copied into its native child alias",
                        com.affilemanager.app.workflow.AfPlanPreflight().preview(reverse, storage).canRun)
                } finally { storage.close() }
            }
            compose.runOnUiThread { vm.openSafAddress(childUri.toString()) }
            compose.waitUntil(10_000) { vm.safBrowser.value.currentUri == childUri.toString() && !vm.safBrowser.value.loading }
            assertTrue(vm.safBrowser.value.entries.any { it.name == "visible.txt" })
            compose.onNodeWithTag("directory_address_saf").performClick()
            compose.onNodeWithTag("directory_address_input").performTextReplacement(tree.toString())
            compose.onNodeWithTag("directory_address_open").performClick()
            compose.waitUntil(10_000) { vm.safBrowser.value.currentUri == tree.toString() && !vm.safBrowser.value.loading }
            assertTrue(vm.safBrowser.value.entries.any { it.name == child.name })
            compose.onNodeWithContentDescription("File actions: ${child.name}").performClick()
            compose.onNodeWithText("Copy URI").performClick()
            var copied = ""
            compose.runOnUiThread {
                copied = compose.activity.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text.toString()
            }
            assertEquals(vm.safBrowser.value.entries.single { it.name == child.name }.uri, copied)
            compose.onNodeWithTag("directory_address_saf").performClick()
            compose.onNodeWithTag("directory_address_input").performTextReplacement(copied)
            compose.onNodeWithTag("directory_address_open").performClick()
            compose.waitUntil(10_000) { vm.safBrowser.value.currentUri == copied && !vm.safBrowser.value.loading }
        } finally {
            compose.runOnUiThread {
                launcher?.unregister()
                vm.closeSafBrowser()
                selected?.let { vm.removeSafLocation(it.toString()) }
            }
            selected?.let { uri -> compose.waitUntil(5000) { vm.safLocations.value.none { it.uri == uri.toString() } } }
            root.deleteRecursively()
        }
    }

    private fun click(node: AccessibilityNodeInfo) {
        var candidate: AccessibilityNodeInfo? = node
        repeat(5) {
            val current = candidate ?: return@repeat
            if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            candidate = current.parent
        }
        error("DocumentsUI control could not be activated")
    }

    private fun waitForLabel(pattern: Pattern, timeout: Long): AccessibilityNodeInfo? {
        val deadline = SystemClock.uptimeMillis() + timeout
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        do {
            val root = automation.rootInActiveWindow
            if (root != null) {
                val pending = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
                var visited = 0
                while (pending.isNotEmpty() && visited++ < 4096) {
                    val node = pending.removeFirst()
                    if (pattern.matcher(node.text?.toString().orEmpty()).matches()) return node
                    for (index in 0 until node.childCount.coerceAtMost(128)) node.getChild(index)?.let(pending::add)
                }
            }
            SystemClock.sleep(100)
        } while (SystemClock.uptimeMillis() < deadline)
        return null
    }
}
