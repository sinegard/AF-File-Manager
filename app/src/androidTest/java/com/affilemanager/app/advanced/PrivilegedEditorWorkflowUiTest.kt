package com.affilemanager.app.advanced

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.affilemanager.app.AFFileManagerApplication
import com.affilemanager.app.MainActivity
import com.affilemanager.app.editing.EditOrigin
import com.affilemanager.app.ui.MainViewModel
import com.affilemanager.app.ui.PreviewTarget
import com.affilemanager.app.uiAuditDirectory
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class PrivilegedEditorWorkflowUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun openingEditingAndSavingThroughAfPreservesAccessAndDiscardsThePrivateDraft() = exercise(false)

    @Test fun aReadableForeignOwnedFileRetainsItsOwnerWhenEditedThroughAf() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        exercise(true)
    }

    @Test fun anAppOwnedReadableFileKeepsItsLocalRouteWithRootConnected() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        exercise(readableForeignOwner = false, appOwned = true)
    }

    @Test fun anOrdinaryStagedReadablePreviewDoesNotStartRequiringPrivilegedAccess() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("afShizukuRoot") == "true")
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        // Outside FileProvider roots, but directly readable by the app without a provider.
        val root = File(app.noBackupFilesDir,"ordinary-staged-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val target = File(root,"document.txt").apply { writeText("unchanged ordinary preview") }
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            val service = app.graph.advancedAccess.fileMetadataServiceOrThrow()
            val original = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            service.applyFileMetadata(target.path,original.copy(uid=0,gid=0,mode=(original.mode and 0x1ff.inv()) or 0x1b6).encode())
            val before = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            assertTrue(target.canRead())
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.OFF); vm.open(app.graph.localFiles.toEntry(target)) }
            compose.waitUntil(10_000) { vm.fileEditState.value.session != null || vm.fileEditState.value.error != null }
            assertNull(vm.fileEditState.value.error)
            assertTrue(vm.preview.value is PreviewTarget.PrivilegedFile)
            assertTrue((vm.preview.value as PreviewTarget.PrivilegedFile).localOrigin)
            assertTrue(vm.fileEditState.value.session?.origin is EditOrigin.Local)
            val draft = checkNotNull(vm.fileEditState.value.session).workingFile
            compose.runOnUiThread { vm.closePreview() }
            compose.waitUntil(10_000) { vm.preview.value == null && !draft.exists() }
            assertEquals("unchanged ordinary preview",target.readText())
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected }
            assertEquals(before,PrivilegedFileMetadata.decode(app.graph.advancedAccess.fileMetadataServiceOrThrow().readFileMetadata(target.path)))
            accepted=true
        } finally {
            compose.runOnUiThread { vm.closePreview(); vm.setAdvancedAccessMode(previous) }
            if (accepted) check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit","Retained failed ordinary staged preview: $root")
        }
    }

    private fun exercise(readableForeignOwner: Boolean, appOwned: Boolean = false) {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedUid = if (arguments.getString("afShizukuRoot") == "true") 0 else 2_000
        assumeTrue(arguments.getString("afShizukuRoot") == "true" || arguments.getString("afShizukuShell") == "true")
        val app = compose.activity.application as AFFileManagerApplication
        val vm = ViewModelProvider(compose.activity)[MainViewModel::class.java]
        val previous = app.graph.advancedAccess.state.value.selectedMode
        val parent = if (expectedUid == 0) app.cacheDir else checkNotNull(app.getExternalFilesDir("validation"))
        val root = File(parent,"root-editor-ui-${System.nanoTime()}").canonicalFile.apply { check(mkdir()) }
        val target = File(root,"document.txt").apply { writeText("original") }
        if (expectedUid == 0) android.system.Os.chmod(target.path,0x1a0)
        val events = mutableListOf<org.json.JSONObject>()
        fun event(record: org.json.JSONObject) = synchronized(events) {
            if (events.size < 64) events.add(record.put("at",android.os.SystemClock.uptimeMillis()))
        }
        val monitor = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        monitor.launch {
            vm.advancedBrowser.collect { state -> event(org.json.JSONObject().put("kind","browser")
                .put("open",state.open).put("loading",state.loading).put("error",state.error)
                .put("sizes",org.json.JSONArray(state.entries.take(8).map { it.sizeBytes }))) }
        }
        monitor.launch {
            vm.fileEditState.collect { state -> event(org.json.JSONObject().put("kind","editor")
                .put("saving",state.saving).put("changed",state.textChanged).put("error",state.error)
                .put("hasSession",state.session != null).put("temporary",state.temporaryDownload)) }
        }
        var accepted = false
        try {
            compose.runOnUiThread { vm.setAdvancedAccessMode(AdvancedAccessMode.SHIZUKU); vm.requestShizukuAccess() }
            compose.waitUntil(20_000) { app.graph.advancedAccess.state.value.connected || app.graph.advancedAccess.state.value.error != null }
            assertEquals(expectedUid,app.graph.advancedAccess.state.value.serviceUid)
            val service = app.graph.advancedAccess.privilegedTerminalServiceOrThrow()
            if (expectedUid == 0 && !appOwned) {
                val ordinary = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
                val mode = if (readableForeignOwner) 0x1b6 else 0x180
                service.applyFileMetadata(target.path,ordinary.copy(uid=0,gid=0,mode=(ordinary.mode and 0x1ff.inv()) or mode).encode())
                if (readableForeignOwner) assertTrue(target.canRead() && target.canWrite())
                else assertFalse("The root workflow needs a file not directly readable by AF",target.canRead())
            }
            val before = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            compose.runOnUiThread { vm.openAdvancedBrowser(root.path) }
            compose.waitUntil(10_000) { vm.advancedBrowser.value.open && !vm.advancedBrowser.value.loading &&
                vm.advancedBrowser.value.path == root.path && vm.advancedBrowser.value.error == null }
            val browser = hasAnyAncestor(hasTestTag("advanced_list")) or hasAnyAncestor(hasTestTag("advanced_grid"))
            compose.onNode(hasText(target.name,substring=false) and browser).assertIsDisplayed().performTouchInput { click() }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("full-text-editor").fetchSemanticsNodes().isNotEmpty() }
            val origin = vm.fileEditState.value.session?.origin
            val privileged = expectedUid == 0 && !appOwned
            if (readableForeignOwner || appOwned || expectedUid == 2_000) {
                val preview = vm.preview.value
                assertTrue("Readable files keep their local preview and deletion behavior",preview is PreviewTarget.LocalFile)
                assertTrue((preview as PreviewTarget.LocalFile).advancedOrigin)
            }
            if (privileged && !readableForeignOwner) assertTrue(origin is EditOrigin.Privileged)
            else if (!privileged) assertTrue("App-owned readable files keep their direct-local route",origin is EditOrigin.Local)
            val draft = checkNotNull(vm.fileEditState.value.session).workingFile
            val text = "edited in AF\nsecond line\n"
            compose.onNodeWithTag("full-text-editor").performSemanticsAction(SemanticsActions.SetText) {
                assertTrue(it(AnnotatedString(text)))
            }
            compose.waitUntil(5_000) { vm.fileEditState.value.text == text }
            val captureMode = when {
                readableForeignOwner -> "readable-foreign-$expectedUid"
                appOwned -> "owned-readable-$expectedUid"
                else -> "$expectedUid"
            }
            capture(app,"privileged-editor-ready-$captureMode.png")
            compose.onNodeWithText("Save",substring=false).assertIsEnabled().performTouchInput { click() }
            compose.waitUntil(10_000) { !vm.fileEditState.value.saving && !vm.fileEditState.value.textChanged && vm.fileEditState.value.error == null }
            val saved = if (expectedUid == 0) app.graph.privilegedFiles.openInput(target.path).bufferedReader().use { it.readText() }
                else target.readText()
            assertEquals(text,saved)
            if (privileged && !readableForeignOwner) assertFalse(target.canRead())
            else assertTrue(target.canRead())
            val after = PrivilegedFileMetadata.decode(service.readFileMetadata(target.path))
            if (readableForeignOwner) File(uiAuditDirectory(app),"readable-foreign-owner.json").writeText(
                org.json.JSONObject().put("before",org.json.JSONArray(before.encode().toList()))
                    .put("after",org.json.JSONArray(after.encode().toList()))
                    .put("origin",origin?.javaClass?.simpleName).put("path",target.path).toString())
            assertTrue(before.sameSecurity(after))
            if (privileged) assertTrue(origin is EditOrigin.Privileged)
            compose.runOnUiThread { vm.closePreview() }
            compose.waitUntil(10_000) { vm.preview.value == null && !draft.exists() }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("file-preview-dialog").fetchSemanticsNodes().isEmpty() }
            captureSettled(app,"privileged-editor-saved-$captureMode.png")
            compose.waitUntil(10_000) {
                val state = vm.advancedBrowser.value
                !state.loading && state.error == null && state.entries.any {
                    it.absolutePath == target.path && it.sizeBytes == text.toByteArray(Charsets.UTF_8).size.toLong()
                }
            }
            assertEquals(setOf(target.name),root.list()!!.toSet())
            accepted=true
        } finally {
            monitor.cancel()
            if (!accepted) {
                val state = vm.advancedBrowser.value
                val record = org.json.JSONObject().put("path",state.path).put("loading",state.loading)
                    .put("error",state.error).put("open",state.open).put("directPath",target.path)
                    .put("directSize",target.length()).put("serviceUid",app.graph.advancedAccess.state.value.serviceUid)
                    .put("events",synchronized(events) { org.json.JSONArray(events.toList()) })
                    .put("entries",org.json.JSONArray(state.entries.take(8).map {
                        org.json.JSONObject().put("path",it.absolutePath).put("size",it.sizeBytes)
                    }))
                runCatching {
                    val remote = app.graph.advancedAccess.fileSystemOrThrow().getFile(target.path)
                    record.put("providerSize",remote.length()).put("providerPath",remote.absolutePath)
                }.onFailure { record.put("providerError",it.javaClass.simpleName) }
                runCatching {
                    compose.runOnUiThread { vm.refreshAdvancedBrowser() }
                    compose.waitUntil(10_000) { !vm.advancedBrowser.value.loading }
                    record.put("diagnosticReload",org.json.JSONArray(vm.advancedBrowser.value.entries.take(8).map {
                        org.json.JSONObject().put("path",it.absolutePath).put("size",it.sizeBytes)
                    })).put("reloadError",vm.advancedBrowser.value.error)
                }.onFailure { record.put("reloadFailure",it.javaClass.simpleName) }
                File(uiAuditDirectory(app),"editor-failure-state.json").writeText(record.toString())
            }
            compose.runOnUiThread { vm.closePreview(); vm.closeAdvancedBrowser(); vm.setAdvancedAccessMode(previous) }
            if (accepted) check(root.deleteRecursively())
            else android.util.Log.e("AFRootAudit","Retained failed root editor workflow: $root")
        }
    }

    private fun capture(app: AFFileManagerApplication,name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(uiAuditDirectory(app),name).outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))
            }
        } finally { bitmap.recycle() }
    }

    private fun captureSettled(app: AFFileManagerApplication,name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = android.os.SystemClock.uptimeMillis() + 5_000
        var stableSince = android.os.SystemClock.uptimeMillis()
        var previous: android.graphics.Bitmap? = null
        try {
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val current = checkNotNull(automation.takeScreenshot())
                val same = previous?.sameAs(current) == true
                previous?.recycle()
                previous = current
                if (!same) stableSince = android.os.SystemClock.uptimeMillis()
                if (same && android.os.SystemClock.uptimeMillis() - stableSince >= 300) {
                    File(uiAuditDirectory(app),name).outputStream().use { assertTrue(current.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
                    return
                }
                android.os.SystemClock.sleep(50)
            }
            error("Native window did not settle after preview close")
        } finally { previous?.recycle() }
    }
}
